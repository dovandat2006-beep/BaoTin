# Report Service acceptance

Implemented and verified:
- JWT issuer/audience/RS256 validation, owner isolation and moderator/admin authorization.
- Owned report schema, optimistic versions, state/history and idempotent creation.
- Private MinIO upload/download/delete, MIME/size/count checks and eventual object purge.
- Verified optional scan references and Threat internal target resolution, transactional report lifecycle outbox and ordered Kafka publication.
- 23 workflow/security tests; Docker integration with real PostgreSQL, MinIO and Kafka.
- Frontend Report/Evidence/Moderation through Gateway; separate HTTP browser fixtures.

Public deployment acceptance (FullStack27 / Report30, 2026-10-27):
- Separate migration/runtime identities and prefix-restricted private bucket credentials are provisioned outside Git; schema versions1..3 applied and runtime negative grants passed.
- Jenkins gated promotion, loopback readiness/artifact hashes and public owner/moderator/evidence/version/role tests passed.
- Real Report Kafka lifecycle notifications passed on the public domain; async Reputation risk flow also passed. Analytics remains scaffold, not accepted business functionality.
- User-approved temporary test ADMIN/MODERATOR were demoted to USER, sessions revoked, audit retained; real account roles unchanged.

Remaining:
- Kafka ACL/TLS hardening beyond the current private/loopback deployment.
- Physical-device picker/share acceptance (Web acceptance does not imply Android/iOS device acceptance).
- See Infra's `docs/api-integration-acceptance-2026-10-27.md` for proof and compatible rollback boundaries.

Commands, limits and evidence: REPORT_RUNBOOK.md.
