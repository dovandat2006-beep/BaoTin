import unittest
from pathlib import Path

import yaml
from openapi_spec_validator import validate_spec

ROOT = Path(__file__).resolve().parents[1]


class AnalyticsContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Tệp contract dùng cú pháp JSON với phần mở rộng .yaml; YAML parse được cả hai.
        cls.document = yaml.safe_load(
            (ROOT / "openapi/analytics-service.yaml").read_text(encoding="utf-8")
        )
        cls.schemas = cls.document["components"]["schemas"]

    def test_analytics_openapi_is_valid(self):
        validate_spec(self.document)

    def test_admin_read_paths_are_exact_and_bearer_protected(self):
        self.assertEqual(
            set(self.document["paths"]),
            {
                "/api/v1/analytics/admin/summary",
                "/api/v1/analytics/admin/queue",
                "/api/v1/analytics/admin/counts",
                "/api/v1/analytics/admin/trends",
            },
        )
        self.assertEqual(self.document["security"], [{"BearerAuth": []}])
        for path, methods in self.document["paths"].items():
            with self.subTest(path=path):
                self.assertEqual(set(methods), {"get"})
                operation = methods["get"]
                self.assertEqual(operation["security"], [{"BearerAuth": []}])
                self.assertIn("ADMIN", operation["description"])
                self.assertIn("MODERATOR", operation["description"])

    def test_summary_required_fields(self):
        required = set(self.schemas["AdminSummary"]["required"])
        self.assertEqual(
            required,
            {"pendingReview", "needMoreInfo", "resolvedToday", "highRisk", "scansToday"},
        )

    def test_queue_item_and_page_fields(self):
        queue_item = self.schemas["QueueItem"]
        self.assertEqual(
            set(queue_item["required"]),
            {
                "id",
                "targetType",
                "target",
                "category",
                "title",
                "status",
                "riskLevel",
                "priority",
                "submittedAt",
                "evidenceCount",
            },
        )
        self.assertNotIn("moderatorNote", queue_item["required"])
        self.assertEqual(
            set(queue_item["properties"]["moderatorNote"]["type"]), {"string", "null"}
        )
        self.assertEqual(
            set(self.schemas["QueuePage"]["required"]),
            {"items", "page", "size", "totalItems", "totalPages"},
        )

    def test_queue_page_parameter_bounds(self):
        parameters = {
            param["name"]: param
            for param in self.document["paths"]["/api/v1/analytics/admin/queue"]["get"]["parameters"]
        }
        page_schema = parameters["page"]["schema"]
        self.assertEqual(page_schema["minimum"], 0)
        self.assertEqual(page_schema["maximum"], 10000)

    def test_status_priority_and_risk_enums_match_service_values(self):
        self.assertEqual(
            set(self.schemas["ModerationStatus"]["enum"]),
            {"SUBMITTED", "PENDING_REVIEW", "NEED_MORE_INFO", "VERIFIED", "REJECTED"},
        )
        self.assertEqual(
            set(self.schemas["Priority"]["enum"]), {"LOW", "MEDIUM", "HIGH"}
        )
        self.assertEqual(
            set(self.schemas["RiskLevel"]["enum"]),
            {"LOW", "MEDIUM", "HIGH", "CRITICAL"},
        )

    def test_counts_require_every_group(self):
        counts = self.schemas["QueueCounts"]
        self.assertEqual(set(counts["required"]), {"byStatus", "byPriority"})
        by_status = counts["properties"]["byStatus"]
        by_priority = counts["properties"]["byPriority"]
        self.assertEqual(
            set(by_status["required"]), set(self.schemas["ModerationStatus"]["enum"])
        )
        self.assertEqual(
            set(by_priority["required"]), set(self.schemas["Priority"]["enum"])
        )
        for group in (by_status, by_priority):
            for name, schema in group["properties"].items():
                with self.subTest(group=name):
                    self.assertEqual(schema["type"], "integer")
                    self.assertEqual(schema["minimum"], 0)

    # --- Các test từ origin/main (code thành viên), giữ nguyên nội dung kiểm tra ---

    def test_admin_routes_are_role_protected_and_document_upstream_failure(self):
        paths = self.document["paths"]
        for path in ["/api/v1/analytics/admin/summary", "/api/v1/analytics/admin/queue",
                     "/api/v1/analytics/admin/counts"]:
            operation = paths[path]["get"]
            self.assertIn("ADMIN or MODERATOR required", operation["description"])
            self.assertIn("401", operation["responses"])
            self.assertIn("403", operation["responses"])
            self.assertIn("503", operation["responses"])

    def test_queue_allows_unknown_reputation_without_faking_low(self):
        queue_item = self.document["components"]["schemas"]["QueueItem"]
        self.assertIn("riskLevel", queue_item["required"])
        risk = queue_item["properties"]["riskLevel"]
        self.assertIn("null", [entry.get("type") for entry in risk["oneOf"]])
        self.assertIn("must not render null as LOW", risk["description"])

    def test_summary_defines_all_scan_and_report_kpis(self):
        summary = self.document["components"]["schemas"]["AdminSummary"]["properties"]
        self.assertEqual({"pendingReview", "needMoreInfo", "resolvedToday", "highRisk", "scansToday"},
                         set(summary))
        self.assertIn("guest scans", summary["scansToday"]["description"])
        self.assertIn("UTC", summary["resolvedToday"]["description"])

    def test_info_description_documents_phase_a_read_through_sources(self):
        description = self.document["info"]["description"]
        self.assertIn("Report-Service", description)
        self.assertIn("GET /internal/v1/analytics/moderation", description)
        self.assertIn("Threat-Service", description)
        self.assertIn("GET /api/v1/scans/admin/stats", description)
        risk = self.schemas["QueueItem"]["properties"]["riskLevel"]
        self.assertIn("null", [entry.get("type") for entry in risk["oneOf"]])
        self.assertIn("must not render null as LOW", risk["description"])

    def test_info_version_is_bumped_for_phase_2(self):
        self.assertEqual(self.document["info"]["version"], "0.2.0")

    def test_trends_parameter_and_responses(self):
        operation = self.document["paths"]["/api/v1/analytics/admin/trends"]["get"]
        self.assertIn("ADMIN or MODERATOR required", operation["description"])
        parameters = {param["name"]: param for param in operation["parameters"]}
        self.assertEqual(set(parameters), {"days"})
        days = parameters["days"]
        self.assertEqual(days["in"], "query")
        self.assertFalse(days.get("required", False))
        self.assertEqual(
            days["schema"],
            {"type": "integer", "default": 14, "minimum": 1, "maximum": 90},
        )

        responses = operation["responses"]
        self.assertEqual(set(responses), {"200", "400", "401", "403", "500"})
        self.assertNotIn("503", responses)
        self.assertEqual(
            responses["200"]["content"]["application/json"]["schema"]["$ref"],
            "#/components/schemas/AnalyticsTrends",
        )
        for status in ("200", "400", "401", "403", "500"):
            with self.subTest(status=status):
                correlation = responses[status]["headers"]["X-Correlation-Id"]
                self.assertEqual(correlation["schema"], {"type": "string", "format": "uuid"})
        for status in ("400", "401", "403", "500"):
            with self.subTest(problem_status=status):
                self.assertEqual(
                    responses[status]["content"]["application/problem+json"]["schema"]["$ref"],
                    "#/components/schemas/Problem",
                )

    def test_trends_schemas_and_strict_fields(self):
        counter_fields = {
            "totalScans",
            "textScans",
            "urlScans",
            "highRiskScans",
            "reportsCreated",
            "reportsVerified",
            "reportsRejected",
            "reportsNeedMoreInfo",
            "newThreats",
        }

        trends = self.schemas["AnalyticsTrends"]
        self.assertFalse(trends["additionalProperties"])
        self.assertEqual(
            set(trends["required"]),
            {"days", "fromDate", "toDate", "totals", "riskBreakdown", "series"},
        )
        self.assertEqual(set(trends["properties"]), set(trends["required"]))
        self.assertEqual(
            trends["properties"]["days"],
            {"type": "integer", "minimum": 1, "maximum": 90},
        )
        self.assertEqual(
            trends["properties"]["fromDate"], {"type": "string", "format": "date"}
        )
        self.assertEqual(
            trends["properties"]["toDate"], {"type": "string", "format": "date"}
        )
        self.assertEqual(
            trends["properties"]["totals"]["$ref"], "#/components/schemas/TrendTotals"
        )
        self.assertEqual(
            trends["properties"]["riskBreakdown"]["$ref"],
            "#/components/schemas/RiskBreakdown",
        )
        self.assertEqual(trends["properties"]["series"]["type"], "array")
        self.assertEqual(
            trends["properties"]["series"]["items"]["$ref"],
            "#/components/schemas/DailyTrendPoint",
        )

        totals = self.schemas["TrendTotals"]
        self.assertFalse(totals["additionalProperties"])
        self.assertEqual(set(totals["required"]), counter_fields)
        self.assertEqual(set(totals["properties"]), counter_fields)
        for field in counter_fields:
            with self.subTest(totals_field=field):
                self.assertEqual(totals["properties"][field]["type"], "integer")
                self.assertEqual(totals["properties"][field]["minimum"], 0)

        point = self.schemas["DailyTrendPoint"]
        self.assertFalse(point["additionalProperties"])
        self.assertEqual(set(point["required"]), {"date"} | counter_fields)
        self.assertEqual(set(point["properties"]), {"date"} | counter_fields)
        self.assertEqual(point["properties"]["date"]["type"], "string")
        self.assertEqual(point["properties"]["date"]["format"], "date")
        for field in counter_fields:
            with self.subTest(point_field=field):
                self.assertEqual(point["properties"][field]["type"], "integer")
                self.assertEqual(point["properties"][field]["minimum"], 0)

        risk_levels = {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
        risk_breakdown = self.schemas["RiskBreakdown"]
        self.assertFalse(risk_breakdown["additionalProperties"])
        self.assertEqual(set(risk_breakdown["required"]), risk_levels)
        self.assertEqual(set(risk_breakdown["properties"]), risk_levels)
        for level in risk_levels:
            with self.subTest(risk_level=level):
                self.assertEqual(risk_breakdown["properties"][level]["type"], "integer")
                self.assertEqual(risk_breakdown["properties"][level]["minimum"], 0)

    def test_no_legacy_sms_scans_or_active_users_in_any_analytics_schema(self):
        forbidden = {"smsScans", "sms_scans", "activeUsers", "active_users"}
        seen = set()

        def walk(node):
            if isinstance(node, dict):
                props = node.get("properties")
                if isinstance(props, dict):
                    seen.update(props.keys())
                    for value in props.values():
                        walk(value)
                req = node.get("required")
                if isinstance(req, list):
                    seen.update(req)
                for value in node.values():
                    if isinstance(value, (dict, list)):
                        walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)

        walk(self.schemas)
        self.assertEqual(seen & forbidden, set())


if __name__ == "__main__":
    unittest.main()
