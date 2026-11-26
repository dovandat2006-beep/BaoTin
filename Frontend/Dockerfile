# syntax=docker/dockerfile:1.7

FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS build
COPY . .
ARG EXPO_PUBLIC_API_URL=""
ENV CI=1 \
    EXPO_NO_TELEMETRY=1 \
    EXPO_PUBLIC_API_URL=$EXPO_PUBLIC_API_URL
RUN npm run typecheck \
    && npm run build:web

FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3 AS runtime
USER root
RUN apk upgrade --no-cache
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY nginx-api-proxy.conf /etc/nginx/baotin-api-proxy.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
