# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The data gateway of Geoportal (GP-336, ADR-0022): nginx with its JavaScript module (njs), in front of
# Geoportal and GeoServer, on one origin. The configuration is filled from the environment at start
# (templates/default.conf.template); gateway.js asks Geoportal for the go-ahead of data requests and
# keeps it ten seconds.
FROM nginx:1.27.5-alpine
LABEL org.opencontainers.image.title="Geoportal gateway" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/antus/geoportal"
COPY nginx.conf /etc/nginx/nginx.conf
COPY gateway.js /etc/nginx/njs/gateway.js
COPY templates/ /etc/nginx/templates/
# The resolver of the container (Docker or Kubernetes) as NGINX_LOCAL_RESOLVERS, for the names of
# Geoportal and GeoServer, resolved again when they restart.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    GATEWAY_MOSAIKIT=geoportal:8080 \
    GATEWAY_GEOSERVER=geoserver:8080 \
    GATEWAY_GEOSERVER_NETWORK=127.0.0.1/32 \
    GATEWAY_MAX_BODY=1g \
    GATEWAY_SERVICE_KEY=""
EXPOSE 80 8081 8480
