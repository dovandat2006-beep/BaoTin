FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3
USER root
# Pin the patched Nginx release and apply current Alpine security patches; CI scans
# the exact resulting immutable image. Never suppress HIGH/CRITICAL advisories.
RUN apk upgrade --no-cache
USER 101
COPY web/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=20 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
