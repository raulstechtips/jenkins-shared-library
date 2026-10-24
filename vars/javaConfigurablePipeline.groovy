import dev.raulstechtips.jenkins.helpers.RegistryCatalog
import dev.raulstechtips.jenkins.helpers.SemVer
import dev.raulstechtips.jenkins.helpers.VersionCalculator

/**
 * Runs the declarative pipeline for a java repository.
 *
 * It loads project-pipeline-config.yaml as type java, builds the agent pod with agentPodYaml, and
 * runs the stages that pipeline-steps turns on: checkout and version, chart render, the Trivy
 * secret and Dockerfile scans in parallel with the opengrep scan, build, unit test, Helm test,
 * image build, Trivy image scan, chart package and upload, Trivy chart scan, Helm clean install or
 * Helm install, Argo deploy, and release. Most stages run on the generic agent merged with the
 * library pod. Image build runs on the builder agent and the Helm installs on the deployer agent.
 * Build and unit test run build-command and test-command in the jdk container. The Helm installs
 * never run on main or in a release run, and Argo deploy runs only on a main push or in a release
 * run. A main push whose last commit is "release: VERSION" is marked NOT_BUILT and skips every
 * stage. Files under .pipeline/reports are archived. The build times out after 60 minutes and
 * builds do not run concurrently.
 *
 * The RELEASE_VERSION parameter (X.Y.Z, main only) starts a release run through releasePipeline.
 *
 * @throws error (fails the build) when RELEASE_VERSION is set on a branch other than main or on a
 *     pull request, or when gradle.properties has no X.Y.Z version on a main push. Each step raises its
 *     own errors.
 */
def call() {
    def cfg = loadProjectConfig(type: 'java')
    cfg.podYaml = agentPodYaml(cfg)
    cfg.upstreamImages = []
    cfg.releaseVersion = params.RELEASE_VERSION ?: ''
    if (cfg.releaseVersion && (env.BRANCH_NAME != 'main' || env.CHANGE_ID)) {
        error('RELEASE_VERSION is only allowed on the main job.')
    }

    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            timeout(time: 60, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeep: '20', artifactNumToKeep: '10'))
        }
        parameters {
            string(name: 'RELEASE_VERSION', defaultValue: '',
                description: 'X.Y.Z. Only on main. Empty = normal build.')
        }
        stages {
            stage('Pipeline') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml cfg.podYaml
                        yamlMergeStrategy merge()
                    }
                }
                stages {
                    stage('Checkout') {
                        steps {
                            checkout scm
                            script {
                                cfg.skipAll = false
                                currentBuild.description = cfg['project-description']
                                if (!cfg.releaseVersion && env.BRANCH_NAME == 'main' && !env.CHANGE_ID) {
                                    String subject = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
                                    String version = VersionCalculator.gradleVersion(readFile('gradle.properties'))
                                    try {
                                        SemVer.parse(version)
                                    } catch (IllegalArgumentException ignored) {
                                        error('Version file gradle.properties must contain X.Y.Z')
                                    }
                                    if (subject == 'release: ' + version) {
                                        cfg.skipAll = true
                                        currentBuild.result = 'NOT_BUILT'
                                        currentBuild.description = "Release commit ${version}: already built by the release run."
                                    }
                                }
                                if (!cfg.skipAll) {
                                    cfg.version = computeVersion(cfg)
                                    currentBuild.displayName = cfg.version
                                    if (cfg.releaseVersion) {
                                        releasePipeline.prepare(cfg)
                                    }
                                }
                            }
                        }
                    }
                    stage('Render') {
                        when {
                            expression { !cfg.skipAll && cfg.chartPresent }
                        }
                        steps {
                            renderChart(cfg)
                            script {
                                if (cfg['pipeline-steps']['trivy-scan']) {
                                    cfg.upstreamImages = trivyImageScan.upstreamImages(cfg)
                                }
                            }
                        }
                    }
                    stage('Early checks') {
                        when {
                            expression { !cfg.skipAll }
                        }
                        parallel {
                            stage('Trivy scan') {
                                when {
                                    expression { !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] }
                                }
                                steps {
                                    trivySecretScan(cfg)
                                    trivyDockerfileScan(cfg)
                                }
                            }
                            stage('Opengrep scan') {
                                when {
                                    expression { !cfg.skipAll && cfg['pipeline-steps']['opengrep-scan'] }
                                }
                                steps {
                                    opengrepScan(cfg)
                                }
                            }
                        }
                    }
                    stage('Build') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['build'] }
                        }
                        steps {
                            runBuildCommand(cfg, 'jdk')
                        }
                    }
                    stage('Unit test') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['unit-test'] }
                        }
                        steps {
                            runTestCommand(cfg, 'jdk')
                        }
                    }
                    stage('Helm test') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['helm-test'] }
                        }
                        steps {
                            helmTest(cfg)
                        }
                    }
                    stage('Build image') {
                        when {
                            beforeAgent true
                            expression { !cfg.skipAll && cfg['pipeline-steps']['build-docker-image'] }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'builder'
                            }
                        }
                        steps {
                            script {
                                cfg.digest = buildDockerImage(cfg)
                            }
                        }
                    }
                    stage('Trivy image scan') {
                        when {
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                                    (cfg.digest || cfg.upstreamImages)
                            }
                        }
                        steps {
                            script {
                                String builtImageRef = null
                                if (cfg.digest) {
                                    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
                                    Map target = new RegistryCatalog(registries).target(cfg['publish-targets'][0])
                                    builtImageRef = target['image-prefix'] + '/' + cfg['docker-image'].name +
                                        ':' + cfg.version + '@' + cfg.digest
                                }
                                trivyImageScan(cfg, builtImageRef)
                            }
                        }
                    }
                    stage('Package and upload chart') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['helm-package-upload'] }
                        }
                        steps {
                            helmPackageUpload(cfg, cfg.digest)
                        }
                    }
                    stage('Trivy chart scan') {
                        when {
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                                    cfg['pipeline-steps']['helm-package-upload']
                            }
                        }
                        steps {
                            trivyChartScan(cfg)
                        }
                    }
                    stage('Helm clean install') {
                        when {
                            beforeAgent true
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['helm-clean-install'] &&
                                    env.BRANCH_NAME != 'main' && !cfg.releaseVersion
                            }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'deployer'
                            }
                        }
                        steps {
                            helmCleanInstall(cfg, cfg.digest)
                        }
                    }
                    stage('Helm install') {
                        when {
                            beforeAgent true
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['helm-install'] &&
                                    !cfg['pipeline-steps']['helm-clean-install'] &&
                                    env.BRANCH_NAME != 'main' && !cfg.releaseVersion
                            }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'deployer'
                            }
                        }
                        steps {
                            helmInstall(cfg, cfg.digest)
                        }
                    }
                    stage('Argo deploy') {
                        when {
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['argo-deploy'] &&
                                    ((env.BRANCH_NAME == 'main' && !env.CHANGE_ID) || cfg.releaseVersion)
                            }
                        }
                        steps {
                            argoDeploy(cfg)
                        }
                    }
                    stage('Release') {
                        when {
                            expression { !cfg.skipAll && cfg.releaseVersion }
                        }
                        steps {
                            script {
                                releasePipeline.finish(cfg)
                            }
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts(artifacts: '.pipeline/reports/**', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
