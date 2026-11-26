# K8s/K3s implementation plan

Approved direction: `k8s-k3s-design-2026-11-03.md`; Kustomize, no production cutover.

1. Preserve original Infra bundle/Jenkinsfile hash. Add failing tests for the
   missing targets and configuration/manifest boundaries.
2. Implement a common workload/network/security base and two actual Kustomize
   overlays. Render external reviewed image/config inputs into a new release
   directory. Validate before any cluster request.
3. Implement explicit-context/namespace preflight, server dry-run, bounded ordered
   rollout, exact-peer discovery, smoke checks and retained-release rollback.
   Namespace/Secret/model/data provisioning remains a separate operator step.
4. Package verified JAR/web artifacts into non-root, scanned OCI images, including
   glibc/OpenMP model support and the source-bound signed APK publication.
5. Add `k8s`/`k3s` branches to FullStack without altering old target/default/approval
   semantics; add external configuration examples and bootstrap/runbooks.
6. Run unit/negative/real kubectl-render tests, existing Infra pipeline tests and
   actual isolated cluster validation for each profile. Record real and fixture
   evidence separately; do not label dry-run/fake kubectl as a live rollout.
7. Review, source-copy rollback test, source artifacts/hashes, final validation and
   exact-owned temporary cleanup. Keep the goal active until required proof holds.

All temporary build/cluster artifacts for this goal go on M:, not L:. The separate
L: inventory requested by the user was read-only; no cleanup was performed there.
