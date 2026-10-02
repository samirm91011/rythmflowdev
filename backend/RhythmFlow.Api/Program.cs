using System.Security.Claims;
using System.Text;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using RhythmFlow.Api.Data;
using RhythmFlow.Api.Services;

var builder = WebApplication.CreateBuilder(args);
var cfg = builder.Configuration;
// Private, git-ignored overrides for this machine (PayFast keys, Gmail login, admin alert emails).
cfg.AddJsonFile("appsettings.Local.json", optional: true, reloadOnChange: true);

// ---- Database: SQLite for local development, PostgreSQL for deployment (same code, config switch) ----
builder.Services.AddDbContext<AppDbContext>(o =>
{
    var cs = cfg.GetConnectionString("Default") ?? "Data Source=rhythmflow.db";
    if ((cfg["Database:Provider"] ?? "Sqlite").Equals("Postgres", StringComparison.OrdinalIgnoreCase)) o.UseNpgsql(cs);
    else o.UseSqlite(cs);
});

// ---- Options ----
builder.Services.Configure<JwtOptions>(cfg.GetSection("Jwt"));
builder.Services.Configure<MediaOptions>(cfg.GetSection("Media"));
builder.Services.Configure<PayFastOptions>(cfg.GetSection("PayFast"));
builder.Services.Configure<BookingOptions>(cfg.GetSection("Booking"));
builder.Services.Configure<SmtpOptions>(cfg.GetSection("Smtp"));
builder.Services.Configure<AdminOptions>(cfg.GetSection("Admin"));

var jwt = cfg.GetSection("Jwt").Get<JwtOptions>() ?? new JwtOptions();
if (jwt.Key.Length < 32) throw new InvalidOperationException("Jwt:Key must be at least 32 characters. Set it via configuration or an environment variable, never in source control.");
if ((cfg["Media:SigningKey"] ?? "").Length < 32) throw new InvalidOperationException("Media:SigningKey must be at least 32 characters.");

// ---- Services ----
builder.Services.AddScoped<TokenService>();
builder.Services.AddScoped<EntitlementService>();
builder.Services.AddScoped<ProgressService>();
builder.Services.AddScoped<SubscriptionService>();
builder.Services.AddScoped<BookingService>();
builder.Services.AddSingleton<MediaTokenService>();
builder.Services.AddSingleton<PayFastService>();
builder.Services.AddSingleton<IPayFastApi, PayFastApiClient>();
builder.Services.AddSingleton<IEmailSender, EmailService>();
builder.Services.AddSingleton<ErrorLogService>();
builder.Services.AddScoped<NotificationService>();
builder.Services.AddScoped<PasswordResetService>();
builder.Services.AddHttpClient();
builder.Services.AddHttpClient("video", c => c.Timeout = TimeSpan.FromMinutes(30));
builder.Services.AddControllers();

// ---- Authentication / authorisation (JWT, roles) ----
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(o =>
{
    o.MapInboundClaims = false;
    o.TokenValidationParameters = new TokenValidationParameters
    {
        ValidateIssuer = true, ValidIssuer = jwt.Issuer,
        ValidateAudience = true, ValidAudience = jwt.Audience,
        ValidateIssuerSigningKey = true, IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwt.Key)),
        ValidateLifetime = true, ClockSkew = TimeSpan.FromMinutes(1),
        NameClaimType = ClaimTypes.Name, RoleClaimType = ClaimTypes.Role,
    };
    // A token stops working when the account is disabled or its password changes (security stamp no longer matches).
    o.Events = new JwtBearerEvents
    {
        OnTokenValidated = async ctx =>
        {
            var db = ctx.HttpContext.RequestServices.GetRequiredService<AppDbContext>();
            var sub = ctx.Principal?.FindFirstValue("sub");
            var stamp = ctx.Principal?.FindFirstValue("sv");
            var ok = Guid.TryParse(sub, out var id) && await db.Users.AsNoTracking()
                .AnyAsync(u => u.Id == id && u.AccountStatus == "ACTIVE" && u.SecurityStamp == stamp);
            if (!ok) ctx.Fail("Session is no longer valid.");
        },
    };
});
builder.Services.AddAuthorization();

// ---- Rate limiting on login/register to slow credential stuffing ----
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy("auth", ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = cfg.GetValue("RateLimit:AuthPerMinute", 20), Window = TimeSpan.FromMinutes(1) }));
    // Error reports come from the app and may be sent without a login, so they are limited per address.
    o.AddPolicy("telemetry", ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 30, Window = TimeSpan.FromMinutes(1) }));
});

// Behind Azure's front door the API only sees plain HTTP from the proxy. Trust the proxy's headers so links the API
// builds (video playback, PayFast return URLs) use the real https address.
builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(o =>
{
    o.ForwardedHeaders = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;
    o.KnownNetworks.Clear();
    o.KnownProxies.Clear();
});

var app = builder.Build();
app.UseForwardedHeaders();

// ---- Create the schema and demo data on start. For production use EF Core migrations instead. ----
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    await db.Database.EnsureCreatedAsync();
    await SeedData.RunAsync(db, cfg);
}

app.UseMiddleware<ErrorHandlingMiddleware>();
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();
app.MapControllers();
app.MapGet("/health", () => Results.Ok(new { status = "ok", time = DateTime.UtcNow }));

// Mark overdue subscriptions once an hour.
_ = Task.Run(async () =>
{
    while (true)
    {
        try
        {
            using var scope = app.Services.CreateScope();
            await scope.ServiceProvider.GetRequiredService<SubscriptionService>().SweepExpiredAsync();
        }
        catch (Exception ex) { app.Logger.LogError(ex, "Subscription sweep failed"); }
        await Task.Delay(TimeSpan.FromHours(1));
    }
});

app.Run();
