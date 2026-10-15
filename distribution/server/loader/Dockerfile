# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The loader of Geoportal (ADR-0019): GDAL in a sidecar, behind the catalog only. The official GDAL
# image, pinned by digest: the flavour alpine-normal, the smallest official one with the LIBKML driver,
# which reads the extended data of KML and KMZ (ADR-0026). loader.py uses its Python bindings and the
# standard library, nothing else.
# The folder of the jobs exists in the image, owned by the loader: the volume of the stack mounted
# there takes its owner (an empty volume on a missing folder belongs to root, and no job could run).
FROM ghcr.io/osgeo/gdal:alpine-normal-3.11.4@sha256:6c433bcde6c9eafb81a3e237da813deb9b85b50fca38950dc67c8322ea86ad98
RUN addgroup -S -g 10001 loader \
    && adduser -S -D -u 10001 -G loader -h /var/lib/loader loader \
    && install -d -o loader -g loader /var/lib/loader/jobs
LABEL org.opencontainers.image.title="Geoportal loader" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/antus/geoportal"
COPY loader.py /opt/loader/loader.py
USER loader
ENV LOADER_PORT=8090 LOADER_WORKDIR=/var/lib/loader/jobs PYTHONUNBUFFERED=1
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=2)"
CMD ["python3", "/opt/loader/loader.py"]
