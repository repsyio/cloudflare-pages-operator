# cfpo — Cloudflare Pages Operator

A Kubernetes operator, written in Java with the [Java Operator SDK](https://javaoperatorsdk.io), that publishes static sites to [Cloudflare Pages](https://pages.cloudflare.com).

You install the operator once, with a Cloudflare API token. After that, an application chart only has to say **which image** holds the built site, **which directory** inside it to publish, and **which domain** to serve it on:

```yaml
apiVersion: pages.repsy.io/v1alpha1
kind: CloudflarePage
metadata:
  name: my-frontend
spec:
  image: registry.example.com/my-frontend:1.0.0
  directory: /usr/share/nginx/html
  domain: app.example.com
```

For each `CloudflarePage` the operator:

1. Creates the Pages project if it doesn't exist. The name defaults to `<namespace>-<name>`.
2. Runs a deploy Job in the operator's namespace. An init container copies `directory` out of `image`, then `wrangler pages deploy` uploads it.
3. Adds `domain` as a custom domain on the project.
4. Creates a proxied `CNAME domain -> <project>.pages.dev` record, provided the domain's zone is in the same Cloudflare account.
5. Reports progress in `status` (phase, conditions, URLs) and as Kubernetes events.

Changing `image`, `directory` or `revision` triggers a new deploy. Deleting the resource removes the DNS record, the custom domain and the project the operator created.

## Install

### 1. Build the images

```sh
docker build -t <registry>/cfpo:0.1.2 .
docker build -t <registry>/cfpo-deployer:0.1.2 deployer/
docker push <registry>/cfpo:0.1.2
docker push <registry>/cfpo-deployer:0.1.2
```

### 2. Create an API token

In the Cloudflare dashboard, go to **My Profile → API Tokens** and create a token with these permissions:

| Scope   | Permission       | Access |
|---------|------------------|--------|
| Account | Cloudflare Pages | Edit   |
| Zone    | DNS              | Edit   |
| Zone    | Zone             | Read   |

Limit the zone permissions to the zones you want the operator to manage.

### 3. Install the chart

```sh
helm install cfpo charts/cfpo -n cfpo-system --create-namespace \
  --set image.repository=<registry>/cfpo \
  --set deployer.image=<registry>/cfpo-deployer:0.1.2 \
  --set cloudflare.accountId=<account-id> \
  --set cloudflare.apiToken=<token>
```

To keep the token out of Helm values, create the Secret yourself and pass `--set cloudflare.existingSecret=<name>` (key `api-token`, configurable with `cloudflare.existingSecretKey`).

If application images live in a private registry, create a pull secret **in the operator namespace** and list it in `deployJob.imagePullSecrets`. Deploy Jobs run there, not in the application namespace.

Other useful values: `watchNamespaces`, `resyncIntervalSeconds`, `deployJob.activeDeadlineSeconds`, `deployJob.resources`. See [`charts/cfpo/values.yaml`](charts/cfpo/values.yaml).

## Use it from an application chart

See [`examples/app-chart-snippet.yaml`](examples/app-chart-snippet.yaml):

```yaml
apiVersion: pages.repsy.io/v1alpha1
kind: CloudflarePage
metadata:
  name: {{ .Release.Name }}
spec:
  image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
  directory: {{ .Values.cloudflarePages.directory | quote }}
  domain: {{ .Values.cloudflarePages.domain | quote }}
```

### Spec

| Field         | Required | Description |
|---------------|----------|-------------|
| `image`       | yes      | Image that contains the built site. |
| `directory`   | yes      | Absolute path inside the image to publish. |
| `domain`      | yes      | Custom domain. Changing it detaches the old domain and its DNS record. |
| `projectName` | no       | Pages project name. Defaults to `<namespace>-<name>`, shortened with a hash if longer than 58 characters. Immutable. |
| `branch`      | no       | Production branch name (default `main`). |
| `revision`    | no       | Change it to redeploy the same image, for example with a mutable tag. |

Annotation `pages.repsy.io/keep-on-delete: "true"` leaves the project, domain and DNS record in Cloudflare when the resource is deleted.

### Status

```sh
$ kubectl get cloudflarepages -A
NAMESPACE   NAME          DOMAIN            PHASE   URL                       AGE
apps        my-frontend   app.example.com   Ready   https://app.example.com   5m
```

| Phase       | Meaning |
|-------------|---------|
| `Deploying` | A deploy Job for the current spec is running. |
| `Pending`   | Content is deployed and Cloudflare is still validating the domain or issuing the certificate. |
| `Ready`     | Content is deployed and the domain is active. |
| `Failed`    | See `status.message` and the conditions below. |

Conditions: `Deployed`, `DomainActive`, `DnsConfigured`, `Ready`. Deploy failures include the output of the failing container.

## Behaviour notes and limitations

- **Images need `sh` and `cp`.** The copy step runs `sh -c 'cp -R …'` inside your image as a non-root user (uid 65532). Distroless and `scratch` images won't work, and neither will files readable only by root.
- **A failed deploy isn't retried** until the spec changes. Bump `revision` to retry the same image.
- **Existing projects are adopted, not owned.** If the project already exists the first time a resource is reconciled, the operator uses it but never deletes it. `status.projectCreated` shows which case applies.
- **DNS records are only touched when the operator created them.** It recognises its own records by the comment `managed-by: cfpo <namespace>/<name>`. If another record already exists for the domain, the resource reports `DnsConflict` and nothing is overwritten.
- **Domains outside the account:** if the zone isn't in the account, `DnsConfigured` explains which CNAME to create with your DNS provider. The domain becomes active once that record exists.
- **Cleanup failures block deletion.** If Cloudflare refuses a cleanup step, for example a project with too many deployments, the finalizer stays and a `CleanupFailed` event explains why. Set the keep-on-delete annotation to finish deleting without cleanup.
- **Single replica.** The chart runs one operator replica with the `Recreate` strategy.

## Development

```sh
mvn verify
```

This runs:

- **Unit tests:** naming rules, Job construction, the Cloudflare client against WireMock.
- **Integration tests (`*IT`):** these start a throwaway `kube-apiserver` with [kube-api-test](https://github.com/fabric8io/kubernetes-client/tree/main/junit/kube-api-test). The first run downloads the binaries. The tests never use your kubeconfig.

Pushing a `v*` tag runs `.github/workflows/release.yml`. It runs `mvn verify`, then pushes `repo.repsy.io/firat/apps/cfpo:<version>` and `cfpo-deployer:<version>` using the repository secrets `REPSY_USERNAME`/`REPSY_TOKEN`. The tag without its `v` must equal `appVersion` in `charts/cfpo/Chart.yaml`, because the chart uses that as the default image tag.

Building also regenerates the CRD from the Java classes into `charts/cfpo/crds/`, so commit that file together with changes to `src/main/java/io/repsy/cfpo/crd`.

Code layout:

| Path | Purpose |
|------|---------|
| `crd/` | `CloudflarePage` spec and status (source of the CRD). |
| `reconciler/CloudflarePageReconciler` | Reconcile and cleanup logic. |
| `reconciler/DeployJobFactory` | The deploy Job. |
| `cloudflare/CloudflareClient` | Minimal Cloudflare v4 API client. |
| `config/OperatorConfig` | Environment-based configuration. |

## License

[Apache License 2.0](LICENSE)
