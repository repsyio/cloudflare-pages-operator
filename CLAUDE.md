# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

cfpo is a Kubernetes operator (Java 21, Java Operator SDK 5.6 on Fabric8 7.8). It publishes a static site from a directory inside a container image to Cloudflare Pages, attaches a custom domain and manages the CNAME record. There is no Spring or Quarkus; the operator is a plain `main` in `CfpoOperator`.

## Commands

```sh
mvn verify                                   # compile, regenerate CRD, unit tests + integration tests
mvn test                                     # unit tests only (surefire)
mvn test -Dtest=CloudflareClientTest         # single test class (add #methodName for one method)
mvn verify -Dit.test=CloudflarePageReconcilerIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false   # only the IT
mvn package -DskipTests                      # shaded jar at target/cfpo.jar

docker build -t cfpo:dev .                   # operator image (multi-stage, runs maven inside)
docker build -t cfpo-deployer:dev deployer/  # wrangler image used by deploy Jobs

# helm is not installed locally; lint/render through a container
docker run --rm -v "$PWD/charts/cfpo:/chart:ro" alpine/helm:3.19.0 lint /chart --set cloudflare.accountId=x --set cloudflare.apiToken=x
```

No formatter or linter plugin is configured.

**Kubernetes safety:** the default kubeconfig context on the development machine may be a production cluster. Don't run `kubectl`/`helm` against the current context unless the user asks. Tests don't need a cluster: `*IT` classes use `@EnableKubeAPIServer` (kube-api-test). It downloads and starts a throwaway kube-apiserver and injects a `KubernetesClient` into a static field, so tests never read kubeconfig. That API server has no controllers, so the IT sets Job status by hand to simulate completion or failure.

## Releases

Pushing a tag `v<version>` runs `.github/workflows/release.yml`. It runs `mvn verify`, then pushes `repo.repsy.io/firat/apps/cfpo:<version>` and `repo.repsy.io/firat/apps/cfpo-deployer:<version>` using the repository secrets `REPSY_USERNAME`/`REPSY_TOKEN`. The workflow fails if the tag doesn't match `appVersion`, because the chart uses `appVersion` as the default operator image tag.

Bump these together in one commit, then tag:
- `charts/cfpo/Chart.yaml`: `version` and `appVersion`
- `charts/cfpo/values.yaml`: `deployer.image`
- `pom.xml`: `<version>` (keep `-SNAPSHOT`)
- the image tags in the `README.md` install commands

The live install is `apps/cfpo.yaml` in `repsyio/apps-firat-apps`. It pins `targetRevision` and `deployer.image`; bump both there once the release workflow has pushed the images.

## Generated CRD

`crd-generator-maven-plugin` writes the CRD from `src/main/java/io/repsy/cfpo/crd/*` directly into `charts/cfpo/crds/cloudflarepages.pages.repsy.io-v1.yml` on every build.
- Never edit that YAML by hand. Change the Java classes instead.
- Validation comes from Fabric8 annotations on the classes: `@Required`, `@Pattern`, `@Size`, `@Default`, `@ValidationRule`, `@PrinterColumn`.
- `@Pattern` regexes are enforced by the API server's RE2 engine, so no lookaheads.
- The integration test loads the CRD from that chart path.
- `@PrinterColumn` emits `priority: 0`, which the API server drops. GitOps tools therefore see a permanent diff on the CRD; the ArgoCD Application in `apps-firat-apps` ignores that field.

## Architecture

**Reconcile flow** (`reconciler/CloudflarePageReconciler`). Each step is idempotent and checks Cloudflare before acting:
1. Ensure the Pages project exists.
2. Ensure the current content is deployed via a Job.
3. Ensure the custom domain is attached. Cloudflare requires this before the CNAME.
4. Ensure the DNS CNAME exists.

Each step records its outcome as a status condition: `Deployed`, `DomainActive`, `DnsConfigured`. `finish()` then derives `phase`, `message`, and the `Ready` condition from them, and picks the requeue interval (30s in progress, 5m failed, `RESYNC_SECONDS` when ready). The reconciler also implements `Cleaner`; JOSDK manages the `pages.repsy.io/finalizer` finalizer.

**Deploy Jobs** (`reconciler/DeployJobFactory`, `reconciler/JobStates`):
- **Where they run:** in the *operator's* namespace, so the token Secret stays there. Owner references can't cross namespaces, so Jobs carry:
  - annotations `pages.repsy.io/owner-namespace` and `owner-name`, which the JOSDK `SecondaryToPrimaryMapper` (`DeployJobFactory::ownerOf`) uses to trigger reconciles;
  - label `pages.repsy.io/owner-uid`, used to delete a resource's Jobs on cleanup.
- **Lookup:** the reconciler fetches a Job from the informer cache by name via `context.getSecondaryResource(Job.class, JOB_EVENT_SOURCE, name, namespace)`.
  - To list a resource's Jobs, use `context.getSecondaryResourcesAsStream(Job.class)`, which goes through the owner-annotation index.
  - Don't use the `(type, eventSourceName)` overload of `getSecondaryResourcesAsStream`. In JOSDK 5.6 it lists the informer cache in the *primary's* namespace, so it never finds deploy Jobs.
- **Pod layout:** an init container runs the *app image* with `sh -c COPY_SCRIPT`, copying `$SOURCE_DIR` into an emptyDir. The directory reaches the script only through the env var, never interpolated into it. The main container runs `wrangler pages deploy` from the deployer image.
- **Versioning:** `Naming.deployHash(image[#revision], directory)` identifies a content version.
  - The Job name embeds the hash.
  - On success the hash goes to `status.deployedHash`.
  - On failure it goes to `status.failedHash` and is **not retried** until the spec changes.
  - Starting a new hash deletes older unfinished Jobs (`stopSupersededJobs`), so a stale upload can't finish last. The IT `newSpecStopsSupersededDeployJob` covers this.
  - A resource whose current hash is already deployed returns before that step, so an old Job that can't finish (e.g. a missing image) is left to `activeDeadlineSeconds` and the Job TTL.

**Ownership safety.** The operator only deletes what it can prove it created:
- **Pages project:** deleted only if `status.projectCreated == true`. A project that already existed on first reconcile is adopted with `projectCreated=false`.
- **DNS records:** identified by the comment `Naming.dnsComment(ns, name)` (`managed-by: cfpo <ns>/<name>`). Any other record on the name is a `DnsConflict` and is never overwritten.
- **Blocked cleanup:** non-transient Cloudflare errors during cleanup keep the finalizer and emit a `CleanupFailed` event. The annotation `pages.repsy.io/keep-on-delete: "true"` skips Cloudflare cleanup entirely.

**Error handling.** `CloudflareApiException.isTransient()` means network failure (status 0), 429 or 5xx.
- **Transient:** errors are rethrown so JOSDK's retry applies.
- **Permanent:** 4xx errors become conditions (e.g. `DomainRejected`), or `updateErrorStatus` marks the resource `Failed` with no retry and a 5-minute reschedule.
- **Not found:** 404 means "not found" and is swallowed by the get/delete helpers.

**Cloudflare client** (`cloudflare/CloudflareClient`). A hand-written `java.net.http` client that unwraps the v4 `{success, errors, result}` envelope into Java records. Zone lookup walks parent domains (`a.b.example.com` → `b.example.com` → `example.com`), filtered by account ID. The base URL is configurable, so tests point it at WireMock.

**Configuration contract.** The operator reads only environment variables (`config/OperatorConfig`). The chart's `templates/deployment.yaml` must stay in sync with the variable names in `OperatorConfig.from()`. The deploy Job gets its token from the same Secret, via `CREDENTIALS_SECRET_NAME`/`CREDENTIALS_SECRET_KEY`. `CfpoOperator.register()` wires the client, config and reconciler, and is shared by `main` and the integration test.

**RBAC** (`charts/cfpo/templates/rbac.yaml`):
- **ClusterRole:** covers `cloudflarepages` (including status and finalizers) and events.
- **Role:** covers Jobs and pods in the operator namespace only.
- **Events verbs:** JOSDK's event recorder GETs an existing event before creating or patching it, so events need `get`, `create`, `patch` and `update`. With fewer verbs every event fails with 403, while reconciling still works.
- **Testing:** the IT client is cluster-admin, so tests can't catch a missing verb. Check a live install with `kubectl auth can-i <verb> <resource> -n <ns> --as system:serviceaccount:<release-ns>:cfpo`.

**Name limits** (`reconciler/Naming`):
- Pages project names: ≤58 chars, `[a-z0-9-]`.
- Job names: ≤63 chars, because they end up in the `job-name` pod label.
- DNS comments: ≤100 chars.

Names over the limit are truncated with a stable hash suffix. Keep these limits in mind when changing naming.

## Known limitations (from README)

- **Shell required:** app images must contain `sh` and `cp`. The copy step runs as uid 65532.
- **Single replica:** one operator replica with the `Recreate` strategy, and no leader election.
