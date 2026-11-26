from pathlib import Path
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deploy import Deployment, Kubectl, KubectlError, assert_runtime_objects, compatible, preflight
from render import release_resources
from fixtures import configuration, image_manifest


class MetadataKubectl:
    """Unit transport only; not presented as live Kubernetes acceptance."""
    def __init__(self):
        self.cfg = configuration();self.calls = [];self.tls_skip = False;self.namespace_uid = self.cfg['namespaceUid']
        self.server_version = 'v1.34.1+k3s1';self.immutable = 'true'

    def run(self, args, body=None, timeout=480):
        self.calls.append((args, body))
        if args[:2] == ['get', 'secret']:
            return '00000000-0000-4000-8000-000000000009 '+self.immutable
        return ''

    def json(self, args):
        self.calls.append((args, None))
        if args[0] == 'config': return {'clusters': [{'cluster': {'server': self.cfg['apiServer'], 'insecure-skip-tls-verify': self.tls_skip}}]}
        if args[:2] == ['get', 'namespace']: return {'metadata': {'uid': self.namespace_uid, 'labels': {'baotin.io/managed': 'true','pod-security.kubernetes.io/enforce': 'restricted'}}}
        if args[0] == 'version': return {'clientVersion': {'minor': '34'}, 'serverVersion': {'minor': '34', 'gitVersion': self.server_version}}
        if args[:2] == ['get', 'pvc']: return {'metadata': {'uid': 'model-pvc-identity'}, 'status': {'phase': 'Bound'}}
        if args[:2] == ['get', 'deployments']: return {'items': []}
        raise AssertionError('Unexpected unit transport operation: '+str(args))


class DeployGuardTests(unittest.TestCase):
    def test_streamed_jenkins_artifact_is_preauthenticated_without_redirects(self):
        # Jenkins returns403 (not a401 challenge) for anonymous artifact requests.
        source=(Path(__file__).resolve().parents[1]/'Test-JenkinsImageBinding.ps1').read_text(encoding='utf-8')
        self.assertIn("DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Basic'",source)
        self.assertIn('$handler.AllowAutoRedirect=$false;$handler.UseProxy=$false',source)
        self.assertLess(source.index('DefaultRequestHeaders.Authorization'),source.index('$client.GetAsync('))

    def test_kubectl_resource_printer_accepts_list_or_adjacent_json_documents(self):
        cfg=configuration();values=[{'kind':'ConfigMap','metadata':{'name':'one'}},{'kind':'Service','metadata':{'name':'two'}}]
        for text in (json.dumps({'kind':'List','items':values}), '\n'.join(json.dumps(x,indent=2) for x in values)):
            with patch.object(Kubectl,'run',return_value=text):
                self.assertEqual(Kubectl(cfg,'external').json_input(['create'],'fixture')['items'],values)

    def test_server_dry_run_service_allocations_are_not_replayed_as_declarative_input(self):
        cfg=configuration();objects=release_resources(cfg,image_manifest(),'k3s')
        for obj in objects:obj['metadata']['namespace']=cfg['namespace']
        class DryRunTransport:
            def run(self,args,*other,**kwargs):return 'unit kustomize output'
            def json_input(self,args,body):
                result=copy.deepcopy(objects)
                if args[0]=='apply':
                    for obj in result:
                        obj['metadata']['uid']='server-only-placeholder'
                        if obj['kind']=='Service':obj['spec']['clusterIP']='10.43.0.0';obj['spec']['clusterIPs']=['10.43.0.0']
                return {'kind':'List','items':result}
        with tempfile.TemporaryDirectory() as temp:
            result=Deployment(DryRunTransport(),cfg,image_manifest(),'k3s',temp).rendered(cfg)
        for obj in result:
            self.assertNotIn('uid',obj['metadata'])
            if obj['kind']=='Service':self.assertNotIn('clusterIP',obj['spec']);self.assertNotIn('clusterIPs',obj['spec'])

    def test_every_api_command_has_explicit_kubeconfig_context_and_namespace(self):
        captured = []
        def run(args, **kwargs):
            captured.append(args);return subprocess.CompletedProcess(args, 0, '{}', '')
        cfg = configuration()
        with patch('subprocess.run', side_effect=run):
            Kubectl(cfg, 'external-kubeconfig').run(['get', 'pods'])
        args = captured[0]
        self.assertEqual(args[args.index('--context')+1], cfg['context'])
        self.assertEqual(args[args.index('--namespace')+1], cfg['namespace'])
        self.assertEqual(args[args.index('--kubeconfig')+1], 'external-kubeconfig')
        self.assertNotIn('use-context', args);self.assertNotIn('--insecure-skip-tls-verify', args)

    def test_subprocess_credentials_and_raw_error_text_are_not_logged(self):
        with patch('subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', 'sensitive-fixture-output')):
            with self.assertRaisesRegex(KubectlError, '^kubectl get failed; raw output withheld$'):
                Kubectl(configuration(), 'external').run(['get', 'pods'])

    def test_preflight_checks_real_metadata_without_reading_secret_values(self):
        kube = MetadataKubectl();proof = preflight(kube, kube.cfg, 'k3s')
        self.assertEqual(proof['modelClaim'], 'model-pvc-identity')
        for args, _ in kube.calls:
            self.assertNotIn('--raw', args)
            if args[:2] == ['get', 'secret']:
                self.assertFalse(any('.data' in value for value in args))

    def test_preflight_refuses_wrong_namespace_tls_skip_and_wrong_k3s_server(self):
        for field, value in [('namespace_uid','wrong'), ('tls_skip',True), ('server_version','v1.34.1'), ('immutable','false')]:
            kube=MetadataKubectl();setattr(kube,field,value)
            with self.assertRaises(ValueError):preflight(kube,kube.cfg,'k3s')
            self.assertFalse(any(args[0] in ('apply','create','patch','delete') for args,_ in kube.calls))

    def test_database_compatibility_is_checked_not_migrated(self):
        caps={'readerVersion':2,'minimumSchema':10,'cipherReaders':['BK1']}
        db={'readerFloor':2,'schemaRevision':10,'cipherFloor':'BK1'}
        compatible(caps,db)
        for field,value in [('readerFloor',3),('schemaRevision',9),('cipherFloor','unknown')]:
            invalid=dict(db,**{field:value})
            with self.assertRaises(ValueError):compatible(caps,invalid)

    def test_auth_operator_decimal_strings_match_numeric_capabilities_without_mutation(self):
        caps={'readerVersion':2,'minimumSchema':10,'cipherReaders':['BK1']}
        # Literal RuntimeDatabaseState CLI contract (DB metadata columns are text).
        db=json.loads('{"schemaRevision":"10","readerFloor":"2","cipherFloor":"BK1"}')
        original=copy.deepcopy(db);compatible(caps,db);self.assertEqual(db,original)
        for field,value in [('readerFloor','3'),('schemaRevision','9'),('cipherFloor','unknown')]:
            with self.assertRaises(ValueError):compatible(caps,dict(db,**{field:value}))
        for invalid in (True,False,0,-1,1000000000,1.0,None,'0','01','-1','+1',' 2','2 ','2.0','1e2','1000000000','\u0662'):
            for field in ('readerFloor','schemaRevision'):
                with self.subTest(field=field,value=invalid):
                    with self.assertRaises(ValueError):compatible(caps,dict(db,**{field:invalid}))
            for field in ('readerVersion','minimumSchema'):
                with self.subTest(field=field,value=invalid):
                    with self.assertRaises(ValueError):compatible(dict(caps,**{field:invalid}),db)

    def test_post_admission_unsafe_workload_or_cross_namespace_is_rejected(self):
        cfg=configuration();objects=release_resources(cfg,image_manifest(),'k8s')
        for obj in objects:obj['metadata']['namespace']=cfg['namespace']
        assert_runtime_objects(objects,cfg)
        wrong=copy.deepcopy(objects);wrong[0]['metadata']['namespace']='other-project'
        with self.assertRaises(ValueError):assert_runtime_objects(wrong,cfg)
        wrong=copy.deepcopy(objects);pod=next(x['spec']['template']['spec'] for x in wrong if x['kind']=='Deployment');pod['automountServiceAccountToken']=True
        with self.assertRaises(ValueError):assert_runtime_objects(wrong,cfg)

    def test_model_smoke_and_guest_smoke_use_actual_api_commands_without_retries(self):
        with tempfile.TemporaryDirectory() as tmp:
            deploy=Deployment(MetadataKubectl(),configuration(),image_manifest(),'k3s',tmp)
            outputs=['{"status":"UP"}', json.dumps({'aiAvailable':True,'degraded':False,'urls':[{'reputationScore':25}]})+'\n'+json.dumps({'aiAvailable':True,'degraded':False,'urls':[{'reputationScore':25}]})]
            calls=[]
            def probe(*args,**kwargs):calls.append(args);return outputs.pop(0)
            deploy.probe=probe
            proof=deploy.smoke(release_resources(configuration(),image_manifest(),'k3s'))
            self.assertFalse(proof['actualApiMocking'])
            self.assertIn('curl --retry 0',calls[0][4][0])
            self.assertIn('for attempt in 1 2 3 4 5',calls[0][4][0])
            self.assertIn('http://api-gateway:8080/actuator/health/readiness',calls[1][4][0])
            self.assertEqual(calls[1][4][0].count('--data-binary'),2)
            self.assertNotIn('--retry 1',calls[1][4][0])
            self.assertIn('/api/v1/scans/url',calls[1][4][0])
            self.assertIn('exit 42',calls[1][4][0])
            self.assertIn('[ "$denied" != 000 ]',calls[1][4][0])
            self.assertIn('[ "$rc" -ne 7 ] && [ "$rc" -ne 28 ]',calls[1][4][0])
            self.assertNotIn("inference/text)\" = 404",calls[1][4][0])
            self.assertEqual(proof['gatewayInternal'],401)

    def test_success_persists_accepted_state_and_failure_restores_previous(self):
        with tempfile.TemporaryDirectory() as tmp:
            kube=MetadataKubectl();d=Deployment(kube,configuration(),image_manifest(),'k3s',tmp)
            def success(cfg,previous):return {'actualApiMocking':False},cfg
            d.rollout=success;record=d.execute();self.assertTrue((Path(tmp)/'accepted.json').is_file())
            d=Deployment(kube,configuration(),image_manifest(),'k3s',tmp);calls=[]
            def fail_then_restore(cfg,previous):
                calls.append(cfg)
                if len(calls)==1:raise KubectlError('controlled unit rollout failure')
                cfg['gatewayPeers']=['10.42.0.99/32'];return {'actualApiMocking':False},cfg
            d.rollout=fail_then_restore
            output=StringIO()
            with redirect_stdout(output):
                with self.assertRaisesRegex(KubectlError,'previous compatible application release restored'):d.execute()
            self.assertIn('KUBERNETES_ROLLBACK PASS',output.getvalue())
            self.assertEqual(len(calls),2)
            restored=json.loads((Path(tmp)/'accepted.json').read_text(encoding='utf-8'))
            self.assertEqual(restored['config']['gatewayPeers'],['10.42.0.99/32'])
            self.assertFalse(any(args[0]=='delete' for args,_ in kube.calls))

    def test_initial_failure_has_no_fake_rollback_success_or_data_deletion(self):
        with tempfile.TemporaryDirectory() as tmp:
            kube=MetadataKubectl();d=Deployment(kube,configuration(),image_manifest(),'k3s',tmp)
            def fail(cfg,previous):raise KubectlError('controlled initial failure')
            d.rollout=fail
            with self.assertRaisesRegex(KubectlError,'no|only owned candidate'):d.execute()
            self.assertFalse((Path(tmp)/'accepted.json').exists())
            self.assertFalse(any(args[0]=='delete' for args,_ in kube.calls))


if __name__=='__main__':unittest.main()
