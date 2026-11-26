# Do not export the source again: retain the exact reviewed CI web/APK archive.
FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
USER root
RUN apk upgrade --no-cache
USER 101:101
COPY --chown=101:101 web/ /usr/share/nginx/html/
EXPOSE 8080
# Site/proxy config is the rendered ConfigMap; no secret belongs in this image.
