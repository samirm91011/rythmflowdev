<#
  Copies the Rhythm & Flow database from Azure PostgreSQL to the new AWS (RDS) database.
  Run on your Windows PC in PowerShell. Needs the PostgreSQL 18 command-line tools
  (install "PostgreSQL 18" from https://www.postgresql.org/download/windows/ - only "Command Line Tools" is required).

  Example:
    .\copy-database.ps1 -AzureUser rfadmin -AzureDb rhythmflow -AwsHost rhythmflow-db.xxxx.af-south-1.rds.amazonaws.com

  You are asked for each password when it is needed; nothing is saved.
  WARNING: this REPLACES whatever is in the AWS database with the Azure data.
#>
param(
  [string]$AzureHost = "rythmflow-db-21.postgres.database.azure.com",
  [Parameter(Mandatory)] [string]$AzureUser,
  [Parameter(Mandatory)] [string]$AzureDb,
  [Parameter(Mandatory)] [string]$AwsHost,
  [string]$AwsUser = "rfadmin",
  [string]$AwsDb = "rhythmflow",
  [string]$DumpFile = "rhythmflow-azure.dump"
)
$ErrorActionPreference = "Stop"

function Find-PgTool($name) {
  $cmd = Get-Command $name -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $guess = Join-Path $env:ProgramFiles "PostgreSQL\18\bin\$name.exe"
  if (Test-Path $guess) { return $guess }
  throw "Can't find $name. Install the PostgreSQL 18 command-line tools first."
}
$pgDump    = Find-PgTool "pg_dump"
$pgRestore = Find-PgTool "pg_restore"
$psql      = Find-PgTool "psql"

$version = & $pgDump --version
if ($version -notmatch "\s(1[89]|[2-9]\d)\.") { throw "pg_dump must be version 18 or newer (found: $version)." }

$env:PGSSLMODE = "require"
$countSql = "select count(*) from information_schema.tables where table_schema = 'public';"

Write-Host "`n1/3  Exporting from Azure ($AzureHost / $AzureDb) - enter the AZURE database password" -ForegroundColor Cyan
& $pgDump -h $AzureHost -U $AzureUser -d $AzureDb -Fc --no-owner --no-acl -f $DumpFile -W
if ($LASTEXITCODE -ne 0) { throw "Export failed. Check the Azure server's Networking page allows your IP, and the user/database names." }
Write-Host ("     saved {0:N0} KB to {1}" -f ((Get-Item $DumpFile).Length / 1KB), $DumpFile)

Write-Host "`n2/3  Importing into AWS ($AwsHost / $AwsDb) - enter the AWS database password" -ForegroundColor Cyan
& $pgRestore -h $AwsHost -U $AwsUser -d $AwsDb --no-owner --no-acl --clean --if-exists -W $DumpFile
if ($LASTEXITCODE -ne 0) {
  Write-Warning "pg_restore reported problems (above). Messages about Azure-only extensions or roles are usually harmless; check the counts below."
}

Write-Host "`n3/3  Comparing table counts" -ForegroundColor Cyan
Write-Host "     Azure (password again):"
$azure = (& $psql -h $AzureHost -U $AzureUser -d $AzureDb -tAc $countSql -W).Trim()
Write-Host "     AWS (password again):"
$aws = (& $psql -h $AwsHost -U $AwsUser -d $AwsDb -tAc $countSql -W).Trim()
Write-Host "     Azure tables: $azure   AWS tables: $aws"
if ($azure -eq $aws) { Write-Host "`nDone - the data is on AWS. Keep $DumpFile safe or delete it; it contains your users' data." -ForegroundColor Green }
else { Write-Warning "The table counts differ. Don't switch over yet; check the messages above." }
