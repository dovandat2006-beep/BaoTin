import json
import unittest
import uuid
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]


def envelope(event_type, producer, aggregate_type, aggregate_id, payload):
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "eventVersion": 1,
        "occurredAt": "2026-10-22T00:00:00Z",
        "producer": producer,
        "aggregateType": aggregate_type,
        "aggregateId": aggregate_id,
        "correlationId": str(uuid.uuid4()),
        "causationId": None,
        "payload": payload,
    }


class ReputationContracts(unittest.TestCase):
    def test_internal_openapi_is_valid_and_private(self):
        document = yaml.safe_load((ROOT / "openapi/reputation-service.yaml").read_text(encoding="utf-8"))
        validate_spec(document)
        self.assertTrue(document["paths"])
        self.assertTrue(all(path.startswith("/internal/") for path in document["paths"]))
        self.assertEqual(document["security"], [{"BearerAuth": []}])
        self.assertIn("BearerAuth", document["components"]["securitySchemes"])

    def test_service_key_is_read_only_and_jwt_remains_compatible(self):
        document = yaml.safe_load((ROOT / "openapi/reputation-service.yaml").read_text(encoding="utf-8"))
        read = document["paths"]["/internal/v1/reputation/targets/{threatTargetId}"]["get"]
        write = document["paths"]["/internal/v1/reputation/targets/{threatTargetId}/recalculate"]["post"]
        self.assertEqual(read["security"], [{"BearerAuth": []}, {"ThreatReadKey": []}])
        self.assertEqual(write.get("security", document["security"]), [{"BearerAuth": []}])
        scheme = document["components"]["securitySchemes"]["ThreatReadKey"]
        self.assertEqual((scheme["type"], scheme["in"], scheme["name"]),
                         ("apiKey", "header", "X-Service-Token"))

    def test_event_schemas_are_valid(self):
        names = [
            "events/report/report-reputation-events-v1.schema.json",
            "events/threat/threat-reputation-events-v1.schema.json",
            "events/campaign/campaign-reputation-events-v1.schema.json",
            "events/reputation/reputation-events-v1.schema.json",
        ]
        for name in names:
            with self.subTest(name=name):
                schema = json.loads((ROOT / name).read_text(encoding="utf-8"))
                Draft202012Validator.check_schema(schema)

    def test_consumed_event_samples_validate(self):
        target_id = str(uuid.uuid4())
        report_id = str(uuid.uuid4())
        campaign_id = str(uuid.uuid4())
        samples = {
            "events/report/report-reputation-events-v1.schema.json": envelope(
                "report.verified", "report-service", "Report", report_id,
                {"reportId": report_id, "threatTargetId": target_id, "targetType": "URL", "severity": 80}),
            "events/threat/threat-reputation-events-v1.schema.json": envelope(
                "threat.updated", "threat-service", "ThreatTarget", target_id,
                {"threatTargetId": target_id, "targetType": "URL", "ruleSignal": 80}),
            "events/campaign/campaign-reputation-events-v1.schema.json": envelope(
                "campaign.updated", "campaign-service", "Campaign", campaign_id,
                {"campaignId": campaign_id, "threatTargetId": target_id, "targetType": "URL",
                 "campaignSignal": 70}),
        }
        for name, sample in samples.items():
            with self.subTest(name=name):
                schema = json.loads((ROOT / name).read_text(encoding="utf-8"))
                Draft202012Validator(schema, format_checker=FormatChecker()).validate(sample)

    def test_produced_event_sample_validates_and_is_bounded(self):
        target_id = str(uuid.uuid4())
        name = "events/reputation/reputation-events-v1.schema.json"
        schema = json.loads((ROOT / name).read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        event = envelope(
            "reputation.score.changed", "reputation-service", "ThreatTarget", target_id,
            {"threatTargetId": target_id, "targetType": "URL", "oldScore": 0, "newScore": 41.5,
             "oldLevel": "LOW", "newLevel": "MEDIUM", "confidence": 1.0,
             "components": {"reportSignal": 60, "ruleSignal": 40, "aiSignal": 30,
                            "campaignSignal": 20},
             "calculationVersion": "v1", "changedAt": "2026-10-22T00:00:00Z", "reason": "test"})
        validator.validate(event)

        event["payload"]["newScore"] = 101
        with self.assertRaises(ValidationError):
            validator.validate(event)

        event["payload"]["newScore"] = 41.5
        event["payload"]["components"]["reportSignal"] = 101
        with self.assertRaises(ValidationError):
            validator.validate(event)

        event["payload"]["components"]["reportSignal"] = 60
        event["rawReportContent"] = "must never be published"
        with self.assertRaises(ValidationError):
            validator.validate(event)

    def test_report_severity_is_bounded(self):
        target_id = str(uuid.uuid4())
        report_id = str(uuid.uuid4())
        name = "events/report/report-reputation-events-v1.schema.json"
        schema = json.loads((ROOT / name).read_text(encoding="utf-8"))
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        event = envelope(
            "report.verified", "report-service", "Report", report_id,
            {"reportId": report_id, "threatTargetId": target_id, "targetType": "URL", "severity": 100})
        validator.validate(event)
        event["payload"]["severity"] = 101
        with self.assertRaises(ValidationError):
            validator.validate(event)


if __name__ == "__main__":
    unittest.main()
