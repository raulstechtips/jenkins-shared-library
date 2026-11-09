package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/** Maps pipeline filenames to job metadata without reading or executing the pipelines. */
class JobDiscovery implements Serializable {
    // Keep the script fixed so adding a job does not require a new script approval.
    // Repository settings and discovered files are data supplied through additionalParameters.
    static final String SCRIPT = '''
folder('Jobs')
jobDefinitions.each { jobSpec ->
    def jobName = 'Jobs/' + jobSpec.name
    def existing = jenkins.model.Jenkins.get().getItemByFullName(jobName)
    def previous = existing ? new XmlParser().parseText(existing.configFile.asString()) : null
    pipelineJob(jobName) {
        displayName(jobSpec.displayName)
        disabled(false)
        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url(repositoryUrl)
                            credentials(scmCredentialsId)
                        }
                        branch('*/main')
                    }
                }
                scriptPath(jobSpec.scriptPath)
                lightweight()
            }
        }
        configure { node ->
            // Keep parameters, options, and tracking actions written by the operational pipeline.
            if (previous != null) {
                ['properties', 'actions'].each { element ->
                    if (previous[element]) {
                        node[element][0].replaceNode(previous[element][0])
                    }
                }
            }
            // Discovery creates manual jobs, including when refreshing an existing definition.
            node.properties[0].children().removeAll {
                it.name() == 'org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty'
            }
        }
    }
}
'''.trim()

    @NonCPS
    static List<Map<String, String>> jobs(List<String> paths) {
        paths.sort(false).collect { String path ->
            if (!(path ==~ /pipelines\/[a-z0-9]+(?:-[a-z0-9]+)*\.Jenkinsfile/)) {
                throw new IllegalArgumentException(
                    "Pipeline filename must use lowercase words separated by hyphens: ${path}")
            }
            String name = path.substring('pipelines/'.length(), path.length() - '.Jenkinsfile'.length())
            [
                name: name,
                displayName: name.split('-').collect { it.capitalize() }.join(' '),
                scriptPath: path
            ]
        }
    }
}
