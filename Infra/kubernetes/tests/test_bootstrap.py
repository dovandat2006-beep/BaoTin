from pathlib import Path
import json
import subprocess
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bootstrap import bootstrap_resources


class BootstrapTests(unittest.TestCase):
    def test_new_namespace_is_rendered_as_separate_stage_before_namespaced_dry_run(self):
        script=Path(__file__).resolve().parents[1]/'bootstrap.py'
        values=[]
        for stage in ('namespace','resources'):
            output=subprocess.check_output([sys.executable,'-B',str(script),'--namespace','baotin-test','--profile','k3s','--stage',stage],text=True)
            values.append(json.loads(output)['items'])
        self.assertEqual([x['kind'] for x in values[0]],['Namespace'])
        self.assertFalse(any(x['kind']=='Namespace' for x in values[1]))
        self.assertEqual(len(values[0])+len(values[1]),7)

    def test_k3s_uses_local_path_and_k8s_uses_selected_class(self):
        for profile,storage in [('k3s','local-path'),('k8s','custom-csi')]:
            objects=bootstrap_resources('baotin-test',profile,None if profile=='k3s' else storage)
            claim=next(x for x in objects if x['kind']=='PersistentVolumeClaim')
            self.assertEqual(claim['spec']['storageClassName'],storage)
            self.assertEqual(claim['metadata']['namespace'],'baotin-test')
            self.assertEqual(claim['spec']['accessModes'],['ReadWriteOnce'])

    def test_deployer_has_no_data_namespace_or_secret_delete_permissions(self):
        objects=bootstrap_resources('baotin-test','k3s')
        role=next(x for x in objects if x['kind']=='Role')
        for rule in role['rules']:
            if any(x in rule['resources'] for x in ['secrets','persistentvolumeclaims','namespaces']):
                self.assertNotIn('delete',rule['verbs']);self.assertNotIn('create',rule['verbs'])
            if 'delete' in rule['verbs']:self.assertEqual(rule['resources'],['jobs'])
        cluster=next(x for x in objects if x['kind']=='ClusterRole')
        self.assertEqual(cluster['rules'],[{'apiGroups':[''],'resources':['namespaces'],'resourceNames':['baotin-test'],'verbs':['get']}])
        self.assertFalse(any(x['kind']=='Secret' for x in objects))

    def test_namespace_is_restricted_and_accounts_do_not_automount_tokens(self):
        objects=bootstrap_resources('baotin-test','k3s')
        namespace=next(x for x in objects if x['kind']=='Namespace')
        self.assertEqual(namespace['metadata']['labels']['pod-security.kubernetes.io/enforce'],'restricted')
        account=next(x for x in objects if x['kind']=='ServiceAccount')
        self.assertFalse(account['automountServiceAccountToken'])

    def test_reserved_namespace_and_bad_capacity_are_rejected(self):
        for value in ('default','kube-system','../other','baotin;command'):
            with self.assertRaises(ValueError):bootstrap_resources(value,'k3s')
        for capacity in ('0Gi','all','8;command'):
            with self.assertRaises(ValueError):bootstrap_resources('baotin-test','k3s',capacity=capacity)


if __name__=='__main__':unittest.main()
