# Tailscale/ingress trust and rate limiting

Only actual proxy **peer addresses** belong in `GATEWAY_TRUSTED_PROXY_CIDRS`; default is empty.
Use exact loopback/Docker ingress peers (prefer /32 or /128), not a client tailnet range or all
private networks. Global/broad CIDRs and hostname values are rejected at startup.

Tailscale Serve/ingress must append the genuine caller IP to X-Forwarded-For. Gateway walks
the chain right-to-left only while the current hop is trusted; attacker-provided leftmost
values never bypass the first untrusted real client. Every client IP must be literal-only,
with bounded hop count/length and no reverse DNS. Validate peer/header behavior in deployment
preflight rather than assuming Docker Desktop's host address is stable.

On Docker Desktop, the published-loopback path can alternate IPv4 and IPv6 host peers.
Team Compose accepts optional `GATEWAY_HOST_PROXY_IPV6_CIDR`, one observed host ULA `/128`
only; it is not a tailnet client and must not be a client range. Without that exact peer,
right-to-left traversal correctly stops at the untrusted host hop, aggregating that traffic
under the proxy instead of its actual caller. Revalidate live counters after configuration.

Auth also enforces CORS. Both Auth routes deduplicate identical
`Access-Control-Allow-Origin`/`Access-Control-Allow-Credentials` values (`RETAIN_UNIQUE`),
otherwise a successful backend login has invalid browser CORS. Differing values remain
conflicting/fail-closed; `Set-Cookie` is not deduplicated.

Gateway removes identity/role/Tailscale/internal/forwarded headers and sets a canonical
`X-BaoTin-Client-IP` plus a generated UUID correlation ID. This is metadata, never authentication.
Auth accepts those metadata headers only when its direct peer matches `AUTH_TRUSTED_GATEWAY_CIDRS`.
No other service/client may share that trusted peer identity; restrict network and avoid direct
Auth host exposure. JWT verification and live session authorization remain mandatory.

All Auth writes share one Redis IP bucket (not one per URL), so alternating routes cannot
reset the limit. Configure `GATEWAY_AUTH_WRITE_LIMIT/GATEWAY_RATE_WINDOW_SECONDS`; Auth also
keeps a durable coarse IP/account limiter. Missing Redis/counter DB fails closed.
An additional verified-JWT principal bucket runs after authentication (default 100/10 minutes),
so changing device/IP cannot reset one account's quota; unverified headers/claims never supply its key.
Body size is 64 KiB including chunked transfers; headers are limited to 16 KiB. CORS uses
exact origins; internal/admin-management routes remain unexposed.

Tests cover trusted/untrusted chains, forged internal headers, literal validation, cross-route
rate limit, chunked oversize, Redis outage and actual Web cookie Origin/CSRF rotation at Auth.
