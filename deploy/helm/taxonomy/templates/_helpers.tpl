{{- define "taxonomy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "taxonomy.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "taxonomy.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "taxonomy.labels" -}}
app.kubernetes.io/name: {{ include "taxonomy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "taxonomy.selectorLabels" -}}
app.kubernetes.io/name: {{ include "taxonomy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "taxonomy.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "taxonomy.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "taxonomy.image" -}}
{{- $tag := .Values.image.tag | default "" -}}
{{- $digest := .Values.image.digest | default "" -}}
{{- if and $tag $digest -}}
{{- fail "configure exactly one of image.tag or image.digest, not both" -}}
{{- end -}}
{{- if $digest -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" $digest) -}}
{{- fail "image.digest must use sha256:<64 lowercase hex characters>" -}}
{{- end -}}
{{- printf "%s@%s" .Values.image.repository $digest -}}
{{- else -}}
{{- $requiredTag := required "image.tag or image.digest is required" $tag -}}
{{- $releaseTag := regexMatch "^v[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$" $requiredTag -}}
{{- $commitTag := regexMatch "^sha-[0-9a-f]{7,40}$" $requiredTag -}}
{{- if not (or $releaseTag $commitTag) -}}
{{- fail "image.tag must be an immutable release tag (vX.Y.Z with optional Docker-safe prerelease suffix) or sha-<7-40 lowercase hex commit>" -}}
{{- end -}}
{{- printf "%s:%s" .Values.image.repository $requiredTag -}}
{{- end -}}
{{- end }}

{{- define "taxonomy.metricsSecretName" -}}
{{- default .Values.existingSecret .Values.serviceMonitor.authorization.secretName -}}
{{- end }}
