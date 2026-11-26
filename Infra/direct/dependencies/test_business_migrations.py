"""Exercise actual operator JAR entry points with disposable PostgreSQL roles.

No live credentials, database, ports or existing containers are touched.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    args = parser.parse_args()
    name = "baotin-migration-check-" + secrets.token_hex(4)
    password = "TEST-FIXTURE-ONLY-migration-password-at-least-32"
    checks = []

    def docker(*arguments, ok=True, data=None):
        result = subprocess.run(["docker", *arguments], input=data, capture_output=True, text=True)
        if ok and result.returncode:
            raise AssertionError("Disposable migration fixture command failed: " + arguments[0])
        return result

    def sql(statement, user="postgres", ok=True):
        return docker("exec", "-i", "-e", "PGPASSWORD=" + password, name, "psql", "-h", "127.0.0.1", "-U", user,
                      "-d", "baotin", "-v", "ON_ERROR_STOP=1", "-At", ok=ok, data=statement)

    try:
        docker("run", "-d", "--name", name, "--label", "baotin.fixture=business-migrations", "-e", "POSTGRES_PASSWORD=" + password,
               "-e", "POSTGRES_DB=baotin", "-p", "127.0.0.1::5432", "postgres:17-alpine")
        for _ in range(90):
            if docker("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "baotin", ok=False).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise AssertionError("Disposable PostgreSQL TCP endpoint did not become ready")
        port = docker("port", name, "5432/tcp").stdout.strip().split(":")[-1]
        sql("CREATE SCHEMA auth; CREATE TABLE auth.private_fixture(id uuid);")
        with tempfile.TemporaryDirectory(prefix="baotin-migration-password-") as directory:
            password_file = Path(directory) / "migrator.password"
            password_file.write_text(password, encoding="utf-8")
            for service, schema in [("Report-Service", "report"), ("Notification-Service", "notification")]:
                runtime = "baotin_" + schema
                migrator = runtime + "_migrator"
                sql(f"CREATE ROLE {migrator} LOGIN PASSWORD '{password}'; CREATE ROLE {runtime} LOGIN PASSWORD '{password}'; "
                    f"CREATE SCHEMA {schema} AUTHORIZATION {migrator}; REVOKE ALL ON SCHEMA {schema} FROM PUBLIC;")
                # Published Report V1 includes CREATE SCHEMA IF NOT EXISTS. PostgreSQL
                # checks database CREATE before its existence check; never edit V1.
                if schema == "report":
                    sql(f"GRANT CREATE ON DATABASE baotin TO {migrator}")
                jar = args.workspace / service / "build/libs" / (service + "-0.0.1-SNAPSHOT.jar")
                assert jar.is_file(), service + " must be built with its wrapper first"
                environment = {**os.environ, "DB_URL": f"jdbc:postgresql://127.0.0.1:{port}/baotin", "DB_MIGRATION_USER": migrator,
                               "DB_MIGRATION_PASSWORD_FILE": str(password_file), "DB_RUNTIME_USER": runtime}
                command = [str(args.java), f"-Dloader.main=com.baotin.{schema}.operator.DatabaseMigration", "-cp", str(jar),
                           "org.springframework.boot.loader.launch.PropertiesLauncher"]
                for iteration in range(2):
                    result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=90)
                    assert result.returncode == 0, service + " operator migration failed: " + (result.stdout + result.stderr).replace(password, "[fixture]")[-2400:]
                    if iteration == 0 and schema == "report":
                        sql(f"REVOKE CREATE ON DATABASE baotin FROM {migrator}")
                    if iteration == 0:
                        if schema == "notification":
                            sql("INSERT INTO notification.preferences(user_id) VALUES ('00000000-0000-4000-8000-000000000001');", runtime)
                        else:
                            sql("INSERT INTO report.outbox_events(id,aggregate_id,event_type,payload,created_at,next_attempt_at) "
                                "VALUES ('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000002','fixture','{}',now(),now());", runtime)
                table = "notification.preferences" if schema == "notification" else "report.outbox_events"
                assert sql("SELECT count(*) FROM " + table, runtime).stdout.strip() == "1"
                assert sql(f"CREATE SCHEMA {schema}_unowned", migrator, ok=False).returncode != 0
                for forbidden in [f"CREATE TABLE {schema}.forbidden(id int)", "SELECT * FROM auth.private_fixture",
                                  f"UPDATE {schema}.flyway_schema_history SET success=false"]:
                    assert sql(forbidden, runtime, ok=False).returncode != 0, "Runtime privilege regression"
                if schema == "report":
                    assert sql("DELETE FROM report.outbox_events", runtime, ok=False).returncode != 0
                invalid = {**environment, "DB_RUNTIME_USER": migrator}
                assert subprocess.run(command, env=invalid, capture_output=True, timeout=30).returncode != 0
                checks.append({"service": service, "migration": "PASS", "repeatPreservesData": True, "runtimeCreateDenied": True,
                               "crossSchemaDenied": True, "historyWriteDenied": True, "sameMigratorRuntimeDenied": True})
        print("BUSINESS_MIGRATIONS PASS services=2 repeat_preserves_data=true runtime_create=denied cross_schema=denied history_write=denied")
        print(json.dumps(checks))
    finally:
        docker("rm", "-f", "-v", name, ok=False)


if __name__ == "__main__":
    main()
