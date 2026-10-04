{{- define "taxonomy.networkEgress" -}}
  {{- $egressMode := .Values.networkPolicy.egressMode | default "restricted" }}
  {{- if eq $egressMode "open" }}
  egress:
    - {}
  {{- else if or .Values.networkPolicy.allowSameNamespaceEgress .Values.networkPolicy.dns.enabled (gt (len .Values.networkPolicy.egress) 0) (and (eq .Values.analysis.transportMode "artemis") (not (empty .Values.analysis.artemis.egress))) }}
  egress:
    {{- if .Values.networkPolicy.allowSameNamespaceEgress }}
    - to:
        - podSelector: {}
    {{- end }}
    {{- if .Values.networkPolicy.dns.enabled }}
    - to:
        - namespaceSelector:
            {{- toYaml .Values.networkPolicy.dns.namespaceSelector | nindent 12 }}
          podSelector:
            {{- toYaml .Values.networkPolicy.dns.podSelector | nindent 12 }}
      ports:
        {{- toYaml .Values.networkPolicy.dns.ports | nindent 8 }}
    {{- end }}
    {{- if eq .Values.analysis.transportMode "artemis" }}
    {{- with .Values.analysis.artemis.egress }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- end }}
    {{- with .Values.networkPolicy.egress }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- else }}
  egress: []
  {{- end }}
{{- end -}}
