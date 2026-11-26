"""Guarded Kustomize apply/rollout/rollback. No implicit context or data migration."""
import argparse
import copy
import hashlib
import ipaddress
import json
import os
import re
import subprocess
import tempfile
import uuid
from pathlib import Path

from catalog import CONFIGURED, ORDER, SERVICES, labels
from render import ROOT, endpoint, load_json, release_resources, require, validate_config, validate_images, write_release


class KubectlError(RuntimeError):
    pass


class Kubectl:
    def __init__(self, cfg, kubeconfig, executable='kubectl'):
        self.cfg = cfg
        self.prefix = [executable, '--kubeconfig', str(kubeconfig), '--context', cfg['context'],
                       '--namespace', cfg['namespace'], '--request-timeout=30s']

    def run(self, args, body=None, timeout=480):
        data = json.dumps(body) if isinstance(body, (dict, list)) else body
        try:
            result = subprocess.run(self.prefix+args, input=data, text=True, encoding='utf-8',
                                    capture_output=True, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise KubectlError('kubectl operation unavailable or timed out; credentials/output withheld') from None
        if result.returncode:
            raise KubectlError('kubectl '+args[0]+' failed; raw output withheld')
        return result.stdout

    def json(self, args):
        return json.loads(self.run(args))

    def json_input(self, args, body):
        # kubectl printers may emit a List or adjacent JSON documents when a
        # multi-resource manifest is supplied. Both are normal client output.
        text = self.run(args, body);decoder = json.JSONDecoder();position = 0;items = []
        while position < len(text):
            while position < len(text) and text[position].isspace(): position += 1
            if position == len(text): break
            value, position = decoder.raw_decode(text, position)
            require(isinstance(value, dict), 'Expected kubectl resource objects')
            items.extend(value['items'] if value.get('kind') == 'List' else [value])
        require(items, 'Empty kubectl resource output')
        return {'apiVersion': 'v1', 'kind': 'List', 'items': items}


def validate_external_file(path):
    p = Path(path).absolute()
    require(p.is_file() and not p.is_symlink() and not p.is_junction() and p.stat().st_size <= 1048576,
            'Bounded regular external configuration file required')
    require(not p.resolve().is_relative_to(ROOT.parents[1]), 'Configuration and kubeconfig must stay outside the source workspace')
    return p


def compatible(caps, database):
    def revision(value, message):
        # RuntimeDatabaseState emits DB text columns as canonical decimal strings;
        # capabilities emit JSON integers. Match the owning operator's 1..9 digits.
        if isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,8}', value):
            value = int(value)
        require(type(value) is int and 1 <= value <= 999999999, message)
        return value
    application = {}
    for key in ('readerVersion', 'minimumSchema'):
        application[key] = revision(caps.get(key), 'Invalid application capability metadata')
    state = {}
    for key in ('readerFloor', 'schemaRevision'):
        state[key] = revision(database.get(key), 'Invalid database compatibility metadata')
    require(application['readerVersion'] >= state['readerFloor'] and application['minimumSchema'] <= state['schemaRevision']
            and database.get('cipherFloor') in caps.get('cipherReaders', []),
            'Application reader/schema/cipher floors are incompatible; no runtime replacement')


def readiness_gate(url):
    # A new Job's NetworkPolicy/ipsets may converge after its process starts.
    # Retry only a read-only health GET, never a scan POST or the denial probe.
    return ('ready=false; for attempt in 1 2 3 4 5; do '
            'if curl --retry 0 -fsS --max-time 3 '+url+' > /tmp/readiness.json 2>/dev/null; '
            'then ready=true; break; fi; sleep 2; done; test "$ready" = true; ')


def assert_runtime_objects(objects, cfg):
    allowed = {'ServiceAccount', 'Service', 'ConfigMap', 'Deployment', 'NetworkPolicy'}
    require(isinstance(objects, list) and objects, 'Empty candidate resource set')
    for obj in objects:
        require(obj['kind'] in allowed and obj['metadata']['namespace'] == cfg['namespace'], 'Unexpected resource kind or namespace')
        require(obj['metadata']['labels'].get('app.kubernetes.io/part-of') == 'baotin', 'Unexpected resource owner')
        if obj['kind'] == 'Service':
            require(obj['spec']['type'] == 'ClusterIP' and not obj['spec'].get('externalIPs'), 'Public backend Service rejected')
        if obj['kind'] == 'Deployment':
            pod = obj['spec']['template']['spec']
            require(pod.get('automountServiceAccountToken') is False and not pod.get('hostNetwork'), 'Unsafe pod network or token mounting')
            require(pod['securityContext'].get('runAsNonRoot') is True, 'Root runtime rejected')
            for c in pod['containers']:
                sec = c['securityContext']
                require(sec.get('readOnlyRootFilesystem') is True and sec.get('allowPrivilegeEscalation') is False
                        and sec.get('capabilities', {}).get('drop') == ['ALL'], 'Unsafe container privilege/filesystem settings')
                require(all('hostPort' not in p for p in c.get('ports', [])), 'Host port rejected')
                require(all(x in c for x in ('startupProbe', 'livenessProbe', 'readinessProbe', 'resources')), 'Missing probes/resources')


def preflight(kube, cfg, profile):
    view = kube.json(['config', 'view', '--minify', '-o', 'json'])
    require(len(view.get('clusters', [])) == 1, 'One explicitly selected cluster required')
    cluster = view['clusters'][0]['cluster']
    require(cluster.get('server') == cfg['apiServer'] and not cluster.get('insecure-skip-tls-verify'), 'Cluster endpoint or TLS verification mismatch')
    namespace = kube.json(['get', 'namespace', cfg['namespace'], '-o', 'json'])
    require(namespace['metadata']['uid'] == cfg['namespaceUid'], 'Namespace identity changed')
    meta = namespace['metadata'].get('labels', {})
    require(meta.get('baotin.io/managed') == 'true' and meta.get('pod-security.kubernetes.io/enforce') == 'restricted',
            'Operator-created dedicated restricted namespace required')
    version = kube.json(['version', '-o', 'json'])
    client_minor = int(re.match(r'\d+', version['clientVersion']['minor']).group())
    server_minor = int(re.match(r'\d+', version['serverVersion']['minor']).group())
    require(abs(client_minor-server_minor) <= 1, 'kubectl/server version skew exceeds supported range')
    require(profile != 'k3s' or 'k3s' in version['serverVersion']['gitVersion'], 'K3s profile requires a K3s server')
    pvc = kube.json(['get', 'pvc', cfg['modelClaim'], '-o', 'json'])
    require(pvc.get('status', {}).get('phase') == 'Bound', 'Real model PVC must be Bound before deployment')
    identities = {'modelClaim': pvc['metadata']['uid']}
    for name in sorted(set(cfg['secretRefs'].values()) | {cfg['authKeysSecret']}):
        # Only metadata is emitted, never Secret .data or kubeconfig --raw.
        value = kube.run(['get', 'secret', name, '-o', 'jsonpath={.metadata.uid}{" "}{.immutable}']).strip().split()
        require(len(value) == 2 and value[1] == 'true', 'Immutable versioned runtime Secret required')
        identities[name] = value[0]
    kube.run(['get', 'secret', cfg['imagePullSecret'], '-o', 'name'])
    return identities


class Deployment:
    def __init__(self, kube, cfg, images, profile, state_dir):
        self.kube, self.cfg, self.images, self.profile = kube, cfg, images, profile
        self.state_dir = Path(state_dir)
        self.release_id = uuid.uuid4().hex
        self.probes = []
        self.applied_names = []

    def accept(self, record):
        pending = self.state_dir/(uuid.uuid4().hex+'.accepted.pending')
        pending.write_text(json.dumps(record, indent=2), encoding='utf-8')
        os.replace(pending, self.state_dir/'accepted.json')

    def rendered(self, cfg):
        # Copy only reviewed base/profile JSON/YAML, never secrets or model data.
        with tempfile.TemporaryDirectory(prefix='baotin-kustomize-', dir=self.state_dir) as temp:
            out = write_release(cfg, self.images, self.profile, Path(temp)/'release')
            manifest = self.kube.run(['kustomize', str(out)])
            declared = self.kube.json_input(['create', '--dry-run=client', '--validate=false', '-f', '-', '-o', 'json'], manifest)
            checked = self.kube.json_input(['apply', '--dry-run=server', '-f', '-', '-o', 'json'], manifest)
        server_objects = checked['items'] if checked.get('kind') == 'List' else [checked]
        assert_runtime_objects(server_objects, cfg)
        # Server dry-run can allocate illustrative Service clusterIPs (including
        # reserved placeholders), UIDs/resourceVersions and status defaults. They
        # are validation output, never the declarative payload to apply for real.
        objects = declared['items'] if declared.get('kind') == 'List' else [declared]
        assert_runtime_objects(objects, cfg)
        identity = lambda x: (x['kind'], x['metadata']['namespace'], x['metadata']['name'])
        require({identity(x) for x in objects} == {identity(x) for x in server_objects}, 'Admission resource identity changed')
        for obj in objects:
            obj['metadata']['labels']['baotin.io/release-id'] = self.release_id
        return objects

    def apply(self, objects):
        self.kube.run(['apply', '-f', '-'], {'apiVersion': 'v1', 'kind': 'List', 'items': objects})

    def peers(self, name):
        value = self.kube.json(['get', 'pods', '-l', 'app.kubernetes.io/part-of=baotin,app.kubernetes.io/name='+name+',baotin.io/role=runtime', '-o', 'json'])
        result = []
        for pod in value['items']:
            if pod['metadata'].get('deletionTimestamp'): continue
            require(any(x.get('kind') == 'ReplicaSet' and x.get('controller') for x in pod['metadata'].get('ownerReferences', [])), 'Unexpected peer pod controller')
            require(any(x['type'] == 'Ready' and x['status'] == 'True' for x in pod['status'].get('conditions', [])), 'Peer pod not Ready')
            for address in pod['status'].get('podIPs', []):
                ip = ipaddress.ip_address(address['ip']); result.append(str(ip)+'/'+str(ip.max_prefixlen))
        require(result, 'No ready exact peer addresses discovered')
        return sorted(set(result))

    def probe(self, objects, role, image, command, args, env=None):
        template = copy.deepcopy(next(x for x in objects if x['kind'] == 'Deployment' and x['metadata']['name'] == role)['spec']['template'])
        name = 'baotin-probe-'+uuid.uuid4().hex[:20]
        template['metadata']['labels']['baotin.io/role'] = 'probe'
        pod = template['spec'];pod['restartPolicy'] = 'Never'
        allowed_volumes = ('tmp', 'private-config') if role == 'auth-service' and command == ['java'] else ('tmp',)
        pod['volumes'] = [x for x in pod['volumes'] if x['name'] in allowed_volumes]
        for volume in pod['volumes']:
            if volume['name'] == 'private-config':
                volume['secret']['items'] = [{'key': 'spring.datasource.password', 'path': 'spring.datasource.password'}]
        c = pod['containers'][0];c['image'] = image;c['command'] = command;c['args'] = args
        c['volumeMounts'] = [x for x in c['volumeMounts'] if x['name'] in allowed_volumes]
        c['env'] = env or []
        for key in ('startupProbe', 'readinessProbe', 'livenessProbe', 'ports'): c.pop(key, None)
        c['resources'] = {'requests': {'cpu': '50m', 'memory': '64Mi'}, 'limits': {'cpu': '1', 'memory': '256Mi'}}
        job = {'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': {'name': name, 'namespace': self.cfg['namespace'],
               'labels': {'app.kubernetes.io/part-of': 'baotin', 'baotin.io/probe-id': self.release_id}},
               'spec': {'backoffLimit': 0, 'activeDeadlineSeconds': 120, 'ttlSecondsAfterFinished': 600, 'template': template}}
        created = False
        try:
            self.kube.run(['create', '-f', '-'], job);created = True
            self.kube.run(['wait', '--for=condition=complete', '--timeout=120s', 'job/'+name], timeout=140)
            result = self.kube.run(['logs', 'job/'+name, '--tail=20'])
            self.probes.append(name)
            return result
        finally:
            if created:
                check = self.kube.json(['get', 'job', name, '-o', 'json'])
                require(check['metadata']['labels'].get('baotin.io/probe-id') == self.release_id, 'Probe ownership changed; preserve resource')
                self.kube.run(['delete', 'job/'+name, '--wait=true'])

    def compatibility(self, objects, previous):
        auth = self.images['images']['AUTH_IMAGE']
        def run(image, main):
            env = [{'name': 'DB_URL', 'value': 'jdbc:postgresql://'+endpoint(self.cfg['dependencies']['postgres'])+'/baotin'},
                   {'name': 'DB_USER', 'value': 'baotin_auth'},
                   {'name': 'DB_PASSWORD_FILE', 'value': '/run/private/spring.datasource.password'}]
            text = self.probe(objects, 'auth-service', image, ['java'], ['-Xms16m', '-Xmx128m', '-XX:ActiveProcessorCount=2',
                '-Dloader.main=com.baotin.auth.operator.'+main, '-cp', '/app/app.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'], env)
            rows = [line for line in text.splitlines() if line.startswith('{')]
            require(rows, 'Missing read-only compatibility metadata')
            return json.loads(rows[-1])
        db = run(auth, 'RuntimeDatabaseState');compatible(run(auth, 'RuntimeCapabilities'), db)
        if previous: compatible(run(previous['images']['images']['AUTH_IMAGE'], 'RuntimeCapabilities'), db)

    def smoke(self, objects):
        image = self.images['images']['AUTH_IMAGE']  # The verified glibc runtime includes curl.
        # Positive private reachability before the independent denial check.
        value = self.probe(objects, 'threat-service', image, ['sh', '-c'],
            ['set -eu; '+readiness_gate('http://model-inference-service:8080/actuator/health/readiness')+'cat /tmp/readiness.json'])
        require(json.loads(value)['status'] == 'UP', 'Model is not reachable from its authorized caller')
        body = json.dumps({'url': 'https://example.com/baotin-kubernetes-smoke?release='+self.release_id})
        request = "curl --retry 0 -fsS --max-time 20 -H 'Content-Type: application/json' --data-binary '"+body+"' http://api-gateway:8080/api/v1/scans/url"
        script = ('set -eu; '+readiness_gate('http://api-gateway:8080/actuator/health/readiness')+
                  request+' > /tmp/first.json; sleep 15; '+request+' > /tmp/second.json; cat /tmp/first.json; echo; cat /tmp/second.json; echo; '
                  "test \"$(curl --retry 0 -sS --max-time 10 -o /dev/null -w '%{http_code}' http://api-gateway:8080/api/v1/auth/me)\" = 401; "
                  # The private Gateway rejects unauthenticated internal paths at
                  # security before route lookup (401); public nginx hides them404.
                  "test \"$(curl --retry 0 -sS --max-time 10 -o /dev/null -w '%{http_code}' http://api-gateway:8080/internal/v1/inference/text)\" = 401; "
                  "set +e; denied=$(curl --retry 0 -sS --connect-timeout 2 --max-time 3 -o /dev/null -w '%{http_code}' "
                  'http://model-inference-service:8080/actuator/health/readiness 2>/dev/null); rc=$?; set -e; '
                  'if [ "$denied" != 000 ] || { [ "$rc" -ne 7 ] && [ "$rc" -ne 28 ]; }; then exit 42; fi; '
                  'printf "MODEL_NETWORK_DENIED http=%s curl=%s\\n" "$denied" "$rc"')
        text = self.probe(objects, 'frontend', image, ['sh', '-c'], [script])
        values = [json.loads(x) for x in text.splitlines() if x.startswith('{')]
        require(len(values) == 2 and all(x.get('aiAvailable') is True and x.get('degraded') is False for x in values), 'Real Gateway/Threat/model/database smoke failed')
        require(values[-1]['urls'][0].get('reputationScore') is not None, 'Kafka/Reputation synchronous bridge smoke failed')
        return {'actualApiMocking': False, 'realModel': True, 'realReputation': True, 'guestAdmin': 401,
                'gatewayInternal': 401, 'unauthorizedModelNetworkAccess': 'denied'}

    def rollout(self, cfg, previous):
        print('KUBERNETES_STAGE compatibility=checking', flush=True)
        objects = self.rendered(cfg)
        self.apply([x for x in objects if x['kind'] != 'Deployment'])
        self.compatibility(objects, previous)
        for name in ORDER:
            print('KUBERNETES_STAGE workload='+name+' readiness=checking', flush=True)
            objects = self.rendered(cfg)
            if name not in self.applied_names: self.applied_names.append(name)
            self.apply([x for x in objects if (x['kind'] == 'ConfigMap' and x['metadata']['name'] == name+'-public')
                        or (x['kind'] == 'Deployment' and x['metadata']['name'] == name)])
            self.kube.run(['rollout', 'status', 'deployment/'+name, '--timeout=420s'], timeout=450)
            if name == 'frontend': cfg['frontendPeers'] = self.peers(name)
            if name == 'api-gateway': cfg['gatewayPeers'] = self.peers(name)
        print('KUBERNETES_STAGE real_api_and_network=checking', flush=True)
        return self.smoke(objects), cfg

    def execute(self):
        identities = preflight(self.kube, self.cfg, self.profile)
        accepted = self.state_dir/'accepted.json'
        previous = load_json(accepted, 1048576) if accepted.exists() else None
        if previous:
            require(previous['profile'] == self.profile and all(previous['config'][x] == self.cfg[x] for x in ('namespace', 'namespaceUid', 'apiServer', 'context', 'dependencies', 'authKeysSecret', 'modelClaim')),
                    'Target/data/key identity changed; use separate reviewed migration or rotation')
            require(previous['identities'] == identities, 'Immutable Secret/model claim identity changed')
        else:
            existing = self.kube.json(['get', 'deployments', '-o', 'json'])
            require(not existing['items'], 'Managed workloads already exist without local accepted-state history; preserve them')
        candidate_cfg = copy.deepcopy(self.cfg)
        try:
            smoke, applied = self.rollout(candidate_cfg, previous)
        except Exception:
            if previous:
                old_images = self.images;self.images = previous['images']
                try:
                    restored_smoke, restored_cfg = self.rollout(copy.deepcopy(previous['config']), previous)
                    recovered = dict(previous, config=restored_cfg, smoke=restored_smoke)
                    self.accept(recovered)
                except Exception:
                    raise KubectlError('Candidate failed; previous readiness/rollback requires operator review') from None
                finally: self.images = old_images
                print('KUBERNETES_ROLLBACK PASS previous_application=restored readiness=UP data=retained')
                raise KubectlError('Candidate failed; previous compatible application release restored; data retained') from None
            for name in self.applied_names:
                text = self.kube.run(['get', 'deployment', name, '--ignore-not-found', '-o', 'json'])
                if not text.strip(): continue
                current = json.loads(text)
                require(current['metadata'].get('labels', {}).get('baotin.io/release-id') == self.release_id,
                        'Candidate ownership changed; preserve workload')
                self.kube.run(['patch', 'deployment', name, '--type=merge', '-p', '{"spec":{"replicas":0}}'])
            raise KubectlError('Initial candidate failed; only owned candidate workloads stopped; namespace/data/Secrets/PVCs retained') from None
        record = {'schemaVersion': 1, 'releaseId': self.release_id, 'profile': self.profile,
                  'config': applied, 'images': self.images, 'identities': identities, 'smoke': smoke}
        destination = self.state_dir/(self.release_id+'.json')
        destination.write_text(json.dumps(record, indent=2), encoding='utf-8')
        self.accept(record)
        return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=['k8s', 'k3s'], required=True)
    parser.add_argument('--config', required=True);parser.add_argument('--images', required=True)
    parser.add_argument('--kubeconfig', required=True);parser.add_argument('--state-directory', required=True)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    cfg = validate_config(load_json(validate_external_file(args.config)))
    images = load_json(validate_external_file(args.images));validate_images(images)
    kubeconfig = validate_external_file(args.kubeconfig)
    state = Path(args.state_directory).absolute()
    require(state != Path(state.anchor) and not state.resolve().is_relative_to(ROOT.parents[1])
            and not state.is_symlink() and not state.is_junction(), 'External non-root regular state directory required')
    for parent in state.parents:
        require(not parent.is_symlink() and not parent.is_junction(), 'State directory must not traverse a junction/symlink')
    if args.validate_only:
        release_resources(cfg, images, args.profile)
        print('KUBERNETES_PREFLIGHT PASS profile='+args.profile+' images=11 mutation=false cluster_contacted=false');return
    state.mkdir(parents=True, exist_ok=True)
    lock_path = state/'deployment.lock'
    lock = lock_path.open('x', encoding='utf-8')
    try:
        lock.write(str(os.getpid()))
        lock.flush()
        Deployment(Kubectl(cfg, kubeconfig), cfg, images, args.profile, state).execute()
    finally:
        lock.close()
        lock_path.unlink()
    print('KUBERNETES_DEPLOY PASS profile='+args.profile+' services=11 readiness=UP real_smoke=PASS rollback=retained')


if __name__ == '__main__':
    try: main()
    except KubectlError as error:
        raise SystemExit('KUBERNETES_DEPLOY FAIL '+str(error))
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError):
        raise SystemExit('KUBERNETES_DEPLOY FAIL invalid input or failed gate; raw configuration/credentials withheld')
