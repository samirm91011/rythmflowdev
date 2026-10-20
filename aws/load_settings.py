#!/usr/bin/env python3
"""Copies the API's settings from app-settings.env into AWS Systems Manager Parameter Store (encrypted)
and tells Elastic Beanstalk to hand each one to the API as an environment variable.

Called by setup-aws.sh. Values never appear on screen or in the Beanstalk console, only their names.
"""
import json
import re
import subprocess
import sys
import tempfile

# Azure-only settings that mean nothing on AWS.
SKIP_PREFIXES = ("APPLICATIONINSIGHTS_", "ApplicationInsightsAgent_", "XDT_", "WEBSITE_", "SCM_")
# Set by the template or by this script, so a value in the file would be ignored or conflict.
MANAGED = {"Database__Provider", "ConnectionStrings__Default", "PayFast__PublicBaseUrl",
           "ASPNETCORE_ENVIRONMENT", "ASPNETCORE_URLS", "PORT"}
KEY_RE = re.compile(r"^[A-Za-z0-9_]+$")
PREFIX = "/rhythmflow/"


def aws(*args, input_json=None):
    cmd = ["aws", *args]
    if input_json is not None:
        cmd += ["--cli-input-json", json.dumps(input_json)]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        # Never echo the command: it may contain a secret value.
        sys.exit(f"AWS call failed ({args[0]} {args[1]}): {result.stderr.strip()}")
    return result.stdout


def raw_pairs(path):
    """Yields (name, value) from either Azure's 'Advanced edit' JSON or a NAME=value .env file."""
    with open(path, encoding="utf-8-sig") as f:
        text = f.read()
    if text.lstrip().startswith("["):
        try:
            items = json.loads(text)
        except json.JSONDecodeError as e:
            sys.exit(f"{path} isn't valid JSON (line {e.lineno}). Copy the whole Advanced edit box again, including [ and ].")
        for item in items:
            yield str(item.get("name", "")).strip(), str(item.get("value", "") if item.get("value") is not None else "")
        return
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            sys.exit(f"{path} line {n}: expected NAME=value")
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        yield key, value


def read_settings(path):
    settings, skipped = {}, []
    for key, value in raw_pairs(path):
        if not KEY_RE.match(key):
            sys.exit(f"{path}: '{key}' is not a valid setting name (letters, digits, _ only)")
        if key.startswith(SKIP_PREFIXES):
            skipped.append(f"{key} (Azure only)")
            continue
        if key in MANAGED:
            skipped.append(f"{key} (set automatically)")
            continue
        if value == "":
            skipped.append(f"{key} (empty)")
            continue
        settings[key] = value
    return settings, skipped


def main():
    if len(sys.argv) != 7:
        sys.exit("usage: load_settings.py SETTINGS_FILE REGION ACCOUNT_ID ENV_NAME API_URL CONNECTION_STRING")
    path, region, account, env_name, api_url, conn = sys.argv[1:]

    settings, skipped = read_settings(path)
    for required in ("Jwt__Key", "Media__SigningKey"):
        if required not in settings:
            sys.exit(f"{required} is missing from {path}. The API will not start without it.")

    settings["ConnectionStrings__Default"] = conn
    settings["PayFast__PublicBaseUrl"] = api_url

    options = []
    for key, value in sorted(settings.items()):
        name = PREFIX + key
        tier = "Standard" if len(value.encode()) <= 4096 else "Advanced"
        if tier == "Advanced":
            print(f"  note: {key} is over 4 KB, stored as an Advanced parameter (small monthly charge)")
        aws("ssm", "put-parameter", "--region", region, input_json={
            "Name": name, "Value": value, "Type": "SecureString", "Overwrite": True, "Tier": tier,
        })
        options.append({
            "Namespace": "aws:elasticbeanstalk:application:environmentsecrets",
            "OptionName": key,
            "Value": f"arn:aws:ssm:{region}:{account}:parameter{name}",
        })
        print(f"  stored {key}")

    if skipped:
        print("  skipped: " + ", ".join(skipped))

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as tmp:
        json.dump(options, tmp)
    aws("elasticbeanstalk", "update-environment", "--region", region,
        "--environment-name", env_name, "--option-settings", f"file://{tmp.name}")
    print(f"  Beanstalk now reads {len(options)} settings from Parameter Store")


if __name__ == "__main__":
    main()
