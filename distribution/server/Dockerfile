# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Image of Geoportal: the installation next to this directory (../geoportal), with its extensions
# already built into the kernel, so that the container starts at once and can run with a read-only
# root filesystem (the image the Helm chart of geoportal-cloud uses).
FROM eclipse-temurin:25-jre-noble
LABEL org.opencontainers.image.title="Geoportal" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.source="https://github.com/antus/geoportal"
ENV LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
WORKDIR /opt/mosaikit
COPY --chown=185 geoportal/ ./
# The files of organizations (MK-041), in the volume geoportal-files of the stack: the directory of the
# image is owned by root, so it is made here for the user of the kernel.
RUN chmod 755 mosaikit && mkdir -p files && chown 185 files
USER 185
RUN ./mosaikit build
EXPOSE 8080
ENTRYPOINT ["/opt/mosaikit/mosaikit", "start"]
