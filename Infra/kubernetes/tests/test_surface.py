from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DeploymentSurfaceTests(unittest.TestCase):
    def test_both_cluster_targets_preserve_existing_targets(self):
        source = (ROOT / 'Jenkinsfile').read_text(encoding='utf-8')
        choice = re.search(r"choice\(name: 'DEPLOY_TARGET', choices: \[([^]]+)\]", source)
        self.assertIsNotNone(choice)
        self.assertEqual(re.findall(r"'([^']+)'", choice.group(1)),
                         ['jar', 'docker', 'vps', 'k8s', 'k3s'])
        self.assertIn("booleanParam(name: 'DEPLOY_RELEASE', defaultValue: false", source)
        self.assertIn('beforeAgent true; expression { params.DEPLOY_RELEASE }', source)

    def test_both_real_kustomize_profiles_exist(self):
        for profile in ('k8s', 'k3s'):
            self.assertTrue((ROOT / 'kubernetes' / 'overlays' / profile / 'kustomization.yaml').is_file())


if __name__ == '__main__':
    unittest.main()
