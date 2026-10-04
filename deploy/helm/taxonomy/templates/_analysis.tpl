{{/* The chart owns these settings so extraEnv cannot change a workload's role. */}}
{{- define "taxonomy.validateAnalysisEnvironment" -}}
{{- $managed := list "TAXONOMY_ANALYSIS_TRANSPORT_MODE" "TAXONOMY_ANALYSIS_RUNTIME_ROLE" "TAXONOMY_ANALYSIS_WORKER_ENABLED" "TAXONOMY_ANALYSIS_WORKER_SHARDS" "TAXONOMY_ANALYSIS_WORKER_CONSUMERS_PER_SHARD" "TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL" "TAXONOMY_ANALYSIS_ARTEMIS_USER" "TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD" "TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS" "TAXONOMY_ANALYSIS_ARTEMIS_DESTINATION_PREFIX" -}}
{{- if eq .Values.analysis.transportMode "artemis" -}}
{{- $managed = concat $managed (list "MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE" "MANAGEMENT_ENDPOINT_HEALTH_GROUP_BROKER_INCLUDE") -}}
{{- end -}}
{{- range $managed -}}
{{- if or (hasKey $.Values.config .) (hasKey $.Values.secretEnv .) -}}
{{- fail (printf "Configure %s through analysis values, not config/secretEnv" .) -}}
{{- end -}}
{{- end -}}
{{- range .Values.extraEnv -}}
{{- if has (.name | default "") $managed -}}{{- fail (printf "extraEnv duplicates analysis-managed setting %s" .name) -}}{{- end -}}
{{- end -}}
{{- end -}}

{{- define "taxonomy.analysisEnvironment" -}}
{{- $worker := .AnalysisWorker | default dict -}}
{{- $role := ternary "worker" .Values.analysis.runtimeRole (not (empty $worker)) }}
- name: TAXONOMY_ANALYSIS_TRANSPORT_MODE
  value: {{ .Values.analysis.transportMode | quote }}
- name: TAXONOMY_ANALYSIS_RUNTIME_ROLE
  value: {{ $role | quote }}
- name: TAXONOMY_ANALYSIS_WORKER_ENABLED
  value: {{ ne $role "coordinator" | quote }}
{{- if not (empty $worker) }}
- name: TAXONOMY_ANALYSIS_WORKER_SHARDS
  value: {{ join "," $worker.shards | quote }}
- name: TAXONOMY_ANALYSIS_WORKER_CONSUMERS_PER_SHARD
  value: {{ $worker.consumersPerShard | quote }}
{{- end }}
{{- if eq .Values.analysis.transportMode "artemis" }}
- name: TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL
  {{- if .Values.analysis.artemis.brokerUrlSecretKey }}
  valueFrom:
    secretKeyRef:
      name: {{ .Values.analysis.artemis.existingSecret | quote }}
      key: {{ .Values.analysis.artemis.brokerUrlSecretKey | quote }}
      optional: false
  {{- else }}
  value: {{ .Values.analysis.artemis.brokerUrl | quote }}
  {{- end }}
{{- range $name, $key := dict "TAXONOMY_ANALYSIS_ARTEMIS_USER" .Values.analysis.artemis.userKey "TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD" .Values.analysis.artemis.passwordKey }}
- name: {{ $name }}
  valueFrom:
    secretKeyRef:
      name: {{ $.Values.analysis.artemis.existingSecret | quote }}
      key: {{ $key | quote }}
      optional: false
{{- end }}
- name: TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS
  value: {{ .Values.analysis.artemis.requireTls | quote }}
- name: TAXONOMY_ANALYSIS_ARTEMIS_DESTINATION_PREFIX
  value: {{ .Values.analysis.artemis.destinationPrefix | quote }}
- name: MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE
  value: {{ ternary "readinessState,analysisBroker" "readinessState,taxonomy" (eq $role "worker") | quote }}
- name: MANAGEMENT_ENDPOINT_HEALTH_GROUP_BROKER_INCLUDE
  value: "analysisBroker"
{{- end }}
{{- end -}}

{{/* The original web Deployment selector is immutable. Workers use a distinct
     application name, so that selector never adopts a worker ReplicaSet. */}}
{{- define "taxonomy.workerSelectorLabels" -}}
app.kubernetes.io/name: {{ printf "%s-worker" (include "taxonomy.name" .Root | trunc 56 | trimSuffix "-") | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/instance: {{ .Root.Release.Name }}
app.kubernetes.io/component: analysis-worker
taxonomy.io/worker-set: {{ .Worker.name | quote }}
{{- end -}}

{{- define "taxonomy.workerName" -}}
{{- printf "%s-worker-%s" (include "taxonomy.fullname" .Root | trunc 35 | trimSuffix "-") .Worker.name -}}
{{- end -}}

{{- define "taxonomy.brokerVolumeMount" -}}
{{- if and (eq .Values.analysis.transportMode "artemis") .Values.analysis.artemis.tlsSecret }}
- name: artemis-tls
  mountPath: {{ .Values.analysis.artemis.tlsMountPath | quote }}
  readOnly: true
{{- end -}}
{{- end -}}

{{- define "taxonomy.brokerVolume" -}}
{{- if and (eq .Values.analysis.transportMode "artemis") .Values.analysis.artemis.tlsSecret }}
- name: artemis-tls
  secret:
    secretName: {{ .Values.analysis.artemis.tlsSecret | quote }}
    defaultMode: 0440
{{- end -}}
{{- end -}}
