{{- define "cfpo.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "cfpo.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cfpo.labels" -}}
{{ include "cfpo.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "cfpo.secretName" -}}
{{- default (include "cfpo.fullname" .) .Values.cloudflare.existingSecret -}}
{{- end -}}

{{- define "cfpo.secretKey" -}}
{{- if .Values.cloudflare.existingSecret -}}
{{- .Values.cloudflare.existingSecretKey -}}
{{- else -}}
api-token
{{- end -}}
{{- end -}}
