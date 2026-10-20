#!/usr/bin/env bash
# Sets up (or updates) everything the Rhythm & Flow API needs on AWS. Run it in AWS CloudShell. See docs/AWS-SETUP.md.
#
#   ./setup-aws.sh              create or update the AWS resources, then load the app settings
#   ./setup-aws.sh --settings   only reload the app settings into the running API
#   ./setup-aws.sh --copy-db    copy the database from Azure (needs postgresql18 client, installed automatically)
#   ./setup-aws.sh --close-db   make the database private again (after the copy)
#
# App settings are read from azure-settings.json (Azure portal -> App Service -> Environment variables ->
# Advanced edit, copied as-is) or app-settings.env (NAME=value lines).
set -euo pipefail

REGION="${REGION:-af-south-1}"
STACK="${STACK:-rhythmflow}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$HERE/rhythmflow-stack.yaml"
DB_PASSWORD_PARAM="/rhythmflow-admin/db-master-password"
MODE="${1:-full}"

if [[ -z "${SETTINGS:-}" ]]; then
  if [[ -f "$HERE/azure-settings.json" ]]; then SETTINGS="$HERE/azure-settings.json"; else SETTINGS="$HERE/app-settings.env"; fi
fi

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

output() {
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

stack_exists() {
  aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" >/dev/null 2>&1
}

wait_for_env() {
  echo "  waiting for the API server to finish updating (a few minutes)..."
  aws elasticbeanstalk wait environment-updated --region "$REGION" \
    --application-name "$(output AppName)" --environment-names "$(output EnvName)"
}

# The database master password is generated once and kept encrypted in Parameter Store - nobody has to type it.
db_password() {
  if ! aws ssm get-parameter --region "$REGION" --name "$DB_PASSWORD_PARAM" >/dev/null 2>&1; then
    local pw
    pw="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
    aws ssm put-parameter --region "$REGION" --name "$DB_PASSWORD_PARAM" --type SecureString --value "$pw" >/dev/null
  fi
  aws ssm get-parameter --region "$REGION" --name "$DB_PASSWORD_PARAM" --with-decryption --query Parameter.Value --output text
}

need_settings() {
  [[ -f "$SETTINGS" ]] || fail "Can't find $SETTINGS. See docs/AWS-SETUP.md, step 'Copy your settings from Azure'."
}

load_settings() {
  need_settings
  local account conn
  account="$(aws sts get-caller-identity --query Account --output text)"
  conn="Host=$(output DbEndpoint);Port=5432;Database=$(output DbName);Username=$(output DbAdminUser);Password=$(db_password);SSL Mode=Require"
  say "Loading app settings into Parameter Store"
  wait_for_env
  python3 "$HERE/load_settings.py" "$SETTINGS" "$REGION" "$account" "$(output EnvName)" "$(output ApiUrl)" "$conn"
  wait_for_env
}

# A first attempt that failed leaves the stack in ROLLBACK_COMPLETE/ROLLBACK_FAILED, which can't be updated - remove it.
clean_failed_stack() {
  local status
  status="$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" --query 'Stacks[0].StackStatus' --output text 2>/dev/null || true)"
  case "$status" in
    ROLLBACK_COMPLETE|ROLLBACK_FAILED|DELETE_FAILED)
      say "Removing the failed first attempt ($status) - about 10 minutes"
      if aws rds describe-db-instances --region "$REGION" --db-instance-identifier rhythmflow-db >/dev/null 2>&1; then
        aws rds wait db-instance-available --region "$REGION" --db-instance-identifier rhythmflow-db || true
        aws rds delete-db-instance --region "$REGION" --db-instance-identifier rhythmflow-db \
          --skip-final-snapshot --delete-automated-backups >/dev/null 2>&1 || true
        aws rds wait db-instance-deleted --region "$REGION" --db-instance-identifier rhythmflow-db
      fi
      aws cloudformation delete-stack --region "$REGION" --stack-name "$STACK"
      aws cloudformation wait stack-delete-complete --region "$REGION" --stack-name "$STACK" ;;
  esac
}

deploy_stack() {   # $1 = DbPublicCidr ("" for private)
  local vpc subnets
  vpc="$(aws ec2 describe-vpcs --region "$REGION" --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)"
  [[ "$vpc" == vpc-* ]] || fail "This account has no default VPC in $REGION. Create one: VPC console -> Your VPCs -> Actions -> Create default VPC."
  subnets="$(aws ec2 describe-subnets --region "$REGION" --filters Name=vpc-id,Values="$vpc" Name=default-for-az,Values=true \
    --query 'Subnets[].SubnetId' --output text | tr '\t' ',')"
  local params=("SolutionStackName=$PLATFORM" "DbPassword=$(db_password)" "DbPublicCidr=$1" "VpcId=$vpc" "SubnetIds=$subnets")
  if ! stack_exists && aws iam list-open-id-connect-providers --query "OpenIDConnectProviderList[].Arn" --output text \
       | grep -q "token.actions.githubusercontent.com"; then
    params+=("CreateGitHubOidcProvider=false")
  fi
  aws cloudformation deploy --region "$REGION" --stack-name "$STACK" --template-file "$TEMPLATE" \
    --capabilities CAPABILITY_NAMED_IAM --no-fail-on-empty-changeset --parameter-overrides "${params[@]}" \
    || fail "Stack update failed. See CloudFormation -> Stacks -> $STACK -> Events in the console for the first error."
}

current_platform() {
  if stack_exists; then
    aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
      --query "Stacks[0].Parameters[?ParameterKey=='SolutionStackName'].ParameterValue" --output text
  else
    aws elasticbeanstalk list-available-solution-stacks --region "$REGION" \
      --query "SolutionStacks[?contains(@, 'running .NET 10')]" --output text | tr '\t' '\n' | sort -V | tail -1
  fi
}

summary() {
  say "Your AWS details:"
  cat <<EOF
  API address (HTTPS):   $(output ApiUrl)
  Database endpoint:     $(output DbEndpoint)

  In GitHub (repo -> Settings -> Secrets and variables -> Actions):
    Secret    AWS_ROLE_ARN  = $(output GitHubRoleArn)
    Variable  AZURE_API_URL = $(output ApiUrl)      (the workflow keeps this name for every host)
    Variable  DEPLOY_TARGET = aws

  CloudFront can take 5-15 minutes before the HTTPS address starts answering.
EOF
}

aws sts get-caller-identity >/dev/null 2>&1 || fail "The AWS CLI isn't logged in. Run this in AWS CloudShell."
aws ec2 describe-availability-zones --region "$REGION" >/dev/null 2>&1 \
  || fail "Region $REGION isn't enabled on this account. Enable it under Account -> AWS Regions, wait a few minutes, then run this again."
PLATFORM="$(current_platform)"
[[ -n "$PLATFORM" ]] || fail "No .NET 10 Elastic Beanstalk platform found in $REGION."

case "$MODE" in
  --settings)
    stack_exists || fail "The stack doesn't exist yet. Run ./setup-aws.sh first."
    load_settings; summary ;;

  --copy-db)
    stack_exists || fail "The stack doesn't exist yet. Run ./setup-aws.sh first."
    need_settings
    if ! pg_dump --version 2>/dev/null | grep -qE ' (1[89]|[2-9][0-9])\.'; then
      say "Installing the PostgreSQL 18 client"
      # CloudShell ships postgresql16, whose files clash with 18 - swap it out.
      if rpm -q postgresql16 >/dev/null 2>&1; then sudo dnf swap -y -q postgresql16 postgresql18 >/dev/null; else sudo dnf install -y -q postgresql18 >/dev/null; fi
    fi
    MY_IP="$(curl -fsS https://checkip.amazonaws.com)"
    say "Opening the AWS database to this CloudShell ($MY_IP) for the copy"
    deploy_stack "$MY_IP/32"
    aws rds wait db-instance-available --region "$REGION" --db-instance-identifier rhythmflow-db
    sleep 20   # public DNS for the database takes a moment to resolve
    say "Copying the database from Azure to AWS"
    python3 "$HERE/copy_db.py" "$SETTINGS" "$(output DbEndpoint)" "$(output DbName)" "$(output DbAdminUser)" \
      "$(db_password)" "$HOME/rhythmflow-azure.dump"
    say "Making the AWS database private again"
    deploy_stack ""
    echo "  Done. Remember to remove the CloudShell IP ($MY_IP) from the Azure database's Networking page."
    ;;

  --close-db)
    stack_exists || fail "The stack doesn't exist yet."
    say "Making the database private again"
    deploy_stack ""
    echo "  The database now only accepts connections from the API." ;;

  full)
    need_settings
    clean_failed_stack
    PLATFORM="$(current_platform)"
    say "Creating/updating AWS resources (database, API server, HTTPS, GitHub role) - about 15-20 minutes"
    echo "  Platform: $PLATFORM"
    deploy_stack ""
    load_settings
    summary ;;

  *) fail "Unknown option $MODE. Use no option, --settings, --copy-db or --close-db." ;;
esac
