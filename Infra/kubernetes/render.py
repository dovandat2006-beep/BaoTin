"""Offline Kustomize release renderer. This module never connects to a cluster."""
import argparse
import copy
import hashlib
import ipaddress
import json
import re
import shutil
from pathlib import Path
from urllib.parse import urlsplit

from catalog import CONFIGURED, DATABASE_ROLES, DEPENDENCY_USERS, SERVICES, labels, resource

ROOT = Path(__file__).resolve().parent
NAME = re.compile(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z')
DIGEST_REF = re.compile(r'[a-z0-9][a-z0-9.:_-]*(?:/[a-z0-9][a-z0-9._-]*)+@sha256:([a-f0-9]{64})\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def dns_name(value):
    require(isinstance(value, str) and NAME.fullmatch(value), 'Expected a DNS resource name')
    return value


def https_origin(value):
    require(isinstance(value, str) and re.fullmatch(r'https://(?:[A-Za-z0-9][A-Za-z0-9.-]*|\[[a-fA-F0-9:]+\])(?::[0-9]{1,5})?', value), 'Expected a literal HTTPS origin')
    u = urlsplit(value)
    require(u.scheme == 'https' and u.hostname and not u.username and not u.password
            and not u.query and not u.fragment and not u.path and value == f'https://{u.netloc}',
            'Expected an exact HTTPS origin without credentials/path/query')
    require(u.port is None or 1 <= u.port <= 65535, 'Invalid HTTPS port')
    return value


def peer_cidrs(values):
    require(isinstance(values, list) and len(values) <= 32, 'Expected a bounded exact-peer list')
    result = []
    for value in values:
        network = ipaddress.ip_network(value, strict=True)
        require(network.prefixlen == network.max_prefixlen and not network.is_unspecified,
                'Trusted peers must be exact /32 or /128 addresses, never a PodCIDR')
        result.append(str(network))
    return sorted(set(result))


def load_json(path, maximum=131072):
    p = Path(path)
    require(p.is_file() and not p.is_symlink() and not p.is_junction() and p.stat().st_size <= maximum,
            'Bounded regular JSON file required')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate JSON configuration key')
            value[key] = item
        return value
    return json.loads(p.read_text(encoding='utf-8-sig'), object_pairs_hook=unique)


def validate_config(cfg):
    require(isinstance(cfg, dict) and type(cfg.get('schemaVersion')) is int and cfg['schemaVersion'] == 1,
            'Expected cluster config schemaVersion1')
    required = {'schemaVersion', 'namespace', 'namespaceUid', 'context', 'apiServer', 'publicOrigin',
                'modelClaim', 'modelPaths', 'imagePullSecret', 'authKeysSecret', 'secretRefs', 'dependencies'}
    optional = {'gatewayPeers', 'frontendPeers', 'edgePeers', 'edgeSource', 'providerEgress'}
    require(required <= cfg.keys() and not cfg.keys() - required - optional, 'Cluster configuration fields mismatch')
    dns_name(cfg['namespace'])
    require(cfg['namespace'] != 'default' and not cfg['namespace'].startswith('kube-'), 'Dedicated application namespace required')
    require(re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', cfg['namespaceUid']), 'Explicit namespace UID required')
    require(isinstance(cfg['context'], str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:/@+-]{0,255}', cfg['context']), 'Explicit context required')
    https_origin(cfg['apiServer']); https_origin(cfg['publicOrigin'])
    for field in ['modelClaim', 'imagePullSecret', 'authKeysSecret']:
        dns_name(cfg[field])
    require(set(cfg['secretRefs']) == set(CONFIGURED), 'Separate configured-service runtime Secret references required')
    for value in cfg['secretRefs'].values():
        dns_name(value)
    require(len(set(cfg['secretRefs'].values())) == len(CONFIGURED), 'Services must not share a runtime config Secret')
    require(set(cfg['modelPaths']) == {'text', 'url'}, 'Both complete model packages required')
    for value in cfg['modelPaths'].values():
        require(isinstance(value, str) and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}', value),
                'Model package paths must be relative single directory names')
    require(set(cfg['dependencies']) == set(DEPENDENCY_USERS), 'Postgres/Redis/Kafka/MinIO endpoints required')
    for name, dep in cfg['dependencies'].items():
        require(set(dep) == {'host', 'port', 'cidrs'}, 'Dependency fields mismatch')
        host = dep['host']
        require(isinstance(host, str) and len(host) <= 253 and not any(x in host for x in '/@?#\\'), 'Dependency host must not contain credentials or URL components')
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            require(host not in ('localhost',) and all(NAME.fullmatch(x) for x in host.split('.')), 'Invalid dependency DNS host')
        else:
            require(not address.is_loopback and not address.is_unspecified, 'Cluster dependency must not use host localhost')
        require(type(dep['port']) is int and 1 <= dep['port'] <= 65535, 'Invalid dependency port')
        require(isinstance(dep['cidrs'], list) and 1 <= len(dep['cidrs']) <= 16, 'Explicit dependency egress CIDRs required')
        for cidr in dep['cidrs']:
            net = ipaddress.ip_network(cidr, strict=True)
            require(net.prefixlen > 0 and net.is_private and not net.is_loopback, 'Explicit private dependency egress required')
    for name in ['gatewayPeers', 'frontendPeers', 'edgePeers']:
        peer_cidrs(cfg.get(name, []))
    edge = cfg.get('edgeSource')
    if edge is not None:
        require(set(edge) == {'namespace', 'podLabels'} and edge['podLabels'], 'Explicit ingress-controller namespace and pod selector required')
        dns_name(edge['namespace'])
        require(isinstance(edge['podLabels'], dict) and len(edge['podLabels']) <= 8, 'Invalid edge pod selector')
        for key, value in edge['podLabels'].items():
            require(isinstance(key, str) and isinstance(value, str) and key and value and len(key) <= 253 and len(value) <= 63,
                    'Invalid edge selector label')
    for service, rules in cfg.get('providerEgress', {}).items():
        require(service in ('auth-service', 'notification-service') and isinstance(rules, list) and len(rules) <= 16,
                'Provider egress is limited to Auth and Notification')
        for rule in rules:
            require(set(rule) == {'cidr', 'port'} and rule['port'] in (443, 465, 587), 'Unexpected provider egress port')
            ipaddress.ip_network(rule['cidr'], strict=True)
    return copy.deepcopy(cfg)


def validate_images(data):
    require(isinstance(data, dict) and data.get('schemaVersion') == 1 and set(data.get('images', {})) == set(SERVICES.values()),
            'Complete eleven-image immutable release required')
    for value in data['images'].values():
        match = DIGEST_REF.fullmatch(value) if isinstance(value, str) else None
        require(match and len(set(match.group(1))) > 1, 'Pullable non-placeholder OCI registry digest required; local Docker IDs/tags rejected')
    return data['images']


def endpoint(dep):
    host = dep['host']
    return (f'[{host}]' if ':' in host else host) + ':' + str(dep['port'])


def public_properties(name, cfg):
    p = {'server.address': '0.0.0.0', 'server.port': '8080'}
    if name in DATABASE_ROLES:
        p.update({'spring.datasource.url': 'jdbc:postgresql://'+endpoint(cfg['dependencies']['postgres'])+'/baotin',
                  'spring.datasource.username': DATABASE_ROLES[name], 'spring.flyway.enabled': 'false'})
    jwks = 'http://auth-service:8080/.well-known/jwks.json'
    if name == 'auth-service':
        p.update({'auth.allowed-origins': cfg['publicOrigin'], 'auth.private-key-path': '/run/auth-keys/private.pem',
                  'auth.encryption-key-path': '/run/auth-keys/encryption.bin',
                  'auth.trusted-gateway-cidrs': ','.join(peer_cidrs(cfg.get('gatewayPeers', []))),
                  'auth.events.enabled': 'true',
                  'auth.events.bootstrap-servers': endpoint(cfg['dependencies']['kafka'])})
    if name == 'api-gateway':
        p.update({'gateway.jwks-uri': jwks, 'gateway.allowed-origins': cfg['publicOrigin'],
                  'gateway.trusted-proxy-cidrs': ','.join(peer_cidrs(cfg.get('frontendPeers', []))),
                  'spring.data.redis.host': cfg['dependencies']['redis']['host'],
                  'spring.data.redis.port': str(cfg['dependencies']['redis']['port'])})
        for service in SERVICES:
            if service not in ('frontend', 'api-gateway'):
                key = service.removesuffix('-service')
                p['gateway.catalog.services.'+key] = 'http://'+service+':8080'
        p['gateway.auth-uri'] = 'http://auth-service:8080'
    if name == 'threat-service':
        p.update({'scan.jwks-uri': jwks, 'scan.model-uri': 'http://model-inference-service:8080',
                  'scan.reputation-uri': 'http://reputation-service:8080', 'scan.reputation-auth-mode': 'service-token',
                  'scan.trusted-gateway-cidrs': ','.join(peer_cidrs(cfg.get('gatewayPeers', []))),
                  'scan.kafka-bootstrap-servers': endpoint(cfg['dependencies']['kafka']), 'scan.events-enabled': 'true'})
    if name == 'report-service':
        p.update({'report.jwks-uri': jwks, 'report.threat-uri': 'http://threat-service:8080',
                  'report.minio.endpoint': 'http://'+endpoint(cfg['dependencies']['minio']),
                  'spring.kafka.bootstrap-servers': endpoint(cfg['dependencies']['kafka'])})
    if name == 'reputation-service':
        p.update({'reputation.jwks-uri': jwks, 'reputation.events.enabled': 'true',
                  'reputation.events.bootstrap-servers': endpoint(cfg['dependencies']['kafka']),
                  'spring.data.redis.host': cfg['dependencies']['redis']['host'],
                  'spring.data.redis.port': str(cfg['dependencies']['redis']['port'])})
    if name == 'notification-service':
        p.update({'notification.jwks-uri': jwks, 'notification.allowed-origins': cfg['publicOrigin'],
                  'notification.threat-uri': 'http://threat-service:8080', 'notification.events.enabled': 'true',
                  'notification.events.bootstrap-servers': endpoint(cfg['dependencies']['kafka'])})
    if name == 'analytics-service':
        p.update({'analytics.security.jwks-uri': jwks})
    if name == 'search-service':
        p.update({'search.events.enabled': 'true', 'spring.kafka.bootstrap-servers': endpoint(cfg['dependencies']['kafka'])})
    if name == 'model-inference-service':
        p.update({'model.text-path': '/models/'+cfg['modelPaths']['text'], 'model.url-path': '/models/'+cfg['modelPaths']['url']})
    return p


def nginx_config(cfg):
    # A literal Service upstream uses the pod's resolver at startup. No Docker DNS.
    trust = peer_cidrs(cfg.get('edgePeers', []))
    geo = '\n'.join('  '+x+' 1;' for x in trust)
    real = '\n'.join('set_real_ip_from '+x+';' for x in trust)
    host = urlsplit(cfg['publicOrigin']).hostname
    return f'''geo $realip_remote_addr $trusted_edge {{ default 0; {geo} }}
map $http_cf_visitor $cf_scheme {{ default ""; '~"scheme"\\s*:\\s*"https"' https; '~"scheme"\\s*:\\s*"http"' http; }}
map "$trusted_edge:$cf_scheme:$http_x_forwarded_proto" $external_scheme {{
  default $scheme; ~^1:https: https; ~^1:http: http; "1::https" https; "1::http" http;
}}
map "$host:$external_scheme" $canonical_redirect {{ default 0; "{host}:http" 1; }}
map $external_scheme $hsts {{ default ""; https "max-age=31536000"; }}
map $request_id $correlation_id {{ ~^(........)(....)(....)(....)(............)$ $1-$2-$3-$4-$5; }}
server {{
 listen 8080; server_name _; root /usr/share/nginx/html; index index.html;
 access_log off; error_log /dev/null; server_tokens off;
 {real}
 real_ip_header CF-Connecting-IP; real_ip_recursive off;
 add_header Strict-Transport-Security $hsts always;
 add_header X-Content-Type-Options nosniff always;
 add_header Referrer-Policy no-referrer always;
 add_header X-Frame-Options DENY always;
 add_header Content-Security-Policy "frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'" always;
 if ($canonical_redirect) {{ return 308 {cfg['publicOrigin']}$request_uri; }}
 location = /healthz {{ default_type application/json; return 200 '{{"status":"UP"}}'; }}
 location ~ ^/(internal|actuator)(/|$) {{ return 404; }}
 location = /downloads/android.json {{ add_header Cache-Control no-store; try_files $uri =404; }}
 location ~ "^/downloads/baotin-[a-f0-9]{{64}}\\.apk$" {{
   types {{ application/vnd.android.package-archive apk; }}
   add_header Content-Disposition attachment; add_header X-Content-Type-Options nosniff;
   try_files $uri =404;
 }}
 location ~ "^/api/v1/reports/[a-fA-F0-9-]{{36}}/evidence$" {{
   client_max_body_size 11m; include /run/public/api-proxy.inc;
 }}
 location ~ ^/api(/|$) {{ client_max_body_size 64k; include /run/public/api-proxy.inc; }}
 location @api_unavailable {{ default_type application/problem+json;
   add_header Cache-Control no-store; add_header X-Correlation-Id $correlation_id;
   return 503 '{{"title":"Request rejected","status":503,"code":"UPSTREAM_UNAVAILABLE","correlationId":"$correlation_id"}}';
 }}
 location / {{ try_files $uri $uri/ /index.html; }}
}}
'''


PROXY = '''proxy_pass http://api-gateway:8080;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto $external_scheme;
proxy_connect_timeout 3s; proxy_read_timeout 20s; proxy_buffering off;
error_page 502 504 = @api_unavailable;
'''


def release_resources(cfg, images, profile):
    cfg = validate_config(cfg); refs = validate_images(images)
    require(profile in ('k8s', 'k3s'), 'Unknown Kubernetes profile')
    items = load_json(ROOT/'base'/'resources.json', 1048576)['items']
    for obj in items:
        name = obj['metadata']['name']
        if obj['kind'] == 'Deployment':
            pod = obj['spec']['template']['spec']; pod['containers'][0]['image'] = refs[SERVICES[name]]
            pod['imagePullSecrets'][0]['name'] = cfg['imagePullSecret']
            for volume in pod['volumes']:
                if volume['name'] == 'private-config': volume['secret']['secretName'] = cfg['secretRefs'][name]
                if volume['name'] == 'auth-keys': volume['secret']['secretName'] = cfg['authKeysSecret']
                if volume['name'] == 'models': volume['persistentVolumeClaim']['claimName'] = cfg['modelClaim']
        if obj['kind'] == 'ConfigMap':
            service = name.removesuffix('-public')
            obj['data'] = {'default.conf': nginx_config(cfg), 'api-proxy.inc': PROXY} if service == 'frontend' else public_properties(service, cfg)
    maps = {x['metadata']['name']: x['data'] for x in items if x['kind'] == 'ConfigMap'}
    for obj in items:
        if obj['kind'] == 'Deployment':
            checksum = hashlib.sha256(json.dumps(maps[obj['metadata']['name']+'-public'], sort_keys=True).encode()).hexdigest()
            obj['spec']['template']['metadata']['annotations'] = {'baotin.io/public-config-sha256': checksum}
    for dep, callers in DEPENDENCY_USERS.items():
        target = cfg['dependencies'][dep]
        for caller in callers:
            items.append(resource('NetworkPolicy', caller+'-to-'+dep, {'podSelector': {'matchLabels': labels(caller)},
                'policyTypes': ['Egress'], 'egress': [{'to': [{'ipBlock': {'cidr': value}} for value in target['cidrs']],
                'ports': [{'port': target['port'], 'protocol': 'TCP'}]}]}, api='networking.k8s.io/v1'))
    for caller, rules in cfg.get('providerEgress', {}).items():
        def provider_block(cidr):
            block = {'cidr': cidr}
            net = ipaddress.ip_network(cidr)
            if net.prefixlen == 0:
                block['except'] = (['0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8',
                                    '169.254.0.0/16', '172.16.0.0/12', '192.168.0.0/16']
                                   if net.version == 4 else ['::1/128', 'fc00::/7', 'fe80::/10'])
            return block
        items.append(resource('NetworkPolicy', caller+'-providers', {'podSelector': {'matchLabels': labels(caller)},
            'policyTypes': ['Egress'], 'egress': [{'to': [{'ipBlock': provider_block(rule['cidr'])}],
            'ports': [{'port': rule['port'], 'protocol': 'TCP'}]} for rule in rules]}, api='networking.k8s.io/v1'))
    if cfg.get('edgeSource'):
        edge = cfg['edgeSource']
        items.append(resource('NetworkPolicy', 'frontend-from-edge', {'podSelector': {'matchLabels': labels('frontend')},
            'policyTypes': ['Ingress'], 'ingress': [{'from': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': edge['namespace']}},
            'podSelector': {'matchLabels': edge['podLabels']}}], 'ports': [{'port': 8080, 'protocol': 'TCP'}]}]}, api='networking.k8s.io/v1'))
    return items


def write_release(cfg, images, profile, output):
    items = release_resources(cfg, images, profile)
    output = Path(output)
    require(not output.exists(), 'New release output directory required; accepted releases are never overwritten')
    output.mkdir(parents=True)
    (output/'base').mkdir(); (output/'overlays'/profile).mkdir(parents=True)
    (output/'base'/'resources.json').write_text(json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': items}, indent=2), encoding='utf-8')
    for source, destination in [(ROOT/'base'/'kustomization.yaml', output/'base'/'kustomization.yaml'),
                                (ROOT/'overlays'/profile/'kustomization.yaml', output/'overlays'/profile/'kustomization.yaml')]:
        require(source.is_file() and not source.is_symlink() and not source.is_junction(), 'Regular reviewed Kustomize source required')
        shutil.copyfile(source, destination)
    data = {'apiVersion': 'kustomize.config.k8s.io/v1beta1', 'kind': 'Kustomization',
            'namespace': cfg['namespace'], 'resources': ['overlays/'+profile]}
    (output/'kustomization.yaml').write_text(json.dumps(data, indent=2), encoding='utf-8')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True); parser.add_argument('--images', required=True)
    parser.add_argument('--profile', choices=['k8s', 'k3s'], required=True); parser.add_argument('--output', required=True)
    args = parser.parse_args()
    write_release(load_json(args.config), load_json(args.images), args.profile, args.output)
    print('KUSTOMIZE_RELEASE_RENDER PASS profile='+args.profile+' services=11 credentials_embedded=false cluster_contacted=false')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError):
        raise SystemExit('KUSTOMIZE_RELEASE_RENDER FAIL invalid input; no raw config or credential values logged')
