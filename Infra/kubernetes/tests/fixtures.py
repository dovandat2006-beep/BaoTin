from catalog import CONFIGURED, SERVICES


def configuration():
    return {'schemaVersion': 1, 'namespace': 'baotin-test',
        'namespaceUid': '00000000-0000-4000-8000-000000000001', 'context': 'isolated-test',
        'apiServer': 'https://127.0.0.1:6443', 'publicOrigin': 'https://gateway.test',
        'modelClaim': 'baotin-models', 'modelPaths': {'text': 'text-1.0.1', 'url': 'url-1.0.1'},
        'imagePullSecret': 'baotin-registry', 'authKeysSecret': 'baotin-auth-keys',
        'secretRefs': {name: name+'-runtime' for name in CONFIGURED},
        'dependencies': {name: {'host': '10.20.30.'+str(i), 'port': port, 'cidrs': ['10.20.30.'+str(i)+'/32']}
                         for i, (name, port) in enumerate([('postgres', 5432), ('redis', 6379), ('kafka', 9092), ('minio', 9000)], 1)},
        'gatewayPeers': ['10.42.0.20/32'], 'frontendPeers': ['10.42.0.10/32'],
        'edgePeers': ['10.42.1.2/32'],
        'edgeSource': {'namespace': 'ingress-test', 'podLabels': {'app': 'ingress-test'}},
        'providerEgress': {'auth-service': [{'cidr': '0.0.0.0/0', 'port': 443}]}}


def image_manifest():
    import hashlib
    return {'schemaVersion': 1, 'images': {key: 'registry.test/baotin/'+name+'@sha256:'+hashlib.sha256(name.encode()).hexdigest()
                                          for name, key in SERVICES.items()}}
