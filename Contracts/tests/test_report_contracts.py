import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]


class ReportContracts(unittest.TestCase):
    def test_current_producer_payload_is_accepted_by_reputation_projection(self):
        import uuid
        target = str(uuid.uuid4())
        report = str(uuid.uuid4())
        envelope = {"eventId": str(uuid.uuid4()), "eventType": "report.verified", "eventVersion": 1,
                    "occurredAt": "2026-10-22T00:00:00Z", "producer": "report-service", "aggregateType": "Report",
                    "aggregateId": report, "correlationId": str(uuid.uuid4()), "causationId": None,
                    "payload": {"reportId": report, "targetId": target, "targetType": "MESSAGE_TEMPLATE",
                                "reporterUserId": str(uuid.uuid4()), "status": "VERIFIED", "version": 2}}
        for path in ["report-events-v1.schema.json", "report-reputation-events-v1.schema.json"]:
            schema = json.loads((ROOT / "events/report" / path).read_text())
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(envelope)

    def test_report_and_target_openapi_are_valid(self):
        for name in ["report-service.yaml", "threat-targets-internal.yaml"]:
            validate_spec(json.loads((ROOT / "openapi" / name).read_text()))

    def test_scan_and_authenticated_reporter_are_an_optional_pair(self):
        contract = json.loads((ROOT / "openapi/threat-targets-internal.yaml").read_text())
        validator = Draft202012Validator(contract["components"]["schemas"]["TargetRequest"], format_checker=FormatChecker())
        manual = {"type": "URL", "value": "https://example.test/"}
        validator.validate(manual)
        scan = "00000000-0000-0000-0000-000000000001"
        validator.validate({**manual, "scanId": scan, "reporterUserId": scan})
        for extra in [{"scanId": scan}, {"reporterUserId": scan}, {"scanId": "invalid", "reporterUserId": scan}]:
            with self.assertRaises(ValidationError):
                validator.validate({**manual, **extra})

    def test_optional_scan_id_keeps_manual_creation_compatible(self):
        contract = json.loads((ROOT / "openapi/report-service.yaml").read_text())
        validator = Draft202012Validator(contract["components"]["schemas"]["CreateReport"], format_checker=FormatChecker())
        manual = {"targetType": "URL", "targetValue": "https://example.test/", "category": "PHISHING_URL",
                  "title": "Suspicious website", "description": "The site requests banking credentials."}
        validator.validate(manual)
        validator.validate({**manual, "scanId": "00000000-0000-0000-0000-000000000001"})
        with self.assertRaises(ValidationError):
            validator.validate({**manual, "scanId": "invalid"})
