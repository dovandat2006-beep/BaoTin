#!/usr/bin/env python3
"""Generate local-only credentials without printing them or overwriting existing keys."""
import base64
import os
from pathlib import Path
import secrets

path = Path(__file__).resolve().parents[1] / ".env.demo"
values = {
    "POSTGRES_PASSWORD": secrets.token_hex(32),
    "THREAT_DB_PASSWORD": secrets.token_hex(32),
    "THREAT_MIGRATION_PASSWORD": secrets.token_hex(32),
    "THREAT_HASH_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
    "MODEL_SERVICE_TOKEN": secrets.token_hex(32),
    "REPORT_THREAT_SERVICE_TOKEN": secrets.token_hex(32),
    "SCAN_DEMO_PORT": "8096",
}
descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w") as output:
    output.write("# Local guest demo only; never commit or copy to production.\n")
    output.writelines(f"{name}={value}\n" for name, value in values.items())
print("Created private .env.demo; keep it with the demo database volume.")
