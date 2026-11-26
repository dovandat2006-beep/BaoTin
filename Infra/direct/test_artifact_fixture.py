import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('artifact_fixture', Path(__file__).with_name('artifact-fixture.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='baotin-artifact-fixture-')
        self.jar = Path(self.directory.name) / 'fixture.jar'
        self.jar.write_bytes(b'synthetic immutable artifact bytes')
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), fixture.Handler)
        self.server.config = {'token': 'fixture-only', 'artifacts': {'Auth-Service': str(self.jar)}}
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def get(self, path, authenticated=True):
        headers = {'Authorization': 'Basic ' + base64.b64encode(b'fixture-user:fixture-only').decode()} if authenticated else {}
        with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:' + str(self.server.server_port) + path, headers=headers), timeout=5) as response:
            return response.read()

    def test_exact_evidence_matches_ordinary_artifact(self):
        prefix = '/job/baotin/job/Auth-Service/1/artifact/'
        data = self.get(prefix + 'build/libs/Auth-Service-0.0.1-SNAPSHOT.jar')
        evidence = json.loads(self.get(prefix + 'build/release-evidence.json'))
        self.assertEqual(evidence['jarSha256'], hashlib.sha256(data).hexdigest())

    def test_invalid_archive_fixture_has_its_actual_evidence(self):
        prefix = '/job/baotin/job/Auth-Service/3/artifact/'
        data = self.get(prefix + 'build/libs/Auth-Service-0.0.1-SNAPSHOT.jar')
        self.assertEqual(data, b'invalid Java archive')
        self.assertEqual(json.loads(self.get(prefix + 'build/release-evidence.json'))['jarSha256'], hashlib.sha256(data).hexdigest())

    def test_missing_credentials_are_denied(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.get('/job/baotin/job/Auth-Service/1/artifact/build/release-evidence.json', False)
        self.assertEqual(error.exception.code, 401)

    def test_unknown_repository_is_denied(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.get('/job/baotin/job/unknown/1/artifact/build/release-evidence.json')
        self.assertEqual(error.exception.code, 404)


if __name__ == '__main__':
    unittest.main()
