import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class DemoEnvironmentTests(unittest.TestCase):
    def test_generates_report_credential_and_preserves_existing_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "ops").mkdir()
            script = root / "ops/create-demo-env.py"
            shutil.copyfile(ROOT / "ops/create-demo-env.py", script)
            first = subprocess.run([sys.executable, str(script)], capture_output=True)
            self.assertEqual(first.returncode, 0)
            env = root / ".env.demo"
            original = env.read_bytes()
            values = dict(line.split("=", 1) for line in env.read_text().splitlines() if line and not line.startswith("#"))
            self.assertGreaterEqual(len(values["REPORT_THREAT_SERVICE_TOKEN"]), 32)
            self.assertNotEqual(values["REPORT_THREAT_SERVICE_TOKEN"], values["MODEL_SERVICE_TOKEN"])
            self.assertNotIn(values["REPORT_THREAT_SERVICE_TOKEN"].encode(), first.stdout)
            second = subprocess.run([sys.executable, str(script)], capture_output=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(env.read_bytes(), original)

    def test_demo_requires_report_credential_without_an_insecure_default(self):
        compose = (ROOT / "compose.demo.yml").read_text()
        self.assertIn("REPORT_THREAT_SERVICE_TOKEN: ${REPORT_THREAT_SERVICE_TOKEN:?", compose)


if __name__ == "__main__":
    unittest.main()
