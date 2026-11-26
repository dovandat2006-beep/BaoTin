"""Verify the reviewed private dependency composition on disposable volumes."""
import json
import csv
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    project = "baotin-domain-check-" + secrets.token_hex(4)
    compose = Path(__file__).with_name("business-compose.yml").resolve()
    with tempfile.TemporaryDirectory(prefix="baotin-minio-fixture-") as directory:
        root = Path(directory)
        if os.name == "nt":
            # Python 3.13 creates an elevated TemporaryDirectory with an ACL that
            # Docker Desktop's ordinary user token cannot read. Limit this new
            # fixture to the exact user SID, SYSTEM and Administrators.
            sid = next(csv.reader([subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True).strip()]))[1]
            subprocess.run(["icacls", str(root), "/inheritance:r", "/grant:r", "*" + sid + ":(OI)(CI)F",
                            "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F"], capture_output=True, check=True)
        (root / "minio").mkdir()
        (root / "minio/root-user").write_text("fixture-root", encoding="utf-8")
        (root / "minio/root-password").write_text("TEST-FIXTURE-ONLY-password-at-least-32", encoding="utf-8")
        env = {**os.environ, "BAOTIN_SECRETS_DIR": str(root).replace("\\", "/"),
               "KAFKA_HOST_PORT": str(free_port()), "MINIO_HOST_PORT": str(free_port())}
        base = ["docker", "compose", "-p", project, "-f", str(compose)]

        def run(*args, ok=True):
            p = subprocess.run([*base, *args], env=env, capture_output=True, text=True)
            if ok and p.returncode:
                raise AssertionError("Disposable business dependency failed: " + p.stderr[-1600:])
            return p

        try:
            run("config", "--quiet")
            run("up", "-d", "--wait", "--wait-timeout", "90")
            # Inspect exact Docker bindings rather than relying on a successful host request.
            for service in ("kafka", "minio"):
                cid = run("ps", "-q", service).stdout.strip()
                metadata = json.loads(subprocess.check_output(["docker", "inspect", cid], text=True))[0]
                assert metadata["Config"]["Labels"]["com.docker.compose.project"] == project
                assert metadata["Config"]["User"] in ("1000:1000", "1001:0")
                bindings = metadata["HostConfig"]["PortBindings"]
                assert all(binding["HostIp"] == "127.0.0.1" for values in bindings.values() for binding in values)
                assert all("PASSWORD=" not in value for value in metadata["Config"]["Env"])
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            url = "http://127.0.0.1:" + env["MINIO_HOST_PORT"] + "/minio/health/ready"
            with opener.open(url, timeout=5) as response:
                assert response.status == 200
            run("restart", "kafka", "minio")
            for _ in range(60):
                try:
                    with opener.open(url, timeout=2) as response:
                        if response.status == 200:
                            break
                except Exception:
                    time.sleep(1)
            else:
                raise AssertionError("MinIO did not become ready after restart")
            print("BUSINESS_DEPENDENCIES PASS kafka=running minio=ready loopback=true nonroot=true secret_values_not_in_inspect=true restart=PASS")
        finally:
            # This random project owns only disposable fixture volumes.
            run("down", "-v", ok=False)


if __name__ == "__main__":
    main()
