# Reviewed service routing

The Gateway catalog registers the existing Auth8181 and eight backend targets8182..8189.
Registration is private configuration, not automatic publication or API discovery.
Existing Auth routes/transports remain unchanged. The Analytics admin read API is
enabled; Search/Campaign remain scaffolds and publish no business endpoints by default.

An operator-reviewed properties file lists endpoint ID, service, exact path/parameters,
HTTP methods and explicit anonymous access (defaultfalse). Publish/update the matching
OpenAPI contract before enabling an endpoint. Wildcards, duplicate parameter-pattern
aliases, unknown services, cross-service namespaces, auth overrides, management/internal
paths and public inference/reputation routes fail configuration validation.
Literal paths are ordered before parameter patterns so an anonymous generic route cannot
override a protected literal route. Gateway validates JWT at the edge; the service remains
responsible for account/session state, roles, ownership and all business authorization.
`/api/v1/analytics/admin/**` is an enabled admin route covering all four Analytics admin read endpoints
(`GET /api/v1/analytics/admin/summary`, `GET /api/v1/analytics/admin/queue`,
`GET /api/v1/analytics/admin/counts`, and `GET /api/v1/analytics/admin/trends`): the edge requires
`ADMIN|MODERATOR`, forwards the bearer token, and Analytics-Service checks the role again.
The Report-Service `/internal/v1/analytics/**` (`GET /internal/v1/analytics/moderation`) read API used by Analytics is not in
the Gateway catalog and is never publicly routed.

All enabled routes receive bounded bodies, client-header stripping, IP/principal limits
and exact credentialed CORS. Addresses are deployment configuration, never client input.
At the same stable address, a service may stop/restart without Gateway redeployment;
connection failures return generic Problem Details503, and later connections recover.
This is static-address reconnection, not Eureka/Consul discovery or a health registry.

Primary public topology: `https://baotin.rimiprod.me/api/**` reaches Gateway8090 while
the same host's other page/assets reach Expo Web8091. Cloudflare Tunnel must preserve
the `/api/v1/...` path. PostgreSQL/Redis and internal service ports are not tunnel targets.
Public HTTPS origins are exact, without wildcard/credentials/path/query/fragment;
local HTTP origins exist only for development. Browser Web refresh remains Secure,
HttpOnly, session-bound CSRF with exact Origin; no provider code or refresh token is
placed in a frontend URL/log. Provider callback registration is a separate operator step.

Fixtures prove configured HTTPS-origin proxy behavior, not live Cloudflare/DNS/TLS.
See API-Gateway/MANUAL_ROUTES.md for operator setup. Domain/tunnel linking is a later step;
this change does not install cloudflared or create DNS records.
