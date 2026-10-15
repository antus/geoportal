{{/* SPDX-FileCopyrightText: 2026 Massimo Antonini */}}
{{- define "geoportal.geoserver.name" -}}
{{- printf "%s-geoserver" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "geoportal.labels" -}}
app.kubernetes.io/part-of: geoportal
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "geoportal.loader.name" -}}
{{- printf "%s-loader" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "geoportal.gateway.name" -}}
{{- printf "%s-gateway" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
