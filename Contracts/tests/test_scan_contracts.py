import json
from pathlib import Path
import unittest
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]

class ScanContracts(unittest.TestCase):
    def test_new_openapi_documents_are_valid(self):
        for name in ['model-inference-service', 'threat-service']:
            validate_spec(json.loads((ROOT / 'openapi' / (name + '.json')).read_text()))

    def test_every_published_openapi_document_is_valid(self):
        documents = sorted((ROOT / 'openapi').glob('*.json')) + sorted((ROOT / 'openapi').glob('*.yaml'))
        self.assertTrue(documents)
        for path in documents:
            with self.subTest(path=path.name):
                text = path.read_text(encoding='utf-8')
                document = json.loads(text) if path.suffix == '.json' else yaml.safe_load(text)
                validate_spec(document)

    def test_event_schema_rejects_raw_content_and_invalid_risk(self):
        schema = json.loads((ROOT / 'events/scan/scan-events-v1.schema.json').read_text())
        Draft202012Validator.check_schema(schema)
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        uid = '00000000-0000-4000-8000-000000000001'
        event = dict(eventId=uid, eventType='scan.completed', eventVersion=1, producer='threat-service', aggregateType='Scan', aggregateId=uid,
                     occurredAt='2026-10-22T00:00:00Z', correlationId=uid, causationId=None,
                     payload=dict(scanId=uid, scanType='TEXT', targetId=None, authenticated=False, finalScore=25, riskLevel='MEDIUM', aiAvailable=False,
                                  embeddedUrlCount=0, modelName=None, modelVersion=None, modelLabel=None, modelLatencyMs=None))
        validator.validate(event)
        event['payload']['content'] = 'never include raw content'
        with self.assertRaises(ValidationError): validator.validate(event)
        del event['payload']['content']
        event['payload']['finalScore'] = 101
        with self.assertRaises(ValidationError): validator.validate(event)

    def test_history_is_authenticated_and_model_is_internal(self):
        threat = json.loads((ROOT / 'openapi/threat-service.json').read_text())
        self.assertNotIn({}, threat['security'])
        for path, methods in threat['paths'].items():
            if path.startswith('/api/v1/scans/history'):
                for method in ['get', 'delete']:
                    self.assertNotIn('security', methods[method])
        model = json.loads((ROOT / 'openapi/model-inference-service.json').read_text())
        self.assertTrue(all(path.startswith(('/internal/', '/actuator/health')) for path in model['paths']))

if __name__ == '__main__': unittest.main()
