"""Render operator-only namespace/RBAC/model-PVC bootstrap, never apply it implicitly."""
import argparse
import json
import re
from pathlib import Path

from render import dns_name, require


def bootstrap_resources(namespace, profile, storage_class=None, model_claim='baotin-models', capacity='8Gi'):
    dns_name(namespace);dns_name(model_claim)
    require(namespace != 'default' and not namespace.startswith('kube-'), 'Dedicated application namespace required')
    require(profile in ('k8s', 'k3s'), 'Unknown profile')
    if storage_class is not None: dns_name(storage_class)
    require(re.fullmatch(r'[1-9][0-9]{0,4}(Mi|Gi|Ti)', capacity), 'Explicit positive PVC capacity required')
    if profile == 'k3s' and storage_class is None: storage_class = 'local-path'
    common={'app.kubernetes.io/part-of':'baotin'}
    def obj(api,kind,name,**fields):
        metadata={'name':name,'labels':common.copy()}
        if kind not in ('Namespace','ClusterRole','ClusterRoleBinding'):metadata['namespace']=namespace
        return {'apiVersion':api,'kind':kind,'metadata':metadata,**fields}
    ns=obj('v1','Namespace',namespace)
    ns['metadata']['labels'].update({'baotin.io/managed':'true','pod-security.kubernetes.io/enforce':'restricted',
        'pod-security.kubernetes.io/enforce-version':'v1.33','pod-security.kubernetes.io/audit':'restricted'})
    sa=obj('v1','ServiceAccount','baotin-deployer',automountServiceAccountToken=False)
    role=obj('rbac.authorization.k8s.io/v1','Role','baotin-deploy',rules=[
        {'apiGroups':[''],'resources':['services','configmaps','serviceaccounts'],'verbs':['get','list','create','update','patch']},
        {'apiGroups':[''],'resources':['secrets','persistentvolumeclaims'],'verbs':['get']},
        {'apiGroups':[''],'resources':['pods'],'verbs':['get','list','watch']},
        {'apiGroups':[''],'resources':['pods/log'],'verbs':['get']},
        {'apiGroups':['apps'],'resources':['deployments'],'verbs':['get','list','watch','create','update','patch']},
        {'apiGroups':['networking.k8s.io'],'resources':['networkpolicies'],'verbs':['get','list','create','update','patch']},
        {'apiGroups':['batch'],'resources':['jobs'],'verbs':['get','list','watch','create','delete']},
    ])
    subject={'kind':'ServiceAccount','name':'baotin-deployer','namespace':namespace}
    binding=obj('rbac.authorization.k8s.io/v1','RoleBinding','baotin-deploy',
        roleRef={'apiGroup':'rbac.authorization.k8s.io','kind':'Role','name':'baotin-deploy'},subjects=[subject])
    identity_name=namespace+'-namespace-identity'
    identity=obj('rbac.authorization.k8s.io/v1','ClusterRole',identity_name,rules=[
        {'apiGroups':[''],'resources':['namespaces'],'resourceNames':[namespace],'verbs':['get']}])
    identity_binding=obj('rbac.authorization.k8s.io/v1','ClusterRoleBinding',identity_name,
        roleRef={'apiGroup':'rbac.authorization.k8s.io','kind':'ClusterRole','name':identity_name},subjects=[subject])
    spec={'accessModes':['ReadWriteOnce'],'volumeMode':'Filesystem','resources':{'requests':{'storage':capacity}}}
    if storage_class is not None:spec['storageClassName']=storage_class
    claim=obj('v1','PersistentVolumeClaim',model_claim,spec=spec)
    return [ns,sa,role,binding,identity,identity_binding,claim]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--namespace',required=True);p.add_argument('--profile',choices=['k8s','k3s'],required=True)
    p.add_argument('--storage-class');p.add_argument('--model-claim',default='baotin-models');p.add_argument('--capacity',default='8Gi')
    p.add_argument('--stage',choices=['namespace','resources','all'],default='all')
    args=p.parse_args()
    items=bootstrap_resources(args.namespace,args.profile,args.storage_class,args.model_claim,args.capacity)
    if args.stage=='namespace':items=[x for x in items if x['kind']=='Namespace']
    if args.stage=='resources':items=[x for x in items if x['kind']!='Namespace']
    value={'apiVersion':'v1','kind':'List','items':items}
    print(json.dumps(value,indent=2))


if __name__=='__main__':
    try:main()
    except (ValueError,TypeError):raise SystemExit('KUBERNETES_BOOTSTRAP_RENDER FAIL invalid operator input')
