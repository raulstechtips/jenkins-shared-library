# jenkins-shared-library

Jenkins shared library of CI/CD steps.

## 1. Purpose

This library gives each repo a full CI/CD pipeline from one config file.
The library has two pipelines:

- `clusterConfigurablePipeline()` is for repos that ship a Helm chart and an optional image.
- `javaConfigurablePipeline()` is for Gradle (Java or Groovy) repos.

Each pipeline reads `project-pipeline-config.yaml` from the repo root.
The library validates the file and stops the build on any error.
Flags in the file enable the steps.
The library calculates the version, runs the scans, publishes the artifacts and deploys.
A release run tags and pushes the release commit.

## 2. How a repo uses the library

A repo needs two files in its root.

The `Jenkinsfile` has two lines:

```groovy
@Library('jenkins-shared-library@1.0.0') _
clusterConfigurablePipeline()
```

Use `javaConfigurablePipeline()` for a Gradle repo.
Pin the library to a release tag.
This repo builds itself with `@Library('jenkins-shared-library@main') _` until its first release.

The `project-pipeline-config.yaml` file sets the project data and the flags.
These are the real files of the three consumer repos.

`argocd` (a chart, no image):

```yaml
project-config:
  project-name: argocd
  project-description: "Helm chart that installs Argo CD in the cluster"
  project-namespace: argocd
  publish-targets: [ghcr]
  pipeline-steps:
    helm-test: true
    unit-test: false
    trivy-scan: true
    opengrep-scan: true
    build-docker-image: false
    helm-package-upload: true
    helm-install: false
    helm-clean-install: false
    argo-deploy: true
```

`jenkins` (a chart and an image):

```yaml
project-config:
  project-name: jenkins
  project-description: "Jenkins wrapper chart and baked controller image"
  project-namespace: jenkins
  publish-targets: [ghcr]
  test-command: "bash plugins/lock.sh --check"
  docker-image:
    name: jenkins-controller
    dockerfile: plugins/Dockerfile
    context: plugins
  helm-image-tag-path: .jenkins.controller.image.tag
  pipeline-steps:
    helm-test: true
    unit-test: true
    trivy-scan: true
    opengrep-scan: true
    build-docker-image: true
    helm-package-upload: true
    helm-install: false
    helm-clean-install: false
    argo-deploy: true
```

`jenkins-shared-library` (this repo, the java pipeline):

```yaml
project-config:
  project-name: jenkins-shared-library
  project-description: "Jenkins shared library of CI/CD steps"
  build-command: "./gradlew build -x test"
  test-command: "./gradlew test"
  pipeline-steps:
    trivy-scan: true
    opengrep-scan: true
    build: true
    unit-test: true
```

The tests use copies of these files in `test/resources/fixtures/config/`.

## 3. Config key reference

The root key `project-config` is required.
It's the only root key that's allowed.
All keys are kebab-case.
The table shows each key under `project-config`.

| Key | Type | Default | Rules |
| --- | --- | --- | --- |
| `project-name` | string | none | Required. Must match `^[a-z0-9]([-a-z0-9]*[a-z0-9])?$`. Maximum 53 characters. The library uses it as the Helm release name, the default image name and the Argo file name `argo/dev/<project-name>.yaml`. |
| `project-description` | string | `""` | Optional. The build description shows it. |
| `project-namespace` | string | null | Must match the `project-name` pattern when set. Required when `helm-install` or `helm-clean-install` is true. |
| `publish-targets` | list of `ghcr`, `harbor` | `[]` | No duplicates. Must not be empty when `build-docker-image` or `helm-package-upload` is true. |
| `test-command` | string | null | Required (not blank) when `unit-test` is true. One shell string. The library runs it with `sh` from the repo root. |
| `build-command` | string | null | Java pipeline only. Required (not blank) when `build` is true. One shell string, run with `sh` from the repo root. |
| `helm-overrides` | list of string | `[]` | Each item must be a non-empty path relative to the repo root. The library passes each path as `-f <path>` to `helm upgrade --install`, in list order. |
| `trivy-fail-on` | list of `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `UNKNOWN` | `[]` | An empty list makes the Trivy image and config scans warn only. |
| `opengrep-fail-on` | list of `ERROR`, `WARNING`, `INFO` | `[]` | An empty list makes opengrep warn only. |
| `docker-image` | map | see the child keys | Allowed child keys: `name`, `dockerfile`, `context`. |
| `docker-image.name` | string | value of `project-name` | Must match `^[a-z0-9]+([._-][a-z0-9]+)*$`. The library checks the pattern only when `build-docker-image` is true. |
| `docker-image.dockerfile` | string | `Dockerfile` | Must be a non-empty path relative to the repo root. |
| `docker-image.context` | string | `.` | Must be a non-empty path relative to the repo root. |
| `helm-image-tag-path` | string | `""` | When set, it must start with `.` and `build-docker-image` must be true. Example: `.jenkins.controller.image.tag`. The library writes `<version>@<digest>` to this path in `charts/values.yaml` before `helm package` and before `helm install`. When it's empty, the library writes nothing. |
| `pipeline-steps` | map | all flags `false` | Allowed child keys: the flags of section 4. |
| `pipeline-steps.<flag>` | boolean | `false` | `build` is allowed only in the java pipeline. In the cluster pipeline, `build` is an unknown key. |

A relative path is not blank, doesn't start with `/` and has no `..` part.

### Validation rules

`ConfigValidator` applies these rules:

- An unknown key at any level is an error. Example: `Unknown key 'project-config.pipeline-steps.trivy-scna'. Did you mean 'trivy-scan'?` The suggestion is the known sibling key with the smallest Levenshtein distance. You get a suggestion only when that distance is 3 or less.
- A missing required key is an error: `Missing required key '<path>'.`
- A wrong type is an error that names the key, the expected type and the YAML type found. A quoted `"true"` is a string, not a boolean. A key with an empty value is `null`, and that's a type error too.
- Each item of a list must be a string. An item of `publish-targets`, `trivy-fail-on` or `opengrep-fail-on` must be one of the allowed values.
- The root of the file must be a map.
- The validator collects all errors and reports them together, one line for each error.
- A missing optional key gets its default.
- The result is a new map that holds every key of the table. The `project-config` root key isn't in it.

## 4. Flags and branch rules

Put the flags under `pipeline-steps`. Every flag is `false` by default.

| Flag | Vars that run | Pipelines | Branch rule |
| --- | --- | --- | --- |
| (none, when `charts/Chart.yaml` exists) | `renderChart` | both | all |
| `helm-test` | `helmTest`, which runs `helmDependencyBuild`, `helmLint`, `helmUnitTest` and `kubeconformCheck` | both | all |
| `trivy-scan` | `trivySecretScan` and `trivyDockerfileScan` before the image build, `trivyImageScan` after the image build, `trivyChartScan` after the chart upload | both | all |
| `opengrep-scan` | `opengrepScan` | both | all |
| `build` | `runBuildCommand` | java only | all |
| `unit-test` | `runTestCommand` | both | all |
| `build-docker-image` | `buildDockerImage` | both | all |
| `helm-package-upload` | `helmPackageUpload` | both | all |
| `helm-install` | `helmInstall` | both | Never on `main`. Never in a release run. Skipped when `helm-clean-install` is also true. |
| `helm-clean-install` | `helmCleanInstall` | both | Never on `main`. Never in a release run. |
| `argo-deploy` | `argoDeploy` | both | Only on `main` builds and in release runs. A PR build never deploys. |

## 5. Stage order

### Before the `pipeline {}` block

Both pipelines do these steps first, without a node:

1. Load and validate the config with `loadProjectConfig`.
2. Compose the pod YAML with `agentPodYaml`.
3. Read `RELEASE_VERSION`. When it's set on a branch build or a PR build, the build stops with `RELEASE_VERSION is only allowed on the main job.`

Jenkins shows the `RELEASE_VERSION` parameter only after the first build of the job.

Both pipelines use `agent none`, a 60 minute timeout, no concurrent builds, and keep 20 builds (10 with artifacts).
All stages run inside one `Pipeline` stage on the main pod.
That pod inherits the `generic` template.
After all stages, the build archives `.pipeline/reports/**`.

### clusterConfigurablePipeline

1. `Checkout`. Runs `checkout scm`. Then it applies the release-commit skip rule (see below). Else it calculates the version, sets it as the build name, and runs `releasePipeline.prepare` in a release run.
2. `Render`. Runs when `charts/Chart.yaml` exists. Runs `renderChart`. With `trivy-scan`, it also collects the upstream images from the render.
3. `Early checks`, in parallel: `Trivy secret scan` (`trivy-scan`), `Trivy Dockerfile scan` (`trivy-scan`) and `Opengrep scan` (`opengrep-scan`).
4. `Helm test` (`helm-test`).
5. `Build image` (`build-docker-image`). Runs on a pod from the `builder` template.
6. `Unit test` (`unit-test`). Runs in the `shell` container.
7. `Trivy image scan`. Runs with `trivy-scan` when the build has an image digest or the render has upstream images.
8. `Package and upload chart` (`helm-package-upload`).
9. `Trivy chart scan`. Runs when `trivy-scan` and `helm-package-upload` are both true.
10. `Helm clean install` (`helm-clean-install`) or `Helm install` (`helm-install`). Not on `main`, not in a release run. Runs on a pod from the `deployer` template.
11. `Argo deploy` (`argo-deploy`). Only on a `main` build or in a release run.
12. `Release`. Only in a release run. Runs `releasePipeline.finish`.

### javaConfigurablePipeline

1. `Checkout`. The same as the cluster pipeline. The version file is `gradle.properties`.
2. `Render`. The same as the cluster pipeline.
3. `Early checks`, in parallel: `Trivy scan` (`trivy-scan`, the secret scan and then the Dockerfile scan) and `Opengrep scan` (`opengrep-scan`).
4. `Build` (`build`). Runs `build-command` in the `jdk` container.
5. `Unit test` (`unit-test`). Runs `test-command` in the `jdk` container.
6. `Helm test`, `Build image`, `Trivy image scan`, `Package and upload chart`, `Trivy chart scan`, `Helm clean install` or `Helm install`, `Argo deploy` and `Release`. These have the same rules as in the cluster pipeline.

### Image-scan gate

The `Trivy image scan` stage needs one of two inputs:

- the digest of an image that this build pushed, or
- one or more upstream images in the rendered chart.

When neither exists, the stage is skipped.
For a built image, the scan target is `<image-prefix of the first publish target>/<image name>:<version>@<digest>`.
Images that start with an `image-prefix` of a registry in this library aren't scanned as upstream images.

### Release-commit skip

A release run pushes a commit `release: <v>` to `main`.
That push starts a new `main` build.
The new build must not build the same version again.
So, on a `main` build without `RELEASE_VERSION`, `Checkout` compares the last commit subject with `release: <version file value>`.
When they're equal, the build sets the result `NOT_BUILT` and the description `Release commit <v>: already built by the release run.`
Every later stage is skipped.

### Agent stages

`Build image`, `Helm install` and `Helm clean install` use their own pod.
These stages use `beforeAgent true`, so a skipped stage never starts a pod.
Each of these stages runs its own `checkout scm`.

## 6. Version rules

The version file is:

- `charts/Chart.yaml` (key `version`) for the cluster pipeline;
- `gradle.properties` (line `version=X.Y.Z`) for the java pipeline.

The value must be `X.Y.Z`. Otherwise the build stops with `Version file <path> must contain X.Y.Z`.

A build that isn't a release uses the base `X.Y.(Z+1)`.
The library doesn't read Git tags for this.

The build kind sets the format:

| Build kind | Condition | Format |
| --- | --- | --- |
| PR | `CHANGE_ID` is set | `<base>-<type>-<key>-<head7>-<base7>-<n>` |
| main | `BRANCH_NAME` is `main` | `<base>-main-<sha7>-<n>` |
| branch | any other branch | `<base>-<type>-<key>-<sha7>-<n>` |
| release | `RELEASE_VERSION` is set | `RELEASE_VERSION`, unchanged |

- `<n>` is `BUILD_NUMBER`.
- `<sha7>` is the first 7 characters of `git rev-parse HEAD`.
- A PR build reads `<head7>` and `<base7>` from the PR head and base commits with `scmRevision`. It doesn't use the merge commit. When Jenkins has no PR revision, the build stops with `Cannot read the PR head and base commits from the build.`

A branch name (or `CHANGE_BRANCH` for a PR) must match `^(feature|bugfix|hotfix|release)/([A-Z][A-Z0-9]*-[0-9]+)(-.*)?$`.
The `<type>` is the first group.
The `<key>` is the second group in lower case.
Any other name stops the build with this error: `Branch "<name>" must be <type>/<KEY>-<number>[-text]. The type must be feature, bugfix, hotfix or release. Example: feature/DEVSECOPS-54-argocd-chart.`

Examples:

| Version file | Build | Result |
| --- | --- | --- |
| `1.0.0` | `main`, SHA `0067101`, build 13 | `1.0.1-main-0067101-13` |
| `1.2.0` | branch `feature/DEVSECOPS-70-x`, SHA `e4f5a6b`, build 3 | `1.2.1-feature-devsecops-70-e4f5a6b-3` |
| `1.2.0` | PR from `bugfix/ABC-1`, head `a1b2c3d`, base `9f8e7d6`, build 2 | `1.2.1-bugfix-abc-1-a1b2c3d-9f8e7d6-2` |
| `1.2.0` | branch `wip/test` | error: branch name |
| `1.0` | any | error: version file |
| any | release run with `RELEASE_VERSION=1.3.0` | `1.3.0` |

## 7. Release

### Run a release

1. Make sure `CHANGELOG.md` has at least one `- ` bullet under `## [Unreleased]`.
2. Open the `main` job of the repo in Jenkins.
3. Click **Build with Parameters**.
4. Set `RELEASE_VERSION` to the new version, for example `1.3.0`.
5. Start the build.

### What happens

In `Checkout`, `releasePipeline.prepare` runs in the `tools` container. It does these checks:

- `RELEASE_VERSION` must be `X.Y.Z`.
- `RELEASE_VERSION` must be greater than the version file value.
- The tag must not exist on `origin`.
- `CHANGELOG.md` must exist, and `[Unreleased]` must have entries.

A failed check stops the build with the reason.

Then `prepare` does these steps on the local clone:

1. It moves the `[Unreleased]` entries under `## [<v>] - <YYYY-MM-DD>` (UTC date). Empty subsections of that release are removed.
2. It adds a new empty `[Unreleased]` section with `### Added`, `### Changed` and `### Fixed`.
3. It updates the compare links at the bottom. The previous version is the highest `X.Y.Z` tag on `origin`.
4. It writes `<v>` to the version file.
5. It commits `CHANGELOG.md` and the version file as `release: <v>`. The author is `raulstechtips-jenkins[bot]`.
6. It creates the annotated tag `<v>`.

Nothing is pushed yet.
All later stages use the version `<v>`.
The `Build image`, `Helm install` and `Helm clean install` pods check out the commit before the release commit.
The release commit changes only `CHANGELOG.md` and the version file.

The last stage, `Release`, runs `releasePipeline.finish`.
It pushes `main` and the tag in one atomic push.
This happens only when all earlier stages passed.

### Re-run a failed release

A failed release pushes nothing. The tag doesn't exist on `origin`.

1. Find the cause in the build log.
2. Fix the cause on `main` with a normal PR, if a fix is needed.
3. Start the release again with the same `RELEASE_VERSION`.

When `main` moves during the release build, the atomic push is rejected.
Then the build fails and nothing is tagged.
Run the release again.

## 8. Registries and credentials

`resources/dev/raulstechtips/jenkins/registries.yaml` defines the publish targets.
Each target has `host`, `image-prefix`, `chart-repo` and `credentials-id`.

| Target | Credentials ID | Use |
| --- | --- | --- |
| `ghcr` | `ghcr-push` | Push and pull images and charts on GHCR. |
| `harbor` | `harbor-push` | Push and pull images and charts on Harbor. |
| (none) | `github-app` | Release tag check and push, and the Argo deploy PR to `cluster-config`. |

Each credential is a Jenkins "username with password" credential.
`withRegistryLogin(target) { ... }` binds the credential of a target as `REG_USER` and `REG_PASS`.
Use these variables only inside single-quoted `sh` scripts.
Start each such script with `set +x`.
Never put a secret in a Groovy string.

## 9. Test a library branch from a consumer branch

1. Push your library branch.
2. In the consumer repo, make a branch with a valid name, for example `feature/DEVSECOPS-99-try-lib`.
3. Change the first line of its `Jenkinsfile` to the library branch:

   ```groovy
   @Library('jenkins-shared-library@<branch>') _
   clusterConfigurablePipeline()
   ```

4. Push the consumer branch and look at its Jenkins build.
5. Don't merge this change. Put back the release tag before you merge the consumer branch.

## 10. Add a new step

1. Add one var file, `vars/<stepName>.groovy`, with a `call(Map cfg)` method.
2. Add its test, `test/groovy/vars/<StepName>Test.groovy`. Every var must have a test with this name.
3. Add the flag or key to `ConfigSchema` in `src/dev/raulstechtips/jenkins/config/ConfigSchema.groovy`. Add any cross-key rule to `ConfigValidator`, with a test.
4. Call the step from the pipeline vars in a stage with a `when` that includes `!cfg.skipAll`.
5. Document the key in section 3 and the flag in section 4 of this README.
6. Add a bullet to `CHANGELOG.md` under `[Unreleased]`.

Follow these code rules:

- Keep `src/` classes pure. They don't call Jenkins steps, and they implement `Serializable`.
- Use only Groovy 2.4 syntax.
- A step var doesn't call another step var. The pipelines, `helmTest`, `withRegistryLogin` and `trivyConfigScan` are the exceptions.

### Quoting rule

Each value from the config, a file, tool output or an API goes into `sh` as one POSIX single-quoted word.
This includes paths, names, namespaces, image refs, versions and `yq` paths.
Use `ShellQuote.quote(value)` from `dev.raulstechtips.jenkins.helpers.ShellQuote`.
It writes each embedded `'` as `'\''`.
A null value makes it throw an error.
You can also pass the value with `withEnv` and use `"$VAR"` in a single-quoted script.
Add a test that proves a space, `;`, `$(...)` or a quote in the value stays inert.

## 11. Local commands

The build uses Groovy 2.4.21 and a Java 21 toolchain.

Run the tests:

```sh
./gradlew test
```

Run the full build:

```sh
./gradlew build
```

Use `--no-daemon` when other Gradle builds run on the same machine.
