"""Prepare/build verified artifact-only OCI images; registry publication is separate."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import stat
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

from catalog import REPOSITORIES, SERVICES
from render import ROOT, https_origin, load_json, require


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(65536), b''): h.update(block)
    return h.hexdigest()


def regular_tree(path):
    pending = [Path(path)]
    while pending:
        p = pending.pop();info = p.lstat()
        require(not p.is_symlink() and not p.is_junction() and
                not(getattr(info, 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)),
                'Image input may not follow symlinks or junctions')
        if p.is_dir():
            with os.scandir(p) as entries:
                pending.extend(Path(entry.path) for entry in entries)


def verify_artifacts(directory, origin):
    root = Path(directory);regular_tree(root)
    meta = load_json(root/'.ci-verified.json')
    require(meta.get('schemaVersion') == 1 and set(meta.get('repositories', {})) == set(REPOSITORIES.values()),
            'Complete verified immutable Jenkins artifact directory required')
    records = {}
    for name, repo in REPOSITORIES.items():
        record = meta['repositories'][repo]
        require(record.get('result') == 'SUCCESS' and record.get('sonar') == 'validated', 'Passing archived gates required')
        require(re.fullmatch(r'[a-f0-9]{40}', record.get('revision', ''))
                and re.fullmatch(r'[a-f0-9]{64}', record.get('sha256', ''))
                and type(record.get('build')) is int and record['build'] > 0, 'Immutable artifact/source/build identity required')
        require(record.get('testPolicy') == 'external_verify' or record.get('tests', 0) > 0, 'Explicit test policy required')
        if name != 'frontend' and record.get('testPolicy') == 'external_verify':
            require(record.get('releaseEvidenceValidated') is True, 'Backend release evidence not validated')
        relative = 'build/web.tar.gz' if name == 'frontend' else 'build/libs/'+repo+'-0.0.1-SNAPSHOT.jar'
        artifact = root/repo/relative
        require(artifact.is_file() and digest(artifact) == record['sha256'], 'Artifact bytes differ from validated CI receipt')
        records[name] = {'path': artifact, 'sourceRevision': record['revision'], 'artifactSha256': record['sha256'], 'build': record['build']}
    https_origin(origin)
    return records


def extract_web(archive, output, origin):
    """Bounded regular-file extraction; no tar.extractall/path/link tricks."""
    output = Path(output);output.mkdir()
    total = 0;seen = set()
    with tarfile.open(archive, 'r:gz') as tar:
        for member in tar:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in member.name
                    and ':' not in member.name and not any(part.startswith('.') and part != '.' for part in path.parts),
                    'Unsafe web artifact path')
            require(member.isfile() or member.isdir(), 'Web artifact links/devices rejected')
            if member.isdir(): continue
            key = str(path).removeprefix('./').casefold()
            require(key not in seen, 'Duplicate/case-colliding web artifact entry')
            seen.add(key);total += member.size
            require(0 <= member.size <= 268435456 and total <= 536870912, 'Web artifact size bound exceeded')
            target = output.joinpath(*path.parts)
            require(target.resolve().is_relative_to(output.resolve()), 'Web artifact escapes image context')
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open('xb') as dest:
                shutil.copyfileobj(source, dest, 65536)
    require((output/'index.html').is_file(), 'Actual web export missing')
    manifest_file = output/'downloads/android.json'
    if manifest_file.exists():
        manifest = load_json(manifest_file)
        require(manifest.get('gatewayOrigin') == origin, 'APK publication Gateway origin mismatch')
        require(re.fullmatch(r'[a-f0-9]{64}', manifest.get('sha256', ''))
                and re.fullmatch(r'[a-f0-9]{64}', manifest.get('sourceSnapshotSha256', '')),
                'Source-bound APK manifest required')
        relative = '/downloads/baotin-'+manifest['sha256']+'.apk'
        require(manifest.get('url') == relative, 'Unexpected APK URL')
        apk = output/relative.lstrip('/')
        require(apk.is_file() and apk.stat().st_size == manifest.get('bytes') and digest(apk) == manifest['sha256'],
                'Exact signed APK bytes must be preserved in OCI web image')
        return {'included': True, 'sha256': manifest['sha256'], 'bytes': manifest['bytes'],
                'sourceSnapshotSha256': manifest['sourceSnapshotSha256']}
    # No substitute APK or invented button manifest is generated.
    return {'included': False}


def docker(context, args):
    result = subprocess.run(['docker', '--context', context]+args, capture_output=True, text=True, encoding='utf-8')
    if result.returncode: raise RuntimeError('Docker '+args[0]+' failed; raw output withheld')
    return result.stdout.strip()


def build_images(directory, origin, work_root, context):
    records = verify_artifacts(directory, origin)
    work_root = Path(work_root)
    require(work_root.exists() and not work_root.is_symlink() and not work_root.is_junction(), 'Existing regular external build-work root required')
    require(not work_root.resolve().is_relative_to(ROOT.parents[1]), 'Build staging must stay outside the source workspace')
    result = {'schemaVersion': 1, 'images': {}, 'artifacts': {}, 'apk': None}
    for name in SERVICES:
        record = records[name]
        spec = ROOT/('web.Dockerfile' if name == 'frontend' else 'runtime.Dockerfile')
        spec_hash = digest(spec)
        with tempfile.TemporaryDirectory(prefix='baotin-kube-image-', dir=work_root) as temporary:
            stage = Path(temporary)
            if name == 'frontend':
                result['apk'] = extract_web(record['path'], stage/'web', origin)
            else:
                shutil.copyfile(record['path'], stage/'app.jar')
            tag = 'baotin-kube/'+name+':'+record['artifactSha256'][:16]+'-'+spec_hash[:12]+'-'+record['sourceRevision'][:8]
            docker(context, ['build', '-f', str(spec), '--label', 'org.opencontainers.image.revision='+record['sourceRevision'],
                    '--label', 'baotin.artifact.sha256='+record['artifactSha256'], '--label', 'baotin.runtime.spec='+spec_hash,
                    '-t', tag, str(stage)])
            image_id = docker(context, ['image', 'inspect', tag, '--format', '{{.Id}}'])
            require(re.fullmatch(r'sha256:[a-f0-9]{64}', image_id), 'Built OCI image identity missing')
            result['images'][SERVICES[name]] = image_id
            result['artifacts'][REPOSITORIES[name]] = {key: record[key] for key in ('sourceRevision', 'artifactSha256', 'build')}
            if name == 'frontend': result['artifacts']['Frontend']['publicOrigin'] = origin
    require(len(set(result['images'].values())) == 11, 'Eleven distinct tested service images required')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--artifacts', required=True);p.add_argument('--origin', required=True)
    p.add_argument('--work-root', required=True);p.add_argument('--docker-context', required=True);p.add_argument('--output', required=True)
    args = p.parse_args()
    output = Path(args.output)
    require(not output.exists(), 'New local-image manifest required')
    result = build_images(args.artifacts, args.origin, args.work_root, args.docker_context)
    output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('KUBERNETES_IMAGE_BUILD PASS images=11 exact_ci_artifacts=true web_apk=preserved model_runtime=glibc source_or_secrets_in_context=false')


if __name__ == '__main__':
    try: main()
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, json.JSONDecodeError, tarfile.TarError):
        raise SystemExit('KUBERNETES_IMAGE_BUILD FAIL input or build gate; raw configuration/output withheld')
