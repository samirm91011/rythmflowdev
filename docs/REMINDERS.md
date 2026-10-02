# REMINDERS – things that are temporary and must change

Samir: read this before every demo and before launch. Claude keeps this list up to date; ask "what's on the reminders list?" any time.

## A. We are using TEST details now – swap for the CLIENT's details at launch
| # | Item | Test value now | Change to | Where |
|---|---|---|---|---|
| 1 | PayFast account | Our own **sandbox** merchant | Client's **live** merchant ID/key/passphrase; `Sandbox: false` | `appsettings.Local.json` (local) / Azure App Service settings (hosted) |
| 2 | PayFast notify URL | localhost / hosted test URL | Final HTTPS API address; `ValidateWithServer: true` | same |
| 3 | Email sender | Our Gmail + App Password | Client's mailbox or sending service | `Smtp` section |
| 4 | Admin alert emails | Our email | Client's admin staff | `Admin:AlertEmails` |
| 5 | Videos | Public test clips | Client's videos | Admin tools → Lessons & videos |
| 6 | Plans/prices | R99 / R199 / R299 placeholders | Client's plans | Admin tools → Subscription plans |
| 7 | Classes, studio, capacity, cancel cut-off | Placeholders (20 spots, 2h) | Client's values | Admin tools / `Booking:CancelCutoffHours` |
| 8 | Heading font | Serif stand-in | Amaris (client's licensed files) | `ui/theme/Theme.kt` + `res/font` |
| 9 | Terms / Privacy / Help text | Placeholder text | Client's legal text | Settings screen |
| 10 | Explore articles, affirmations | Placeholder text | Client's content | `JournalExploreScreens.kt` |
| 11 | Demo accounts (alex / admin) | Seeded demo users | **Delete** them; create the client's real admin | database / seed config |
| 12 | `Jwt:Key`, `Media:SigningKey` | Generated demo secrets | New long random secrets in the host's secret store | Azure settings |
| 13 | Debug "Simulate payment" button | Debug builds only | Not present in release builds – confirm | `PlanScreens.kt` |
| 14 | Hosting account | Campus Azure subscription | Client-owned subscription (or confirm who pays) | Azure / AWS |
| 15 | App signing key | Debug key | Release keystore (keep it safe; losing it blocks updates) | Android Studio |
| 16 | Google Play Developer account | none | Client or team pays ~$25 once, only if publishing to Play Store | Play Console |

| 17 | Gmail **app password** was typed in chat on 2026-10-02 | Treat as exposed | After the presentation: revoke it (Google Account → Security → App passwords) and create a new one for the client's mailbox | `appsettings.Local.json` + Azure settings |
| 18 | GitHub repo | `samirm91011/rythmflowdev` (Samir's personal account) | Client's/organisation's repo or transfer ownership; remove any group-member access no longer needed | GitHub |
| 19 | Azure resources | Campus subscription, resource group `rg-rhythmflow` | Client-owned subscription; or delete the group after the presentation to stop charges | Azure portal |
| 20 | Seeded **placeholder** content (3 plans, 4 programmes, 8 lessons with public test clips, 6 classes) is created automatically on first start in *every* environment | Placeholders | Replace via Admin tools, then remove the sample seeding in `Data/SeedData.cs` before the client's real launch | `SeedData.cs` |
| 21 | Hosted demo users `admin@rhythmandflow.test` / `alex@rhythmandflow.test` | Test accounts | Delete; create the client's real admin only | Azure settings `Seed__Users__*` |
| 22 | `PayFast__Sandbox`, `PayFast__ValidateWithServer`, `PayFast__PublicBaseUrl` on Azure | sandbox / true / test URL | live merchant, `Sandbox=false`, final HTTPS domain | Azure settings |
| 23 | Smart App Control on Samir's laptop blocks local .NET runs | Use Azure + GitHub Actions | (Optional, permanent) turn off only if Samir decides to | Windows Security |

## B. Before every demo
- API reachable (hosted: open `/health`); database reset to demo data; PayFast sandbox login works; email sending works.
- Fresh demo customer account with no subscription (to show the paywall) + one subscribed customer + admin.
- Emulator/phone charged, internet checked, volume on, screen-sleep off.
- Known-good build frozen; do **not** change code on the day.

## C. Open decisions / waiting on someone
- **Presentation: Wednesday 7 October 2026** (confirmed).
- Azure: group member to follow `docs/AZURE-SETUP.md` and report back. (Samir)
- GitHub: repo `samirm91011/rythmflowdev` given; Claude pushes only when Samir says "go". Repo owner must also add the secrets/variables and branch rules (`docs/GITHUB-SETUP.md`). (Samir)
- Client answers: videos, pricing, classes, legal text, fonts, her PayFast account.
