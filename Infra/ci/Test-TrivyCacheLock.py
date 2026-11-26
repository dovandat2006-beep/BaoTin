"""Exercise the real POSIX gates against a synthetic shared Docker daemon.

No real Docker, scanner database, credential or application process is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


LOCK = "baotin-security-trivy-cache-lock"


def fake_docker():
    args = sys.argv[2:]
    root = Path(os.environ["CACHE_LOCK_TEST_STATE"])
    scenario = os.environ.get("CACHE_LOCK_TEST_SCENARIO", "normal")
    containers = root / "containers"
    containers.mkdir(exist_ok=True)

    def locate(value):
        for folder in containers.iterdir():
            if folder.name == value or hashlib.sha256(folder.name.encode()).hexdigest() == value:
                return folder
        raise FileNotFoundError(value)

    if args[0] == "create":
        name = args[args.index("--name") + 1]
        if name == LOCK and scenario == "create-failure":
            return 24
        if name == LOCK and scenario == "released-holder" and not (root / "released-holder").exists():
            # A competing holder disappears between conflict and inspect.
            (root / "released-holder").write_text("one transient name conflict")
            return 1
        folder = containers / name
        try:
            folder.mkdir()
        except FileExistsError:
            return 1
        label = "true" if "baotin.security-cache-lock=true" in args else ""
        (folder / "config.json").write_text(json.dumps({"args": args, "label": label}))
        print(hashlib.sha256(name.encode()).hexdigest())
        return 0
    if args[0] == "inspect":
        try:
            folder = locate(args[-1])
            for _ in range(30):
                if (folder / "config.json").exists():
                    break
                time.sleep(0.01)
            print(json.loads((folder / "config.json").read_text())["label"])
            return 0
        except FileNotFoundError:
            return 1
    if args[:2] == ["volume", "create"]:
        print(args[2])
        return 0
    if args[0] == "save":
        Path(args[args.index("-o") + 1]).write_bytes(b"synthetic image archive")
        return 0
    if args[0] == "cp":
        source, target = args[1:]
        if source == "-":
            sys.stdin.buffer.read()
        elif ":" in source:
            folder = locate(source.split(":", 1)[0])
            shutil.copyfile(folder / Path(source.split(":", 1)[1]).name, target)
        return 0
    if args[0] == "start":
        folder = locate(args[-1])
        if folder.name.endswith("-leak"):
            (folder / "gitleaks.json").write_text("[]")
            return 0
        active = root / "active"
        try:
            active.mkdir()
            owned = True
        except FileExistsError:
            (root / "overlap").write_text("simultaneous shared-cache scanners")
            owned = False
        try:
            with (root / "scans").open("a") as stream:
                stream.write(folder.name + "\n")
            time.sleep(1.5 if scenario == "signal" else 0.6)
            config = json.loads((folder / "config.json").read_text())["args"]
            output = Path(config[config.index("--output") + 1]).name
            report = {"bomFormat": "CycloneDX"} if output == "sbom.cdx.json" else {
                "Results": [{"Type": "jar", "Class": "lang-pkgs", "Vulnerabilities": []},
                            {"Type": "alpine", "Class": "os-pkgs", "Vulnerabilities": []}]}
            (folder / output).write_text(json.dumps(report))
            return 1 if scenario == "scan-failure" else 0
        finally:
            if owned:
                active.rmdir()
    if args[0] == "rm":
        failed = False
        for value in args[1:]:
            if value.startswith("-"):
                continue
            try:
                folder = locate(value)
                if scenario == "cleanup-failure" and folder.name.endswith("-dep"):
                    failed = True
                else:
                    shutil.rmtree(folder)
            except FileNotFoundError:
                failed = True
        return 1 if failed else 0
    raise AssertionError("Unexpected synthetic Docker operation: " + args[0])


def shell_path(path):
    value = str(Path(path).resolve()).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ":" else value


class CacheLockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cache-lock-test-", dir=OPTIONS.temp_root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "daemon"
        self.state.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        wrapper = '#!/bin/sh\nexec "$CACHE_LOCK_TEST_PYTHON" "$CACHE_LOCK_TEST_DRIVER" --fake-docker "$@"\n'
        (self.bin / "docker").write_text(wrapper)
        (self.bin / "docker").chmod(0o755)

    def environment(self, scenario="normal", wait="2"):
        env = os.environ.copy()
        env.update(CACHE_LOCK_TEST_STATE=str(self.state), CACHE_LOCK_TEST_SCENARIO=scenario,
                   CACHE_LOCK_TEST_PYTHON=str(Path(sys.executable)).replace("\\", "/"),
                   CACHE_LOCK_TEST_DRIVER=str(Path(__file__).resolve()).replace("\\", "/"),
                   TRIVY_CACHE_LOCK_WAIT_SECONDS=wait, IMAGE_REFERENCE="fixture/image:tested")
        return env

    def gate(self, name, script, scenario="normal", wait="2", signal=False):
        repo = self.root / name
        (repo / "build/libs").mkdir(parents=True)
        (repo / "build/libs/fixture-SNAPSHOT.jar").write_bytes(b"synthetic packaged JAR")
        command = f'export PATH="{shell_path(self.bin)}:$PATH"; '
        if signal:
            command += f'sh "{shell_path(script)}" & p=$!; '
            command += f'for i in $(seq 1 100); do test -d "{shell_path(self.state)}/active" && break; sleep 0.05; done; '
            command += 'kill -TERM "$p"; wait "$p"'
        else:
            command += f'exec sh "{shell_path(script)}"'
        return subprocess.Popen([OPTIONS.bash, "--noprofile", "--norc", "-c", command],
                                cwd=repo, env=self.environment(scenario, wait),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def finish(self, process):
        try:
            output, error = process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            self.fail("Synthetic gate exceeded bounded test timeout")
        return process.returncode, output, error

    def holder(self, label):
        path = self.state / "containers" / LOCK
        path.mkdir(parents=True)
        (path / "config.json").write_text(json.dumps({"args": [], "label": label}))
        return path

    def test_parallel_jar_and_image_do_not_overlap(self):
        first = self.gate("first", OPTIONS.jar_script, wait="10")
        second = self.gate("second", OPTIONS.image_script, wait="10")
        a, b = self.finish(first), self.finish(second)
        self.assertEqual((a[0], b[0]), (0, 0), (a, b))
        self.assertFalse((self.state / "overlap").exists(), "Shared Trivy cache was used concurrently")
        self.assertFalse((self.state / "containers" / LOCK).exists())

    def test_creation_error_fails_closed(self):
        result = self.finish(self.gate("broken", OPTIONS.jar_script, "create-failure"))
        self.assertEqual(result[0], 4, result)
        self.assertFalse((self.state / "scans").exists())

    def test_released_holder_race_retries_acquisition(self):
        result = self.finish(self.gate("released", OPTIONS.jar_script, "released-holder"))
        self.assertEqual(result[0], 0, result)
        self.assertTrue((self.state / "scans").exists())
        self.assertFalse((self.state / "containers" / LOCK).exists())

    def test_foreign_container_is_preserved(self):
        holder = self.holder("false")
        result = self.finish(self.gate("foreign", OPTIONS.jar_script))
        self.assertEqual(result[0], 4, result)
        self.assertTrue(holder.exists())
        self.assertFalse((self.state / "scans").exists())

    def test_stale_holder_times_out_without_reclaim(self):
        holder = self.holder("true")
        started = time.monotonic()
        result = self.finish(self.gate("waiting", OPTIONS.jar_script))
        self.assertEqual(result[0], 4, result)
        self.assertLess(time.monotonic() - started, 8)
        self.assertTrue(holder.exists())
        self.assertFalse((self.state / "scans").exists())

    def test_invalid_wait_budget_is_rejected(self):
        result = self.finish(self.gate("invalid", OPTIONS.jar_script, wait="0"))
        self.assertEqual(result[0], 2, result)
        self.assertFalse((self.state / "scans").exists())

    def test_scanner_failure_preserves_exit_and_releases_own_lock(self):
        result = self.finish(self.gate("failed", OPTIONS.jar_script, "scan-failure"))
        self.assertEqual(result[0], 1, result)
        self.assertFalse((self.state / "containers" / LOCK).exists())

    def test_cleanup_failure_retains_lock_and_fails_gate(self):
        result = self.finish(self.gate("cleanup", OPTIONS.jar_script, "cleanup-failure"))
        self.assertEqual(result[0], 4, result)
        self.assertTrue((self.state / "containers" / LOCK).exists())

    def test_signal_cleans_own_scanners_and_lock(self):
        result = self.finish(self.gate("interrupted", OPTIONS.jar_script, "signal", signal=True))
        self.assertEqual(result[0], 143, result)
        self.assertEqual(list((self.state / "containers").iterdir()), [])


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--fake-docker":
        sys.exit(fake_docker())
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar-script", type=Path, default=Path(__file__).parent / "templates/jar-security-gates.sh")
    parser.add_argument("--image-script", type=Path, default=Path(__file__).parent / "templates/security-gates.sh")
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--bash", default=r"C:\Program Files\Git\bin\bash.exe")
    OPTIONS = parser.parse_args()
    OPTIONS.temp_root.mkdir(parents=True, exist_ok=True)
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CacheLockTests))
    if result.wasSuccessful():
        print("TRIVY_CACHE_LOCK_TESTS PASS cases=9 parallel=serialized release_race=retried failure=closed foreign=preserved timeout=bounded signal=clean cleanup_failure=held transport=synthetic")
    sys.exit(0 if result.wasSuccessful() else 1)
