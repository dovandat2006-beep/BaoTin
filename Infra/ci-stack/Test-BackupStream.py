"""Synthetic equality, metadata, corruption and capacity checks; no Docker."""
import io
import lzma
import pathlib
import random
import tarfile
import tempfile
from unittest.mock import patch
from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("backup_stream", pathlib.Path(__file__).with_name("backup-stream.py"))
backup = module_from_spec(spec)
spec.loader.exec_module(backup)
command = backup.backup_command("docker", "jenkins-blueocean", "sha256:" + "a" * 64, "fixture")
assert command[command.index("--log-driver") + 1] == "none"
assert command[command.index("--volumes-from") + 1] == "jenkins-blueocean:ro"
assert command[command.index("--memory-swap") + 1] == "256m"
with tempfile.TemporaryDirectory(prefix="baotin-backup-proof-") as tmp:
    p = pathlib.Path(tmp) / "fixture.tar.xz"
    data = random.Random(42).randbytes(2 * 1024 * 1024)
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w") as tar:
        for name in ("history/build1.jar", "history/build2.jar"):
            header = tarfile.TarInfo(name)
            header.size = len(data); header.uid = 1000; header.gid = 1000
            header.mode = 0o600; header.mtime = 1792139287
            tar.addfile(header, io.BytesIO(data))
        link = tarfile.TarInfo("latest.jar"); link.type = tarfile.SYMTYPE
        link.linkname = "history/build2.jar"; tar.addfile(link)
    original = raw.getvalue()
    digest, size = backup.compress(io.BytesIO(original), p, 0, dictionary=8 * 1024 * 1024)
    backup.verify(p, digest, size)
    assert lzma.decompress(p.read_bytes()) == original
    assert p.stat().st_size < len(original) * 0.6
    with tarfile.open(p, "r:xz") as tar:
        assert tar.extractfile("history/build1.jar").read() == data
        assert tar.getmember("history/build1.jar").mode == 0o600
        assert tar.getmember("latest.jar").issym()
    denied = False
    try:
        backup.compress(io.BytesIO(original), p, 0, dictionary=8 * 1024 * 1024)
    except FileExistsError:
        denied = True
    assert denied
    bad = pathlib.Path(tmp) / "bad.tar.xz"
    bad.write_bytes(p.read_bytes()[:-32])
    denied = False
    try:
        backup.verify(bad, digest, size)
    except (EOFError, lzma.LZMAError):
        denied = True
    assert denied
    with patch.object(backup.shutil, "disk_usage", return_value=type("Disk", (), {"free": 0})()):
        denied = False
        try:
            backup.compress(io.BytesIO(original), pathlib.Path(tmp) / "reserve.partial", 1024**3, dictionary=8 * 1024 * 1024)
        except RuntimeError:
            denied = True
        assert denied
print("CI_BACKUP_STREAM_TEST PASS cases=6 exact_bytes=true metadata=preserved duplicates=compressed corruption=denied overwrite=denied reserve=enforced binary_logs=disabled Docker=unused")
