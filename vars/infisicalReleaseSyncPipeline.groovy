import dev.raulstechtips.jenkins.helpers.PodSpecBuilder

/**
 * Runs the job that creates the missing Infisical release branches.
 *
 * MODE next creates only the next release/vX.Y.Z branch after the newest one, for the backfill.
 * MODE all creates every missing branch, oldest first. The MODE default comes from args.mode, so a
 * timer run uses it and a person can still choose the other value. The job runs
 * infisicalReleaseSync on the generic agent with the tools container, and does not wait for the
 * branch builds.
 *
 * @param args a map with mode ('next' or 'all', default 'next') and schedule (a cron spec such as
 *     'TZ=UTC\n0 6 * * *'; empty or absent means no timer)
 * @throws error (fails the build) when args.mode is not 'next' or 'all'
 */
def call(Map args = [:]) {
    String mode = args.mode ?: 'next'
    if (!(mode in ['next', 'all'])) {
        error("infisicalReleaseSyncPipeline mode must be 'next' or 'all'.")
    }
    String schedule = args.schedule ?: ''
    Map tools = readYaml(text: libraryResource('dev/raulstechtips/jenkins/tools.yaml'))
    String podYaml = new PodSpecBuilder().build([type: 'cluster', chartPresent: false, 'pipeline-steps': [:]], tools)

    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            disableConcurrentBuilds()
            timeout(time: 60, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30'))
        }
        triggers {
            cron(schedule)
        }
        parameters {
            choice(name: 'MODE', choices: [mode] + (['next', 'all'] - mode),
                description: 'next: create only the next release branch. all: create every missing release branch.')
        }
        stages {
            stage('Create release branches') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml podYaml
                        yamlMergeStrategy merge()
                    }
                }
                steps {
                    script {
                        List<String> created = infisicalReleaseSync(params.MODE)
                        currentBuild.description = created ? 'Created ' + created.join(', ') : 'Nothing to create'
                    }
                }
            }
        }
    }
}
