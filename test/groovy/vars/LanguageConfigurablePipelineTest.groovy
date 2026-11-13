package vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class LanguageConfigurablePipelineTest extends LanguagePipelineTestSupport {

    @Test
    void usesTheTypeContainerAndExcludesItIsGiven() {
        configText = 'project-config:\n  project-name: demo\n  build-command: make\n' +
            '  test-command: make test\n  publish-targets: [ghcr]\n  pipeline-steps:\n' +
            '    build: true\n    unit-test: true\n    build-docker-image: true\n'
        loadScript('vars/languageConfigurablePipeline.groovy')
            .call(type: 'node', container: 'custom', imageContextExcludes: 'skip/**')

        assertEquals('node', observedConfig.type)
        assertEquals(['custom', 'custom'],
            calls.findAll { it.name in ['runBuildCommand', 'runTestCommand'] }*.argument)
        assertEquals('skip/**', stashes[0].excludes)
        assertEquals(['tools', 'node'], pod().spec.containers*.name)
        assertJobStatusSuccess()
    }

    @Test
    void releaseVersionOnBranchErrorsBeforePipeline() {
        configText = 'project-config:\n  project-name: demo\n'
        binding.getVariable('env').BRANCH_NAME = 'feature/SWEALTH-333-x'
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        def failure = assertThrows(IllegalArgumentException) {
            loadScript('vars/languageConfigurablePipeline.groovy')
                .call(type: 'python', container: 'python', imageContextExcludes: '.git/**')
        }
        assertEquals('RELEASE_VERSION is only allowed on the main job.', failure.message)
        assertNull(declaration)
    }

    @Test
    void misspelledBuildFlagSuggestsBuildForPython() {
        configText = 'project-config:\n  project-name: demo\n  pipeline-steps:\n    biuld: true\n'
        def failure = assertThrows(IllegalArgumentException) {
            loadScript('vars/languageConfigurablePipeline.groovy')
                .call(type: 'python', container: 'python', imageContextExcludes: '.git/**')
        }
        assertTrue(failure.message.contains("Did you mean 'build'?"), failure.message)
        assertNull(declaration)
    }
}
