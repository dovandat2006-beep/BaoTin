# Threat → Reputation read bridge

Design approved by the operator on 2026-11-02: a separate private, read-only
service credential. A short-lived service JWT was considered but would require
an additional issuance/rotation lifecycle; reusing an ADMIN JWT would overgrant
and expire after 600 seconds.

## Contract and boundary

- Keep the existing Auth ADMIN/MODERATOR JWT GET and ADMIN-only recalculation.
- Add `X-Service-Token` as an alternative on the single internal target GET.
- Use a separate random 256-bit key, represented as 64 lowercase hex characters.
  An empty configuration disables this mechanism. Fail startup on malformed
  configuration; reject empty/repeated request headers and mixed credentials.
- Assign only `SCOPE_reputation.read`, never an administrative role. Reject all
  writes, other paths and methods when service authentication is selected.
- Keep the endpoint private; add no Gateway route, tunnel ingress, database grant
  or host port. Never expose this key in client configuration or logs.

## Caller and compatibility

Threat keeps `bearer` as its default Reputation authentication mode and its
existing constructor for callers/tests. Explicit `service-token` mode sends only
`X-Service-Token`. Model authentication is unchanged. Existing target IDs and
`finalScore` remain authoritative. Missing targets stay unknown; provider errors
stay explicitly unavailable, never replaced by an invented score.

## Implementation and proof

1. Preserve three original repo archives, hashes and verified Git bundles.
2. Extend Contracts and boundary regression tests, reproduce the missing read.
3. Implement the ordered, non-global service filter and caller mode; document
   external configuration, rotation and rollback.
4. Run contract validation and complete affected JDK21 wrapper tests/bootJar,
   including JWT compatibility, header ambiguity, disabled keys and write denial.
5. Verify source rollback on independent copies; run pre-push security gates.
6. Push non-forced main commits, approve exact pins, promote through global
   Jenkins, then verify actual public Scan → Gateway → Threat → Reputation and
   frontend score mapping with real data. Keep the current release until gates pass.

## Configuration and rollback

Expand Reputation first, then enable Threat's external URI/key/mode. Maintain
the existing external secret-file ACLs. To rotate, update both private config
trees together and promote/restart through the reviewed lane. To roll back,
disable Threat lookup first and promote previous reviewed application artifacts;
restore private config from its protected backup. No schema/data removal, key
logging or Docker-volume reset is involved. Source rollback is tested only on
marked independent copies and never implicitly applied to live services.
