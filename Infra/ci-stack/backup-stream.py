"""Bounded, byte-preserving tar/XZ backup; Docker volumes are read-only."""
import argparse
import hashlib
import lzma
import pathlib
import re
import shutil
import subprocess
import tempfile
import uuid

PATHS = {
    "jenkins-blueocean": ["var/jenkins_home", "certs/client"],
    "docker": ["var/lib/docker", "certs"],
    "sonarqube": ["opt/sonarqube/data", "opt/sonarqube/extensions", "opt/sonarqube/logs"],
    "baotin-sonar-db": ["var/lib/postgresql/data"],
}


def backup_command(docker, container, image, helper):
    # Binary tar stdout must never also be duplicated into Docker JSON logs.
    return [docker, "run", "--rm", "--name", helper, "--pull", "never",
            "--log-driver", "none", "--network", "none", "--memory", "256m",
            "--memory-swap", "256m", "--volumes-from", container + ":ro",
            "--entrypoint", "tar", image, "-cf", "-", "-C", "/", *PATHS[container]]


def compress(source, target, reserve_bytes, dictionary=256 * 1024 * 1024):
    compressor = lzma.LZMACompressor(
        format=lzma.FORMAT_XZ,
        check=lzma.CHECK_SHA256,
        filters=[{"id": lzma.FILTER_LZMA2, "preset": 0, "dict_size": dictionary}],
    )
    digest = hashlib.sha256()
    length = 0
    with target.open("xb") as output:
        while chunk := source.read(1024 * 1024):
            if shutil.disk_usage(target.parent).free < reserve_bytes:
                raise RuntimeError("Backup disk reserve reached; original volumes unchanged")
            digest.update(chunk)
            length += len(chunk)
            output.write(compressor.compress(chunk))
        output.write(compressor.flush())
    return digest.hexdigest(), length


def verify(archive, expected_hash, expected_bytes):
    digest = hashlib.sha256()
    length = 0
    with lzma.open(archive, "rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
            length += len(chunk)
    if (digest.hexdigest(), length) != (expected_hash, expected_bytes):
        raise RuntimeError("Backup decompressed bytes differ from original tar stream")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--docker", required=True)
    p.add_argument("--container", required=True, choices=PATHS)
    p.add_argument("--image", required=True)
    p.add_argument("--archive", type=pathlib.Path, required=True)
    p.add_argument("--reserve-bytes", type=int, default=1610612736)
    args = p.parse_args()
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", args.image) or args.reserve_bytes < 1024**3:
        raise SystemExit("Immutable helper image and >=1 GiB reserve required")
    archive = args.archive.absolute()
    partial = archive.with_name(archive.name + ".partial")
    if archive.exists() or partial.exists() or not archive.name.endswith(".tar.xz"):
        raise SystemExit("New regular .tar.xz backup target required; never overwrite")
    helper = "baotin-ci-backup-" + uuid.uuid4().hex
    command = backup_command(args.docker, args.container, args.image, helper)
    success = False
    try:
        with tempfile.TemporaryFile(dir=archive.parent) as diagnostics:
            with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                  stderr=diagnostics) as process:
                try:
                    digest, length = compress(process.stdout, partial, args.reserve_bytes)
                    if process.wait() != 0:
                        raise RuntimeError("Read-only volume tar command failed")
                except BaseException:
                    subprocess.run([args.docker, "rm", "-f", helper], capture_output=True, check=False)
                    process.kill()
                    process.wait()
                    raise
        verify(partial, digest, length)
        partial.rename(archive)
        success = True
        print(f"CI_VOLUME_STREAM PASS archive={archive.name} tar_bytes={length} "
              f"archive_bytes={archive.stat().st_size} decompressed_sha256=matched")
    finally:
        if not success:
            partial.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
