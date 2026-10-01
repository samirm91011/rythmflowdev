using Microsoft.EntityFrameworkCore;
using RhythmFlow.Api.Data;
using RhythmFlow.Api.Domain;
using RhythmFlow.Api.Dtos;

namespace RhythmFlow.Api.Services;

public class SubscriptionService(AppDbContext db, PayFastService payFast)
{
    public async Task<CheckoutResponse> CheckoutAsync(Guid userId, int planId)
    {
        var plan = await db.Plans.FirstOrDefaultAsync(p => p.Id == planId && p.Status == "ACTIVE")
            ?? throw new InvalidOperationException("That plan is not available.");
        var user = await db.Users.FirstAsync(u => u.Id == userId);

        // Only one open checkout at a time per user.
        var stale = await db.Subscriptions.Where(s => s.UserId == userId && s.Status == SubscriptionStatus.Pending).ToListAsync();
        foreach (var s in stale) s.Status = SubscriptionStatus.Cancelled;

        var sub = new Subscription { UserId = userId, PlanId = plan.Id, Status = SubscriptionStatus.Pending };
        db.Subscriptions.Add(sub);
        await db.SaveChangesAsync();

        return new CheckoutResponse(sub.Id, payFast.BuildPaymentUrl(user, plan, sub));
    }

    public async Task<List<SubscriptionDto>> MineAsync(Guid userId)
    {
        var subs = await db.Subscriptions.Include(s => s.Plan)
            .Where(s => s.UserId == userId).OrderByDescending(s => s.CreatedAt).ToListAsync();
        return subs.Select(s => new SubscriptionDto(s.Id, s.PlanId, s.Plan!.Name, s.Plan.Tier, s.Plan.Price, s.Status,
            s.StartDate, s.EndDate, EntitlementService.GrantsAccess(s))).ToList();
    }

    public async Task<bool> CancelAsync(Guid userId, int subscriptionId)
    {
        var sub = await db.Subscriptions.FirstOrDefaultAsync(s => s.Id == subscriptionId && s.UserId == userId);
        if (sub is null || sub.Status != SubscriptionStatus.Active) return false;
        // Access continues until the end of the period already paid for.
        sub.Status = SubscriptionStatus.Cancelled;
        await db.SaveChangesAsync();
        return true;
    }

    /// <summary>
    /// Applies a payment result. Called from the PayFast ITN handler (BR-04: a subscription only becomes
    /// active once the payment is confirmed server-to-server) and from the dev simulator.
    /// </summary>
    public async Task<bool> ApplyPaymentResultAsync(int subscriptionId, string paymentStatus, decimal amountGross, string? pfPaymentId, string? token)
    {
        var sub = await db.Subscriptions.Include(s => s.Plan).FirstOrDefaultAsync(s => s.Id == subscriptionId);
        if (sub is null || sub.Plan is null) return false;

        // Idempotency: PayFast can retry the same notification.
        if (pfPaymentId is not null && await db.Payments.AnyAsync(p => p.TransactionReference == pfPaymentId))
            return true;

        switch (paymentStatus.ToUpperInvariant())
        {
            case "COMPLETE":
                if (amountGross != sub.Plan.Price) return false; // amount must match the plan price
                db.Payments.Add(new Payment
                {
                    SubscriptionId = sub.Id, Amount = amountGross, Status = "COMPLETE", TransactionReference = pfPaymentId,
                });
                var now = DateTime.UtcNow;
                // First payment starts the period; each recurring payment extends it by a month.
                var from = sub.EndDate is { } end && end > now && sub.Status != SubscriptionStatus.Pending ? end : now;
                sub.StartDate ??= now;
                sub.EndDate = from.AddMonths(1);
                sub.Status = SubscriptionStatus.Active;
                if (!string.IsNullOrEmpty(token)) sub.PayFastReference = token;
                break;
            case "FAILED":
                db.Payments.Add(new Payment { SubscriptionId = sub.Id, Amount = amountGross, Status = "FAILED", TransactionReference = pfPaymentId });
                sub.Status = sub.Status == SubscriptionStatus.Active ? SubscriptionStatus.PastDue : SubscriptionStatus.Failed;
                break;
            case "CANCELLED":
                if (sub.Status == SubscriptionStatus.Pending) sub.Status = SubscriptionStatus.Cancelled;
                break;
            default:
                return true; // PENDING etc.: nothing to do yet
        }
        await db.SaveChangesAsync();
        return true;
    }

    /// <summary>Marks overdue subscriptions. PAST_DUE after the period ends, EXPIRED a week later (no grace period is specified by the client yet).</summary>
    public async Task SweepExpiredAsync()
    {
        var now = DateTime.UtcNow;
        var due = await db.Subscriptions.Where(s => s.Status == SubscriptionStatus.Active && s.EndDate < now).ToListAsync();
        foreach (var s in due) s.Status = SubscriptionStatus.PastDue;
        var dead = await db.Subscriptions.Where(s => s.Status == SubscriptionStatus.PastDue && s.EndDate < now.AddDays(-7)).ToListAsync();
        foreach (var s in dead) s.Status = SubscriptionStatus.Expired;
        if (due.Count + dead.Count > 0) await db.SaveChangesAsync();
    }
}
