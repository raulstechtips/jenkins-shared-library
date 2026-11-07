# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add the first shared library, including its pipelines, steps, release flow, config validation, and reference README ([DEVSECOPS-34](https://raulstechtips.atlassian.net/browse/DEVSECOPS-34))
- Add the `argo-deploy-targets` config key, so that `argoDeploy` opens one dev deploy PR for each listed Argo file instead of only `argo/dev/<project-name>.yaml` ([DEVSECOPS-47](https://raulstechtips.atlassian.net/browse/DEVSECOPS-47))

### Changed

- `argoDeploy` commits the new `targetRevision` straight to `cluster-config` `main` as the Jenkins bot, one commit for each target and one push, instead of opening a PR for each target. A rejected push is tried again, up to 3 times ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Calculate the build version from the highest `X.Y.Z` Git tag (`0.0.0` when there is no release) and increase the minor number, so the first build is `0.1.0-...`. The version files are no longer read for the build version ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- On every `main` build where `charts/Chart.yaml` exists, commit the build version to `charts/Chart.yaml` as the Jenkins bot and push it to `main`. It has no flag. A failed push only logs a warning ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Skip every `main` build of a commit by the Jenkins bot, instead of only `release:` commits ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Compare `RELEASE_VERSION` with the highest release tag instead of the version file ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))
- Set `gradle.properties` to `0.0.0` ([DEVSECOPS-191](https://raulstechtips.atlassian.net/browse/DEVSECOPS-191))

### Fixed

- Set the build version in the local chart before direct Helm installation, and render and lint the combined deployment override files in deployment order ([DEVSECOPS-190](https://raulstechtips.atlassian.net/browse/DEVSECOPS-190))

[Unreleased]: https://github.com/raulstechtips/jenkins-shared-library/commits/main
