"""Artifact/transport unit fixtures, not a live image or signed-APK acceptance."""
from pathlib import Path
import hashlib
import io
import json
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from catalog import REPOSITORIES, SERVICES
from images import ROOT, build_images, digest, extract_web, verify_artifacts


def fixture_archive(path, entries):
    with tarfile.open(path, 'w:gz') as tar:
        for name, data in entries:
            entry=tarfile.TarInfo(name);entry.size=len(data)
            tar.addfile(entry,io.BytesIO(data))


def artifacts(root):
    root=Path(root);root.mkdir();records={}
    apk=b'unit-test-payload-not-a-real-apk';sha=hashlib.sha256(apk).hexdigest()
    manifest={'gatewayOrigin':'https://gateway.test','sha256':sha,'bytes':len(apk),
              'sourceSnapshotSha256':hashlib.sha256(b'unit-snapshot').hexdigest(),
              'url':'/downloads/baotin-'+sha+'.apk'}
    for name,repo in REPOSITORIES.items():
        target=root/repo/'build';target.mkdir(parents=True)
        if name=='frontend':
            path=target/'web.tar.gz'
            fixture_archive(path,[('index.html',b'<html>unit fixture only</html>'),
                ('downloads/android.json',json.dumps(manifest).encode()),
                ('downloads/baotin-'+sha+'.apk',apk)])
        else:
            (target/'libs').mkdir();path=target/'libs'/(repo+'-0.0.1-SNAPSHOT.jar')
            path.write_bytes(('unit-test-fixture-only-'+name).encode())
        records[repo]={'result':'SUCCESS','sonar':'validated','revision':hashlib.sha1(repo.encode()).hexdigest(),
                       'sha256':digest(path),'build':7,'testPolicy':'external_verify','releaseEvidenceValidated':name!='frontend'}
    (root/'.ci-verified.json').write_text(json.dumps({'schemaVersion':1,'repositories':records}),encoding='utf-8')
    return manifest


class ImagePackagingTests(unittest.TestCase):
    def test_exact_ci_jar_hash_and_lineage_are_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'artifacts';artifacts(root)
            self.assertEqual(len(verify_artifacts(root,'https://gateway.test')),11)
            jar=root/'Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar';jar.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'Artifact bytes differ'):verify_artifacts(root,'https://gateway.test')

    def test_complete_successful_gate_receipt_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'artifacts';artifacts(root);meta=json.loads((root/'.ci-verified.json').read_text())
            for field,value in [('result','FAILURE'),('sonar','unknown'),('releaseEvidenceValidated',False)]:
                bad=json.loads(json.dumps(meta));bad['repositories']['Auth-Service'][field]=value
                (root/'.ci-verified.json').write_text(json.dumps(bad),encoding='utf-8')
                with self.assertRaises(ValueError):verify_artifacts(root,'https://gateway.test')

    def test_web_and_apk_bytes_are_not_rebuilt_or_substituted(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'artifacts';manifest=artifacts(root)
            result=extract_web(root/'Frontend/build/web.tar.gz',Path(temp)/'web','https://gateway.test')
            self.assertTrue(result['included']);self.assertEqual(result['sha256'],manifest['sha256'])
            self.assertEqual(digest(Path(temp)/'web'/manifest['url'].lstrip('/')),manifest['sha256'])

    def test_apk_origin_mismatch_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'artifacts';artifacts(root)
            with self.assertRaisesRegex(ValueError,'origin mismatch'):
                extract_web(root/'Frontend/build/web.tar.gz',Path(temp)/'web','https://different.test')

    def test_archive_path_traversal_case_duplicates_and_hidden_operator_files_are_rejected(self):
        for entries in [[('../secret',b'x')],[('C:/secret',b'x')],[('..\\secret',b'x')],
                        [('index.html',b'x'),('INDEX.HTML',b'y')],[('.env',b'x')]]:
            with tempfile.TemporaryDirectory() as temp:
                archive=Path(temp)/'web.tar.gz';fixture_archive(archive,entries)
                with self.assertRaises(ValueError):extract_web(archive,Path(temp)/'web','https://gateway.test')

    def test_archive_symlinks_and_hardlinks_are_rejected(self):
        for kind in [tarfile.SYMTYPE,tarfile.LNKTYPE]:
            with tempfile.TemporaryDirectory() as temp:
                archive=Path(temp)/'web.tar.gz'
                with tarfile.open(archive,'w:gz') as tar:
                    entry=tarfile.TarInfo('index.html');entry.type=kind;entry.linkname='/private';tar.addfile(entry)
                with self.assertRaisesRegex(ValueError,'links/devices'):
                    extract_web(archive,Path(temp)/'web','https://gateway.test')

    def test_packaging_context_contains_only_jar_or_verified_web(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'artifacts';artifacts(root);work=Path(temp)/'work';work.mkdir();seen=[]
            def docker(context,args):
                self.assertEqual(context,'unit-context')
                if args[0]=='build':
                    stage=Path(args[-1]);names={x.name for x in stage.iterdir()};self.assertIn(names,[{'app.jar'},{'web'}]);seen.append(names)
                    return ''
                if args[:2]==['image','inspect']:
                    return 'sha256:'+hashlib.sha256(args[2].encode()).hexdigest()
                raise AssertionError('Unexpected unit transport request')
            with patch('images.docker',side_effect=docker):result=build_images(root,'https://gateway.test',work,'unit-context')
            self.assertEqual(len(seen),11);self.assertEqual(len(result['artifacts']),11)
            self.assertTrue(result['apk']['included']);self.assertEqual(list(work.iterdir()),[])

    def test_runtime_is_glibc_openmp_nonroot_and_web_uses_verified_archive(self):
        runtime=(ROOT/'runtime.Dockerfile').read_text(encoding='utf-8');web=(ROOT/'web.Dockerfile').read_text(encoding='utf-8')
        self.assertIn('21-jre-jammy@sha256:',runtime);self.assertIn('libgomp1',runtime)
        self.assertIn('USER 1001:1001',runtime);self.assertIn('USER 101:101',web)
        self.assertNotIn('npm run',web);self.assertNotIn('COPY . .',runtime+web)

    def test_publisher_scans_before_push_and_does_not_replace_operator_docker_auth(self):
        text=(ROOT/'Build-PublishImages.ps1').read_text(encoding='utf-8')
        self.assertLess(text.index('Test-ImageSecurity.ps1'),text.index('login $registry'))
        self.assertIn('--password-stdin',text);self.assertIn("'--config',$authDir",text)
        self.assertNotIn('docker context use',text);self.assertNotIn('docker system prune',text)


if __name__=='__main__':unittest.main()
