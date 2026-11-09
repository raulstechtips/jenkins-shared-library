# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add `updateInfisicalAuthPipeline()` for manual, selective updates to the existing Infisical credentials Secret and connection. Leave omitted fields unchanged, reject empty requests, and allow the connection name to be selected with `infisical-prod` as the default. The operation does not update Git ([DEVSECOPS-62](https://raulstechtips.atlassian.net/browse/DEVSECOPS-62)).
- Add `discoverJobsPipeline()` to discover `pipelines/*.Jenkinsfile` on `main` and create manual SCM jobs in the root `Jobs` folder. Derive display names from filenames, preserve runtime parameters and build history, disable jobs for removed files, and re-enable them when the files return. The fixed Job DSL script requires initial administrator approval; adding pipeline files does not require another approval ([DEVSECOPS-62](https://raulstechtips.atlassian.net/browse/DEVSECOPS-62)).
- Add the first shared library, including its pipelines, steps, release flow, config validation, and reference README ([DEVSECOPS-34](https://raulstechtips.atlassian.net/browse/DEVSECOPS-34))
- Add the `argo-deploy-targets` config key, so that `argoDeploy` opens one dev deploy PR for each listed Argo file instead of only `argo/dev/<project-name>.yaml` ([DEVSECOPS-47](https://raulstechtips.atlassian.net/browse/DEVSECOPS-47))

### Changed

- Prepare changelogs, chart and application versions, and clean image tags before builds. Bootstrap missing changelogs and publish the release commit and tag before updating cluster-config ([DEVSECOPS-192](https://raulstechtips.atlassian.net/browse/DEVSECOPS-192)).
- Transfer prepared source and Java build outputs to the image builder, run cluster unit tests before image publication, and remove digest pins from packaged and directly installed charts ([DEVSECOPS-192](https://raulstechtips.atlassian.net/browse/DEVSECOPS-192)).

- Resolve `helm-overrides` paths relative to `charts/` and run direct Helm installation from that directory, with override files before the namespace argument. Render and lint keep the combined override order from the repo root ([DEVSECOPS-190](https://raulstechtips.atlassian.net/browse/DEVSECOPS-190))
- `argoDeploy` commits the new `targetRevision` straight to `cluster-config` `main` as the Jenkins bot, one commit for each target and one push, instead of opening a PR for each target. A rejected push is tried again, up to 3 times ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Calculate the build version from the highest `X.Y.Z` Git tag (`0.0.0` when there is no release) and increase the minor number, so the first build is `0.1.0-...`. The version files are no longer read for the build version ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- On every `main` build where `charts/Chart.yaml` exists, commit the build version to `charts/Chart.yaml` as the Jenkins bot and push it to `main`. It has no flag. A failed push only logs a warning ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Skip every `main` build of a commit by the Jenkins bot, instead of only `release:` commits ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Compare `RELEASE_VERSION` with the highest release tag instead of the version file ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Set `gradle.properties` to `0.0.0` ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))

### Fixed

- Set the build version in the local chart before direct Helm installation, and render and lint the combined deployment override files in deployment order ([DEVSECOPS-190](https://raulstechtips.atlassian.net/browse/DEVSECOPS-190))

[Unreleased]: https://github.com/raulstechtips/jenkins-shared-library/commits/main
