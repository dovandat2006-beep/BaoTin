# K8s/K3s isolated acceptance — 2026-11-04

This record concerns the added deployment methods, not production cutover.
No primary tunnel/DNS, global kubeconfig, production database or original models
were changed. Real tests use private fresh dependencies and the source models
mounted read-only, not model or API mocks.

## Actual K3s acceptance

K3s v1.34.1+k3s1, one bounded6GiB node, restricted application namespace,
scoped deployer and a Bound read-only model claim:

- Operator namespace-first server validation/apply; immutable per-service Secrets.
- Wrong namespace UID rejected before mutation; PVC deletion and Secret reads in
  another namespace denied for the deployer.
- Eleven immutable artifact-only images from verified Jenkins child7 releases;
  actual OS/JAR scans and SBOMs for11 images,0HIGH/CRITICAL.
- Eleven actual workloads ready; private Gateway->Threat->real ONNX->Postgres and
  Kafka/Reputation round trip pass. Unauthenticated `/auth/me` and direct Gateway
  `/internal` return401; public nginx hides `/internal` and `/actuator` with404.
- Authorized Threat identity reaches model readiness; frontend identity receives
  no HTTP and connection failure/timeout. HTTP errors and DNS failures are not
  accepted as proof of NetworkPolicy denial.
- Actual web200, canonical HTTP redirect308 and accepted APK served through nginx,
  byte-for-byte SHA256 `2c305f5a7a20cbaad57551e027b34d91d9d90ab7d407f27d971ed0fc5fe20779`,
  88,511,603bytes. No native recompilation/device test is claimed.
- A candidate with an absent model package genuinely fails readiness. The test
  limits that candidate's client wait to25s; normal rollback uses420s. Real
  frontend/Gateway Pod IPs change, compatible predecessor is restored, all11
  services and real smoke pass again, exact trust peers match the new ready pods,
  Secret/PVC identities remain unchanged. Smoke creates synthetic scan records;
  `data retained` means no destructive rollback/down-migration, not unchanged rows.

Literal terminal results (exit0):

```text
KUBERNETES_DEPLOY PASS profile=k3s services=11 readiness=UP real_smoke=PASS rollback=retained
ACTUAL_CLUSTER_WEB_PASS web=200 canonical=308 internal=404 actuator=404 guest=401 apk=exact_hash_and_bytes
KUBERNETES_ROLLBACK PASS previous_application=restored readiness=UP data=retained
ACTUAL_FAILED_CANDIDATE_ROLLBACK PASS model_candidate=not_ready previous=restored services=11 real_smoke=PASS exact_peers=replaced_and_reconciled secrets_pvc_data=retained
```

Initial trials found real defects, not passing evidence: numeric DB metadata
strings from Auth, private Gateway401 vs nginx404, a multihomed test node IP
changing after reboot (policy chains existed without pod enforcement), and a new
Job racing policy convergence. These were corrected; only read-only health GETs
have bounded convergence attempts, scan POSTs are not retried. Failed initial
owned/stopped workloads were retired while preserving namespace/data/Secrets/PVC.

K3s was stopped before standard Kubernetes acceptance to avoid overlapping two
application clusters' RAM. Both disposable clusters were later removed after
their acceptance, not switched into the primary BảoTin runtime.

## Standard Kubernetes

Actual kind Kubernetes v1.34.0 with pinned Calico v3.31.0 has passed CNI readiness,
namespace/RBAC/model-PVC/immutable-Secret bootstrap and scoped negative preflight.
All11 real workloads, native models, Gateway/Scan/Postgres/Kafka/Reputation,
authorized model access and denied frontend access passed, followed by the same
web/APK and real failed-model/changed-peer rollback tests used for K3s. The actual
PowerShell entry point also bound all11 image provenance records against Jenkins'
immutable successful sources/artifacts and streamed the real web archive hash.

```text
JENKINS_IMAGE_BINDING PASS builds=11 immutable_sources_artifacts=matched frontend_origin=matched temporary_artifact_copies=0
KUBERNETES_DEPLOY PASS profile=k8s services=11 readiness=UP real_smoke=PASS rollback=retained
ACTUAL_CLUSTER_WEB_PASS web=200 canonical=308 internal=404 actuator=404 guest=401 apk=exact_hash_and_bytes
ACTUAL_FAILED_CANDIDATE_ROLLBACK PASS model_candidate=not_ready previous=restored services=11 real_smoke=PASS exact_peers=replaced_and_reconciled secrets_pvc_data=retained
```

An interrupted first trial was not accepted: Docker restarted after a wall-clock
gap and the2h scoped token expired. Same test dependencies/keys/DB were restored,
the partial owned candidate retired after namespace/nonce inspection, a fresh
scoped token issued, node IP pinned and Calico reinitialized. The second trial
and rollback completed. No fake accepted history or production data was used.
Actual Jenkins validation also found its anonymous artifact endpoint returns403
without a401 challenge; the streamed downloader now authenticates explicitly to
the already identified loopback origin, with redirects disabled. It then matched
the actual immutable archive. Source regressions48 and existing pipeline checks
166+67 pass; actual authenticated Jenkinsfile validation passes.

## Source and cleanup evidence

Source regressions48, pipeline checks166+67 and authenticated Jenkinsfile validation
pass. The marked independent source copy is restored to original hashes by the
executable rollback script; the main worktree remains changed, and the four
transaction artifacts are reopened after final source changes.

Owned cleanup removed6 test containers,12 exclusive test volumes,2 dedicated
private networks,11 artifact-image tags and4 portable test-tool files. Nine
protected containers retained identical IDs/start times/health, and all22 original
model files retained identical hashes. Wrong-path and actual-junction negative
tests passed before file removal. No global prune, worker/autorun, L: or Worklets
deletion occurred. Source/keys/verified CI artifacts/APK/evidence and shared base
images/build cache remain retained, not mislabeled as obsolete test cache.

Final SCM revision/push and reviewed Jenkins pin promotion are recorded in the
external operator transaction. Offline imported content-addressed images are test-only;
registry publication and production OAuth/ingress cutover are not claimed.
