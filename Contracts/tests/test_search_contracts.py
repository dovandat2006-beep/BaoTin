import json
import unittest
from pathlib import Path
from openapi_spec_validator import validate_spec
from jsonschema import Draft202012Validator
ROOT=Path(__file__).resolve().parents[1]
class SearchContracts(unittest.TestCase):
    def test_public_read_only_contract(self):
        d=json.loads((ROOT/'openapi/search-service.json').read_text())
        validate_spec(d)
        self.assertEqual(set(d['paths']),{'/api/v1/search'})
        self.assertEqual(set(d['paths']['/api/v1/search']),{'get'})
        self.assertEqual(d['security'],[])
        self.assertEqual(d['components']['schemas']['SearchDocument']['properties']['metadata']['maxProperties'],4)
    def test_target_event_schema_and_no_raw_payload_fields(self):
        d=json.loads((ROOT/'events/search/search-target-events-v1.schema.json').read_text())
        Draft202012Validator.check_schema(d)
        p=d['properties']['payload']
        self.assertFalse(p['additionalProperties'])
        self.assertEqual(set(p['properties']),{'targetId','targetType','displayValue'})
        validator=Draft202012Validator(p)
        validator.validate({'targetId':'00000000-0000-4000-8000-000000000001','targetType':'URL','displayValue':'example.com'})
        self.assertTrue(list(validator.iter_errors({'targetId':'x','targetType':'URL','displayValue':'https://x.com/token?secret=yes'})))

    def test_named_domain_publication_contracts(self):
        for owner in ('threat','campaign'):
            d=json.loads((ROOT/f'events/search/{owner}-search-events-v1.schema.json').read_text(encoding='utf-8'))
            Draft202012Validator.check_schema(d)
            self.assertEqual(set(d['properties']['eventType']['enum']),{f'{owner}.created',f'{owner}.updated'})
            snapshot=d['properties']['payload']['properties']['publicSearch']
            Draft202012Validator(snapshot).validate({'visibility':'PRIVATE'})
            self.assertFalse(snapshot['additionalProperties'])
            self.assertFalse(snapshot['properties']['metadata']['additionalProperties'])

    def test_withdrawal_does_not_require_repeating_removed_content(self):
        d=json.loads((ROOT/'events/search/publication-v1.schema.json').read_text(encoding='utf-8'))
        validator=Draft202012Validator(d)
        validator.validate({'visibility':'PUBLIC','documentType':'PHONE','referenceId':'00000000-0000-4000-8000-000000000001','status':'REMOVED'})
        active={'visibility':'PUBLIC','documentType':'PHONE','referenceId':'00000000-0000-4000-8000-000000000001','status':'ACTIVE','title':'Điện thoại','displayValue':'***1234','metadata':{}}
        self.assertTrue(list(validator.iter_errors(active)))

    def test_owner_snapshot_and_complete_rebuild_manifest_are_validated(self):
        owner=json.loads((ROOT/'events/search/owner-snapshot-v1.schema.json').read_text(encoding='utf-8'))
        manifest=json.loads((ROOT/'events/search/rebuild-manifest-v1.schema.json').read_text(encoding='utf-8'))
        Draft202012Validator.check_schema(owner);Draft202012Validator.check_schema(manifest)
        sources=[{'source':name,'snapshotAt':'2026-11-12T00:00:00Z','complete':True} for name in ('threat-service','reputation-service','campaign-service')]
        example={'schemaVersion':1,'runId':'00000000-0000-4000-8000-000000000001','dataFile':'/external/public-snapshots.jsonl','sha256':'a'*64,'records':0,'sources':sources}
        validator=Draft202012Validator(manifest);validator.validate(example)
        self.assertTrue(list(validator.iter_errors(dict(example,sources=sources[:2]))))
        self.assertTrue(list(validator.iter_errors(dict(example,sources=[sources[0],sources[0],sources[2]]))))

    def test_owning_export_manifest_requires_truthful_complete_source(self):
        d=json.loads((ROOT/'events/search/owner-export-v1.schema.json').read_text(encoding='utf-8'))
        Draft202012Validator.check_schema(d)
        example={'schemaVersion':1,'source':'campaign-service','snapshotAt':'2026-11-12T01:00:00Z','complete':True,'records':0,'dataFile':'/external/campaign.jsonl','sha256':'a'*64}
        validator=Draft202012Validator(d);validator.validate(example)
        self.assertTrue(list(validator.iter_errors(dict(example,complete=False))))
        self.assertTrue(list(validator.iter_errors(dict(example,source='search-service'))))
