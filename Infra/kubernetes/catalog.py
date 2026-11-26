"""Deployment inventory; application contracts remain owned by the service repos."""
SERVICES = {
    'api-gateway': 'GATEWAY_IMAGE', 'auth-service': 'AUTH_IMAGE',
    'threat-service': 'THREAT_IMAGE', 'report-service': 'REPORT_IMAGE',
    'reputation-service': 'REPUTATION_IMAGE', 'model-inference-service': 'MODEL_IMAGE',
    'notification-service': 'NOTIFICATION_IMAGE', 'search-service': 'SEARCH_IMAGE',
    'campaign-service': 'CAMPAIGN_IMAGE', 'analytics-service': 'ANALYTICS_IMAGE',
    'frontend': 'WEB_IMAGE',
}
REPOSITORIES = {
    'api-gateway': 'API-Gateway', 'auth-service': 'Auth-Service',
    'threat-service': 'Threat-Service', 'report-service': 'Report-Service',
    'reputation-service': 'Reputation-Service', 'model-inference-service': 'Model-Inference-Service',
    'notification-service': 'Notification-Service', 'search-service': 'Search-Service',
    'campaign-service': 'Campaign-Service', 'analytics-service': 'Analytics-Service', 'frontend': 'Frontend',
}
CONFIGURED = tuple(list(SERVICES)[:8] + ['analytics-service'])
DATABASE_ROLES = {'auth-service': 'baotin_auth', 'threat-service': 'baotin_threat',
                  'report-service': 'baotin_report', 'reputation-service': 'baotin_reputation',
                  'notification-service': 'baotin_notification', 'search-service': 'baotin_search', 'analytics-service': 'baotin_analytics'}
CALLS = {'frontend': ['api-gateway'],
         'api-gateway': ['auth-service', 'threat-service', 'report-service', 'notification-service', 'search-service', 'analytics-service'],
         'threat-service': ['auth-service', 'model-inference-service', 'reputation-service'],
         'report-service': ['auth-service', 'threat-service'],
         'analytics-service': ['auth-service'], 'reputation-service': ['auth-service'], 'notification-service': ['auth-service', 'threat-service']}
DEPENDENCY_USERS = {'postgres': list(DATABASE_ROLES), 'redis': ['api-gateway', 'reputation-service'],
                    'kafka': ['auth-service', 'threat-service', 'report-service', 'reputation-service', 'notification-service', 'search-service'],
                    'minio': ['report-service']}
ORDER = ['frontend', 'api-gateway', 'auth-service', 'model-inference-service', 'reputation-service',
         'threat-service', 'report-service', 'notification-service', 'search-service', 'campaign-service', 'analytics-service']


def labels(name=None):
    result = {'app.kubernetes.io/part-of': 'baotin'}
    if name:
        result['app.kubernetes.io/name'] = name
    return result


def resource(kind, name, spec=None, api='v1', **fields):
    value = {'apiVersion': api, 'kind': kind, 'metadata': {'name': name, 'labels': labels()}}
    if spec is not None:
        value['spec'] = spec
    value.update(fields)
    return value


def base_resources():
    items = []
    for name in SERVICES:
        items.append(resource('ServiceAccount', name, automountServiceAccountToken=False))
        items.append(resource('Service', name, {'type': 'ClusterIP', 'selector': dict(labels(name), **{'baotin.io/role': 'runtime'}),
            'ports': [{'name': 'http', 'port': 8080, 'targetPort': 'http'}]}))
        container = {'name': name, 'image': f'registry.invalid/baotin/{name}:UNCONFIGURED',
            'imagePullPolicy': 'IfNotPresent', 'ports': [{'name': 'http', 'containerPort': 8080}],
            'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                                'capabilities': {'drop': ['ALL']}},
            'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'},
                          'limits': {'cpu': '1', 'memory': '512Mi'}},
            'volumeMounts': [{'name': 'tmp', 'mountPath': '/tmp'},
                             {'name': 'public-config', 'mountPath': '/run/public', 'readOnly': True}]}
        health = '/healthz' if name == 'frontend' else '/actuator/health/'
        for key, path in [('startupProbe', health if name == 'frontend' else health+'readiness'),
                          ('readinessProbe', health if name == 'frontend' else health+'readiness'),
                          ('livenessProbe', health if name == 'frontend' else health+'liveness')]:
            container[key] = {'httpGet': {'path': path, 'port': 'http'}, 'timeoutSeconds': 3,
                              'periodSeconds': 5, 'failureThreshold': 72 if key == 'startupProbe' else 3}
        volumes = [{'name': 'tmp', 'emptyDir': {'sizeLimit': '512Mi'}},
                   {'name': 'public-config', 'configMap': {'name': name+'-public'}}]
        if name != 'frontend':
            container['env'] = [{'name': 'SPRING_CONFIG_IMPORT', 'value':
                ('configtree:/run/private/,' if name in CONFIGURED else '')+'configtree:/run/public/'}]
        if name in CONFIGURED:
            volumes.append({'name': 'private-config', 'secret': {'secretName': name+'-runtime', 'defaultMode': 288}})
            container['volumeMounts'].append({'name': 'private-config', 'mountPath': '/run/private', 'readOnly': True})
        if name == 'auth-service':
            volumes.append({'name': 'auth-keys', 'secret': {'secretName': 'auth-keys', 'defaultMode': 288}})
            container['volumeMounts'].append({'name': 'auth-keys', 'mountPath': '/run/auth-keys', 'readOnly': True})
        if name == 'model-inference-service':
            container['resources'] = {'requests': {'cpu': '500m', 'memory': '1536Mi'},
                                      'limits': {'cpu': '2', 'memory': '3Gi'}}
            volumes.append({'name': 'models', 'persistentVolumeClaim': {'claimName': 'baotin-models', 'readOnly': True}})
            container['volumeMounts'].append({'name': 'models', 'mountPath': '/models', 'readOnly': True})
        if name == 'frontend':
            container['resources'] = {'requests': {'cpu': '50m', 'memory': '32Mi'},
                                      'limits': {'cpu': '500m', 'memory': '128Mi'}}
            container['volumeMounts'].append({'name': 'public-config', 'mountPath': '/etc/nginx/conf.d', 'readOnly': True})
        pod = {'serviceAccountName': name, 'automountServiceAccountToken': False,
               'securityContext': {'runAsNonRoot': True, 'runAsUser': 101 if name == 'frontend' else 1001,
                                   'runAsGroup': 101 if name == 'frontend' else 1001, 'fsGroup': 101 if name == 'frontend' else 1001,
                                   'seccompProfile': {'type': 'RuntimeDefault'}},
               'imagePullSecrets': [{'name': 'baotin-registry'}], 'containers': [container], 'volumes': volumes}
        items.append(resource('Deployment', name, {'replicas': 1, 'revisionHistoryLimit': 3,
            'strategy': {'type': 'Recreate'}, 'selector': {'matchLabels': labels(name)},
            'template': {'metadata': {'labels': dict(labels(name), **{'baotin.io/role': 'runtime'})}, 'spec': pod}}, api='apps/v1'))
        items.append(resource('ConfigMap', name+'-public', data={}))
    items.append(resource('NetworkPolicy', 'default-deny', {'podSelector': {},
        'policyTypes': ['Ingress', 'Egress']}, api='networking.k8s.io/v1'))
    items.append(resource('NetworkPolicy', 'cluster-dns', {'podSelector': {'matchLabels': labels()},
        'policyTypes': ['Egress'], 'egress': [{'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}},
        'podSelector': {'matchLabels': {'k8s-app': 'kube-dns'}}}], 'ports': [{'port': 53, 'protocol': 'UDP'}, {'port': 53, 'protocol': 'TCP'}]}]}, api='networking.k8s.io/v1'))
    for caller, targets in CALLS.items():
        items.append(resource('NetworkPolicy', caller+'-outbound', {'podSelector': {'matchLabels': labels(caller)},
            'policyTypes': ['Egress'], 'egress': [{'to': [{'podSelector': {'matchLabels': labels(target)}} for target in targets],
                                               'ports': [{'port': 8080, 'protocol': 'TCP'}]}]}, api='networking.k8s.io/v1'))
    for target in SERVICES:
        callers = [name for name, targets in CALLS.items() if target in targets]
        if callers:
            items.append(resource('NetworkPolicy', target+'-inbound', {'podSelector': {'matchLabels': labels(target)},
                'policyTypes': ['Ingress'], 'ingress': [{'from': [{'podSelector': {'matchLabels': labels(name)}} for name in callers],
                                                       'ports': [{'port': 8080, 'protocol': 'TCP'}]}]}, api='networking.k8s.io/v1'))
    return items
