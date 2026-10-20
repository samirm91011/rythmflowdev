#!/usr/bin/env python3
"""Copies the database from Azure PostgreSQL to the new RDS database. Run from AWS CloudShell by setup-aws.sh --copy-db.

The Azure connection details come from ConnectionStrings__Default in the Azure settings file; the RDS password
comes from Parameter Store. No password is shown on screen.
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from load_settings import raw_pairs  # noqa: E402


def parse_conn(cs):
    parts = {}
    for piece in cs.split(";"):
        if "=" in piece:
            k, v = piece.split("=", 1)
            parts[k.strip().lower().replace(" ", "")] = v.strip()
    def pick(*names, default=""):
        for n in names:
            if parts.get(n):
                return parts[n]
        return default
    return {
        "host": pick("host", "server"),
        "port": pick("port", default="5432"),
        "db": pick("database", "initialcatalog"),
        "user": pick("username", "userid", "user", "uid"),
        "password": pick("password", "pwd"),
    }


def run(cmd, password, **kw):
    env = dict(os.environ, PGPASSWORD=password, PGSSLMODE="require", PGCONNECT_TIMEOUT="15")
    return subprocess.run(cmd, env=env, **kw)


def count_tables(c):
    r = run(["psql", "-h", c["host"], "-p", c["port"], "-U", c["user"], "-d", c["db"], "-tAc",
             "select count(*) from information_schema.tables where table_schema='public'"],
            c["password"], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"Couldn't connect to {c['host']}: {r.stderr.strip()}")
    return int(r.stdout.strip())


def main():
    settings_file, rds_host, rds_db, rds_user, rds_password, dump = sys.argv[1:7]
    cs = dict(raw_pairs(settings_file)).get("ConnectionStrings__Default", "")
    if not cs:
        sys.exit(f"ConnectionStrings__Default isn't in {settings_file}.")
    azure = parse_conn(cs)
    if not all(azure[k] for k in ("host", "db", "user", "password")):
        sys.exit("Couldn't read host, database, username and password from ConnectionStrings__Default.")
    aws = {"host": rds_host, "port": "5432", "db": rds_db, "user": rds_user, "password": rds_password}

    print(f"  Azure: {azure['host']} / {azure['db']}")
    print(f"  AWS:   {aws['host']} / {aws['db']}")
    before = count_tables(azure)
    print(f"  Azure has {before} tables. Exporting...")
    r = run(["pg_dump", "-h", azure["host"], "-p", azure["port"], "-U", azure["user"], "-d", azure["db"],
             "-Fc", "--no-owner", "--no-acl", "-f", dump], azure["password"])
    if r.returncode != 0:
        sys.exit("Export from Azure failed (see the message above).")
    print(f"  Exported {os.path.getsize(dump) // 1024} KB. Importing into AWS...")
    r = run(["pg_restore", "-h", aws["host"], "-U", aws["user"], "-d", aws["db"],
             "--no-owner", "--no-acl", "--clean", "--if-exists", dump], aws["password"])
    if r.returncode != 0:
        print("  pg_restore reported warnings (above). Azure-only extensions or roles are usually harmless.")
    after = count_tables(aws)
    os.remove(dump)
    print(f"  AWS now has {after} tables (Azure: {before}).")
    if after < before:
        sys.exit("Some tables are missing on AWS - don't switch over yet.")
    print("  Database copied.")


if __name__ == "__main__":
    main()
