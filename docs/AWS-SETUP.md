# Moving the API from Azure to AWS

This replaces the Azure App Service + Azure PostgreSQL setup with:

| Azure | AWS | Notes |
|---|---|---|
| App Service (.NET 10) | **Elastic Beanstalk**, .NET 10 on Amazon Linux 2023, single `t3.micro` server | No load balancer, to keep cost down |
| `*.azurewebsites.net` HTTPS address | **CloudFront** `https://xxxx.cloudfront.net` | Free HTTPS certificate; the Android app only allows HTTPS |
| Azure Database for PostgreSQL 18 | **RDS for PostgreSQL 18**, `db.t3.micro`, 20 GB | Private; only the API can reach it |
| App Service environment variables | **Parameter Store** (encrypted) → Beanstalk environment | Values never shown in the Beanstalk console |
| Application Insights | **CloudWatch Logs** | Kept 14 days |
| Publish profile secret | **GitHub OIDC role** | No AWS keys stored in GitHub |

Everything lives in the Cape Town region (`af-south-1`). The files are in `aws/`:

- `rhythmflow-stack.yaml` – the AWS resources (CloudFormation)
- `setup-aws.sh` + `load_settings.py` – creates/updates them and loads the app settings
- `copy_db.py` – copies the database from Azure to AWS (run by `setup-aws.sh --copy-db` in CloudShell)
- `app-settings.example.env` – optional NAME=value alternative to pasting Azure's settings
- `copy-database.ps1` – optional: the same database copy from a Windows PC

**Cost:** roughly USD 30–40 a month once any free credits are used up (server, database, two public IPv4 addresses, a little CloudFront). Check with the [AWS Pricing Calculator](https://calculator.aws) and set a budget alert (step 1).

---

## 1. AWS account (once)

1. Create an account at <https://aws.amazon.com>. Turn on **MFA** for the root user.
2. **Billing and Cost Management → Budgets → Create budget → Monthly cost budget**, e.g. USD 20, with an email alert.
3. Cape Town is an opt-in region: top-right account menu → **Account** → **AWS Regions** → **Cape Town (af-south-1)** → **Enable**. It can take a few minutes.
4. Switch the console's region (top right) to **Africa (Cape Town)**.

## 2. Copy your settings from Azure into CloudShell

1. In the AWS console open **CloudShell** (the `>_` icon), in the Cape Town region.
2. Get the scripts into CloudShell: **Actions → Upload file** for `rhythmflow-stack.yaml`, `setup-aws.sh`, `load_settings.py` and `copy_db.py`, then run `chmod +x setup-aws.sh`.
3. In CloudShell run `cat > azure-settings.json` (it waits for input).
4. In Azure: **rhythmflow-api-emeris → Settings → Environment variables → Advanced edit**. Click in the box, **Ctrl+A**, **Ctrl+C**, then **Cancel**.
5. Paste into CloudShell (**Ctrl+V**), press **Enter**, then **Ctrl+D** to save.

The Azure-only settings (Application Insights, `WEBSITE_*`) are skipped automatically, and the database connection string is replaced with the AWS one.
(Alternatively fill in `app-settings.example.env`, save it as `app-settings.env` and upload that instead.)

## 3. Create the AWS resources

In CloudShell:
```bash
./setup-aws.sh
```
It takes 15–20 minutes and creates the database, the API server, the HTTPS address and the GitHub deploy role.
The database password is generated for you and kept encrypted in Parameter Store (`/rhythmflow-admin/db-master-password`) – nobody has to type it.
At the end it prints the **API address** and the values for GitHub.

To change a setting later: paste a new `azure-settings.json` (or edit `app-settings.env`) and run `./setup-aws.sh --settings`.

## 4. Copy the database

Do this **before** the first deploy, so the API doesn't start on an empty database.

1. Find CloudShell's address: `curl -s https://checkip.amazonaws.com`.
2. Azure: **rythmflow-db-21 → Settings → Networking → + Add firewall rule** with that address as start and end IP → **Save**.
3. In CloudShell:
   ```bash
   ./setup-aws.sh --copy-db
   ```
   It installs the PostgreSQL 18 client, opens the AWS database to CloudShell only, copies everything from Azure (reading the Azure login from `ConnectionStrings__Default`), compares the table counts and makes the AWS database private again.
4. Remove the CloudShell firewall rule from the Azure database again.
5. Delete the settings file from CloudShell: `rm azure-settings.json`.

(`aws/copy-database.ps1` does the same copy from a Windows PC with the PostgreSQL 18 tools installed, if you prefer.)

## 5. Connect GitHub

Repo → **Settings → Secrets and variables → Actions**:

| Type | Name | Value |
|---|---|---|
| Secret | `AWS_ROLE_ARN` | `GitHubRoleArn` printed in step 3 |
| Variable | `AZURE_API_URL` | the `https://xxxx.cloudfront.net` address (the workflow uses this name for every host) |
| Variable | `DEPLOY_TARGET` | `aws` |

Once AWS works you can delete `AZURE_WEBAPP_PUBLISH_PROFILE` and `AZURE_WEBAPP_NAME`.

## 6. Deploy

Merge this branch into `main` (via `develop`, as usual), or start it by hand: **Actions → Deploy → Run workflow**.
The workflow builds and tests the API, uploads it to Elastic Beanstalk, waits for `/health` on the CloudFront address, and builds a demo APK that points at the new address.

## 7. Test

- `https://xxxx.cloudfront.net/health` returns `{"status":"ok", ...}`
- `https://xxxx.cloudfront.net/docs` opens the API documentation
- Install the APK from the workflow run's artifacts: log in with an existing account, open a lesson (video plays), book a class, run a PayFast sandbox payment (the ITN callback now goes to the CloudFront address automatically)
- Password-reset email arrives (SMTP on port 587/465 works on AWS; port 25 is blocked)

## 8. Switch off Azure

When AWS has run fine for a week or so:
1. Take one last database export from Azure if you want an archive (step 4, export only).
2. Azure portal → **Resource groups → rg-rythmflow → Delete resource group**. This removes the App Service, database and Application Insights and stops the charges.

---

## Troubleshooting

- **CloudFront returns 502/504** – the API isn't running. AWS console → **Elastic Beanstalk → rhythmflow-api-prod → Logs → Request logs → Last 100 lines**, look at `web.stdout.log`. The usual cause is a missing setting (`Jwt__Key` / `Media__SigningKey` must be 32+ characters).
- **Logs over time** – **CloudWatch → Log groups → /aws/elasticbeanstalk/rhythmflow-api-prod/...**
- **"Region not enabled"** – finish step 1.3 and wait a few minutes.
- **Stack failed** – **CloudFormation → Stacks → rhythmflow → Events**, the first red line is the cause. Fix it and run `./setup-aws.sh` again.
- **Video/PayFast links show `http://`** – check the Beanstalk environment has `ForwardedHeaders__ProtoHeaderName = CloudFront-Forwarded-Proto` (set by the stack).
- **Removing everything on AWS** – **CloudFormation → rhythmflow → Delete**. The database is kept as a final snapshot (delete it under RDS → Snapshots if you don't need it), and Parameter Store entries under `/rhythmflow/` must be deleted by hand.
