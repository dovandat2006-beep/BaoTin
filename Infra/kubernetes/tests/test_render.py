from pathlib import Path
import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from catalog import CONFIGURED, SERVICES, base_resources
from render import ROOT, load_json, nginx_config, public_properties, release_resources, validate_config, validate_images, write_release
from fixtures import configuration, image_manifest


class RendererTests(unittest.TestCase):
    def setUp(self):
        self.cfg = configuration(); self.images = image_manifest()

    def test_committed_base_matches_inventory(self):
        self.assertEqual(load_json(ROOT/'base/resources.json', 1048576)['items'], base_resources())

    def test_all_eleven_real_workloads_are_digest_pinned(self):
        for profile in ('k8s', 'k3s'):
            objects = release_resources(self.cfg, self.images, profile)
            pods = [x['spec']['template']['spec'] for x in objects if x['kind'] == 'Deployment']
            self.assertEqual(len(pods), 11)
            for pod in pods:
                self.assertFalse(pod['automountServiceAccountToken'])
                self.assertNotEqual(pod['serviceAccountName'], 'default')
                self.assertTrue(pod['securityContext']['runAsNonRoot'])
                for c in pod['containers']:
                    self.assertIn('@sha256:', c['image'])
                    self.assertTrue(c['securityContext']['readOnlyRootFilesystem'])
                    self.assertFalse(c['securityContext']['allowPrivilegeEscalation'])
                    self.assertEqual(c['securityContext']['capabilities']['drop'], ['ALL'])
                    for name in ('startupProbe', 'livenessProbe', 'readinessProbe', 'resources'):
                        self.assertIn(name, c)

    def test_models_are_read_only_and_have_real_package_paths(self):
        resources = release_resources(self.cfg, self.images, 'k3s')
        model = next(x for x in resources if x['kind'] == 'Deployment' and x['metadata']['name'] == 'model-inference-service')
        volume = next(x for x in model['spec']['template']['spec']['volumes'] if x['name'] == 'models')
        self.assertEqual(volume['persistentVolumeClaim'], {'claimName': 'baotin-models', 'readOnly': True})
        properties = public_properties('model-inference-service', self.cfg)
        self.assertEqual(properties['model.text-path'], '/models/text-1.0.1')
        self.assertEqual(properties['model.url-path'], '/models/url-1.0.1')

    def test_runtime_has_no_migrator_credentials_or_schema_creation(self):
        resources = release_resources(self.cfg, self.images, 'k8s')
        maps = [x for x in resources if x['kind'] == 'ConfigMap']
        text = json.dumps(maps)
        for forbidden in ('DB_MIGRATION_PASSWORD', 'DB_MIGRATION_USER', 'OPERATOR_DB_PASSWORD', 'spring.datasource.password'):
            self.assertNotIn(forbidden, text)
        for name in ('auth-service', 'threat-service', 'report-service', 'reputation-service', 'notification-service'):
            self.assertEqual(public_properties(name, self.cfg)['spring.flyway.enabled'], 'false')

    def test_actual_gateway_property_names_override_old_loopback_origins(self):
        properties = public_properties('api-gateway', self.cfg)
        self.assertEqual(properties['gateway.auth-uri'], 'http://auth-service:8080')
        for service in ('auth', 'threat', 'report', 'notification', 'reputation', 'model-inference'):
            self.assertEqual(properties['gateway.catalog.services.'+service], 'http://'+service+'-service:8080')
        self.assertEqual(public_properties('auth-service', self.cfg)['auth.events.enabled'], 'true')

    def test_private_key_and_config_are_secret_references_only(self):
        objects = release_resources(self.cfg, self.images, 'k8s')
        self.assertFalse(any(x['kind'] == 'Secret' for x in objects))
        for obj in objects:
            if obj['kind'] != 'Deployment': continue
            pod = obj['spec']['template']['spec']
            name = obj['metadata']['name']
            volumes = [v for v in pod['volumes'] if 'secret' in v]
            self.assertEqual(len(volumes), 2 if name == 'auth-service' else int(name in CONFIGURED))
            for volume in volumes:
                self.assertEqual(volume['secret']['defaultMode'], 0o440)

    def test_only_clusterip_services_and_no_host_binding(self):
        for obj in release_resources(self.cfg, self.images, 'k8s'):
            if obj['kind'] == 'Service': self.assertEqual(obj['spec']['type'], 'ClusterIP')
            if obj['kind'] == 'Deployment':
                pod = obj['spec']['template']['spec']
                self.assertNotIn('hostNetwork', pod)
                for c in pod['containers']:
                    self.assertTrue(all('hostPort' not in port for port in c['ports']))

    def test_default_deny_and_private_flow_policies_are_present(self):
        policies = [x for x in release_resources(self.cfg, self.images, 'k8s') if x['kind'] == 'NetworkPolicy']
        deny = next(x for x in policies if x['metadata']['name'] == 'default-deny')
        self.assertEqual(deny['spec']['policyTypes'], ['Ingress', 'Egress'])
        model = next(x for x in policies if x['metadata']['name'] == 'model-inference-service-inbound')
        callers = model['spec']['ingress'][0]['from']
        self.assertEqual([x['podSelector']['matchLabels']['app.kubernetes.io/name'] for x in callers], ['threat-service'])
        self.assertFalse(any(x['metadata']['name'].startswith('model-inference-service-to-') for x in policies))

    def test_provider_egress_excludes_private_addresses_when_public(self):
        policy = next(x for x in release_resources(self.cfg, self.images, 'k8s') if x['metadata']['name'] == 'auth-service-providers')
        self.assertIn('10.0.0.0/8', policy['spec']['egress'][0]['to'][0]['ipBlock']['except'])

    def test_nginx_preserves_gateway_evidence_apk_and_private_path_boundaries(self):
        value = nginx_config(self.cfg)
        self.assertNotIn('127.0.0.11', value)
        for required in ('client_max_body_size 11m', 'client_max_body_size 64k', 'Content-Disposition attachment',
                         'application/vnd.android.package-archive', '/(internal|actuator)', 'return 404', 'CF-Connecting-IP'):
            self.assertIn(required, value)

    def test_trusted_peers_cannot_be_entire_podcidr(self):
        for field in ('gatewayPeers', 'frontendPeers', 'edgePeers'):
            self.cfg[field] = ['10.42.0.0/16']
            with self.assertRaises(ValueError): validate_config(self.cfg)
            self.cfg = configuration()

    def test_image_tags_local_ids_missing_and_placeholder_digests_are_rejected(self):
        for invalid in ('sha256:'+'a'*64, 'registry.test/baotin/auth:latest',
                        'registry.test/baotin/auth@sha256:'+'0'*64):
            data = image_manifest(); data['images']['AUTH_IMAGE'] = invalid
            with self.assertRaises(ValueError): validate_images(data)
        data = image_manifest(); del data['images']['MODEL_IMAGE']
        with self.assertRaises(ValueError): validate_images(data)

    def test_default_system_namespaces_and_missing_uid_or_context_are_rejected(self):
        for key, value in [('namespace', 'default'), ('namespace', 'kube-system'), ('namespaceUid', ''), ('context', '')]:
            self.cfg[key] = value
            with self.assertRaises(ValueError): validate_config(self.cfg)
            self.cfg = configuration()

    def test_origin_config_cannot_inject_proxy_configuration(self):
        for origin in ('http://gateway.test', 'https://user:pass@gateway.test', 'https://gateway.test/path',
                       'https://gateway.test;foo', 'https://gateway.test";return 200;', 'https://gateway.test:0'):
            self.cfg['publicOrigin'] = origin
            with self.assertRaises(ValueError): validate_config(self.cfg)

    def test_cluster_dependencies_cannot_be_host_localhost_or_allow_all(self):
        for host in ('127.0.0.1', '::1', '0.0.0.0', 'localhost'):
            self.cfg['dependencies']['postgres']['host'] = host
            with self.assertRaises(ValueError): validate_config(self.cfg)
        self.cfg = configuration();self.cfg['dependencies']['postgres']['cidrs'] = ['0.0.0.0/0']
        with self.assertRaises(ValueError): validate_config(self.cfg)

    def test_models_cannot_escape_readonly_claim(self):
        for value in ('../source', '/absolute', 'a/b', '..'):
            self.cfg['modelPaths']['text'] = value
            with self.assertRaises(ValueError): validate_config(self.cfg)

    def test_shared_secrets_and_unexpected_plaintext_password_are_rejected(self):
        self.cfg['secretRefs']['auth-service'] = self.cfg['secretRefs']['threat-service']
        with self.assertRaises(ValueError): validate_config(self.cfg)
        self.cfg = configuration();self.cfg['dependencies']['postgres']['password'] = 'x'*32
        with self.assertRaises(ValueError): validate_config(self.cfg)

    def test_real_kubectl_kustomize_both_profiles_and_no_accepted_output_overwrite(self):
        kubectl = shutil.which('kubectl')
        self.assertIsNotNone(kubectl, 'Real kubectl client is required for the renderer contract')
        import yaml
        with tempfile.TemporaryDirectory() as tmp:
            for profile in ('k8s', 'k3s'):
                out = Path(tmp)/profile
                write_release(self.cfg, self.images, profile, out)
                result = subprocess.run([kubectl, 'kustomize', str(out)], capture_output=True, text=True, check=True)
                objects = list(yaml.safe_load_all(result.stdout))
                self.assertEqual(len([x for x in objects if x['kind'] == 'Deployment']), 11)
                self.assertTrue(all(x['metadata']['namespace'] == 'baotin-test' for x in objects))
                self.assertTrue(all(x['metadata']['labels']['baotin.io/distribution'] == profile for x in objects))
                with self.assertRaises(ValueError): write_release(self.cfg, self.images, profile, out)

    def test_duplicate_json_keys_fail_instead_of_overriding_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)/'bad.json';p.write_text('{"context":"first","context":"second"}', encoding='utf-8')
            with self.assertRaises(ValueError): load_json(p)


if __name__ == '__main__':
    unittest.main()

class SearchRuntimeTests(unittest.TestCase):
    def test_projection_has_only_required_dependencies(self):
        from catalog import DATABASE_ROLES, DEPENDENCY_USERS, CALLS
        self.assertEqual(DATABASE_ROLES['search-service'], 'baotin_search')
        self.assertIn('search-service', CALLS['api-gateway'])
        self.assertIn('search-service', DEPENDENCY_USERS['postgres'])
        self.assertIn('search-service', DEPENDENCY_USERS['kafka'])
        self.assertNotIn('search-service', DEPENDENCY_USERS['redis'])
        self.assertNotIn('search-service', DEPENDENCY_USERS['minio'])
        p=public_properties('search-service',configuration())
        self.assertEqual(p['spring.flyway.enabled'],'false')
        self.assertEqual(p['search.events.enabled'],'true')
        self.assertIn('spring.kafka.bootstrap-servers',p)
