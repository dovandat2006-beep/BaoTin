import pathlib
import unittest
import yaml

class ResourceLimits(unittest.TestCase):
    def test_ci_services_have_bounded_memory_without_changing_persistence(self):
        data = yaml.safe_load((pathlib.Path(__file__).parent / "compose.yml").read_text())
        expected = {"jenkins": ("2560m", "3g"), "docker": ("4g", "5g"), "sonar-db": ("512m", "768m"), "sonarqube": ("3g", "4g")}
        self.assertEqual(set(data["services"]), set(expected))
        for name, limits in expected.items():
            service = data["services"][name]
            self.assertEqual((service["mem_limit"], service["memswap_limit"]), limits)
            self.assertEqual(service["restart"], "unless-stopped")
            self.assertTrue(service["healthcheck"])
            self.assertEqual(service["pull_policy"], "never")
        self.assertTrue(all(v["external"] for v in data["volumes"].values()))
        self.assertFalse(data["services"]["sonar-db"].get("ports"))
        self.assertFalse(data["services"]["docker"].get("ports"))

if __name__ == "__main__":
    unittest.main()
