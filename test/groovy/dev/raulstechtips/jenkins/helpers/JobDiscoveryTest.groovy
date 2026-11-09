package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class JobDiscoveryTest {
    @Test
    void mapsSortedFilenamesToStableIdsAndReadableNames() {
        List<String> paths = [
            'pipelines/update-infisical-auth.Jenkinsfile',
            'pipelines/check-backup.Jenkinsfile'
        ]
        assertEquals([
            [name: 'check-backup', displayName: 'Check Backup', scriptPath: paths[1]],
            [name: 'update-infisical-auth', displayName: 'Update Infisical Auth', scriptPath: paths[0]]
        ], JobDiscovery.jobs(paths))
        assertEquals('pipelines/update-infisical-auth.Jenkinsfile', paths[0])
    }

    @Test
    void emptyDirectoryProducesNoJobDefinitions() {
        assertEquals([], JobDiscovery.jobs([]))
    }

    @Test
    void rejectsPathsOutsideTheNamingConvention() {
        [
            'other/task.Jenkinsfile',
            'pipelines/nested/task.Jenkinsfile',
            'pipelines/../task.Jenkinsfile',
            'pipelines/.Jenkinsfile',
            'pipelines/Task.Jenkinsfile',
            'pipelines/task--name.Jenkinsfile',
            "pipelines/task');evil().Jenkinsfile",
            'pipelines/task.Jenkinsfile\n'
        ].each { String path ->
            assertThrows(IllegalArgumentException) { JobDiscovery.jobs([path]) }
        }
    }

    @Test
    void refreshPreservesRuntimeParametersAndActionsButRemovesAutomaticTriggers() {
        String previous = '''
<flow-definition>
  <actions><declarative-tracker><parameters><string>CONNECTION</string></parameters></declarative-tracker></actions>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions><hudson.model.StringParameterDefinition>
        <name>CONNECTION</name><defaultValue>infisical-prod</defaultValue>
      </hudson.model.StringParameterDefinition></parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
    <org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty/>
    <org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty>
      <triggers><hudson.triggers.TimerTrigger><spec>H H * * *</spec></hudson.triggers.TimerTrigger></triggers>
    </org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty>
  </properties>
</flow-definition>
'''
        Node generated = renderJobConfiguration(previous)
        Node parameters = generated.properties[0].'hudson.model.ParametersDefinitionProperty'[0]
        assertEquals('CONNECTION', parameters.parameterDefinitions[0].children()[0].name[0].text())
        assertEquals('infisical-prod', parameters.parameterDefinitions[0].children()[0].defaultValue[0].text())
        assertEquals(1, generated.properties[0].
            'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty'.size())
        assertEquals(0, generated.properties[0].
            'org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty'.size())
        assertEquals('CONNECTION', generated.actions[0].'declarative-tracker'[0].parameters[0].string[0].text())
    }

    @Test
    void newJobKeepsEmptyRuntimePropertiesUntilItsFirstManualBuild() {
        Node generated = renderJobConfiguration(null)
        assertTrue(generated.properties[0].children().isEmpty())
        assertTrue(generated.actions[0].children().isEmpty())
    }

    private static Node renderJobConfiguration(String previous) {
        // Only Jenkins item lookup and the DSL builder are mocked. Run the production XML transform.
        GroovyClassLoader loader = new GroovyClassLoader(JobDiscoveryTest.class.classLoader)
        try {
            Class jenkins = loader.parseClass('''
package jenkins.model
class Jenkins {
    static Object instance
    static Object get() { instance }
}
''')
            jenkins.instance = [getItemByFullName: { String name ->
                assertEquals('Jobs/update-infisical-auth', name)
                previous == null ? null : [configFile: [asString: { previous }]]
            }]
            Node generated = new XmlParser().parseText('<flow-definition><actions/><properties/></flow-definition>')
            def context = new Expando()
            context.displayName = { String value -> }
            context.disabled = { boolean value -> }
            context.definition = { Closure body -> }
            context.configure = { Closure body -> body(generated) }
            Binding bindings = new Binding([
                jobDefinitions: JobDiscovery.jobs(['pipelines/update-infisical-auth.Jenkinsfile']),
                folder: { String name -> assertEquals('Jobs', name) },
                pipelineJob: { String name, Closure body ->
                    body.delegate = context
                    body.resolveStrategy = Closure.DELEGATE_FIRST
                    body()
                }
            ])
            new GroovyShell(loader, bindings).evaluate(JobDiscovery.SCRIPT)
            generated
        } finally {
            loader.close()
        }
    }
}
