# BảoTin Contracts

Source of truth được review/tag cho OpenAPI 3.1, event schemas và compatibility policy giữa các repo.

## Cấu trúc dự kiến

```text
openapi/     # public/internal REST contracts
events/      # versioned domain-event schemas
docs/        # error, compatibility và event-envelope conventions
```

Không đặt JPA entity hoặc business implementation dùng chung ở đây. Breaking change phải có version/migration path; OpenAPI sẽ được lint trước khi publish.

## Validate contracts

`python3 -m pip install -r tests/requirements.txt` then `python3 -m unittest discover -s tests`. Tests validate the OpenAPI documents, versioned event schemas, and public/internal authentication boundaries, including the reputation internal API and report/threat/campaign/reputation events.

Search public GET and separate additive host-only event are documented in
`docs/search-v1.md`, `openapi/search-service.json` and `events/search/`.

## Masked message lookup — 2026-11-12 (candidate, not deployed)

Message history retains readable masked wording, rather than hiding the whole
message. Detected contacts, numeric identifiers, labelled secrets/name/address
fields and URL paths/query values become `***`; automatic pattern masking is
not a guarantee of detecting every possible personal detail. High/critical text
scans publish only masked wording through the owning Threat Outbox. Search
validates the same masking policy, returns optional full `content`, and removes
it on PRIVATE/REMOVED. Existing fingerprint-only documents remain compatible.
Old hidden snapshots without retained input cannot be reconstructed.

Dataset TEXT/URL seeds use the owning Threat operator, SHA-bound JSONL and
per-row transactions/deduplication. SUSPICIOUS is a content warning only; UNKNOWN
is not a safe verdict, and no seed changes report VERIFIED or fabricates scores.
Dataset source stays internal, not a required UI field. Threat/Search additive
V4 migrations and runtime grants must precede promotion. Roll back code without
dropping these populated additive tables. Deployment/import acceptance remains
separate from passing source tests.

## Dataset binary labels — 2026-11-12 correction
SMS0=legitimate (BENIGN),1=spam/fraud (SUSPICIOUS); URL benign=BENIGN,
phishing=SUSPICIOUS. UNKNOWN is reserved for genuinely unlabelled input.
Lookup displays a dataset label independently of aggregate risk scores and report
verification. A normalized message/domain may combine records: a dangerous label
wins over a benign one; absence of a score does not discard an existing label.
Additive ThreatV6 restores previously seeded normal labels; expand Search enum
support before publishing BENIGN events. No report VERIFIED or score is inferred.
