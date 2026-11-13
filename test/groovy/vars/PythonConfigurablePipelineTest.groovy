package vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class PythonConfigurablePipelineTest extends LanguagePipelineTestSupport {

    private void runPipeline() {
        loadScript('vars/pythonConfigurablePipeline.groovy').call()
    }

    @Test
    void backendConfigBuildsAndTestsInPythonContainerBeforeImageContext() {
        configText = fixture('serenewealth-backend-service')
        runPipeline()

        assertEquals(['Checkout', 'Trivy scan', 'Opengrep scan', 'Build', 'Unit test',
            'Prepare image context', 'Build image', 'Trivy image scan'], leafStages())
        assertEquals('python', observedConfig.type)
        assertEquals('bash scripts/build-image-context.sh', observedConfig['build-command'])
        assertEquals('python', callNamed('runBuildCommand').argument)
        assertEquals('python', callNamed('runTestCommand').argument)
        assertEquals(['runBuildCommand', 'runTestCommand', 'stash', 'buildDockerImage'],
            calls*.name.findAll { it in ['runBuildCommand', 'runTestCommand', 'stash', 'buildDockerImage'] })
        assertEquals([[name: 'image-context', includes: '**/*',
            excludes: '.git,.git/**,.pipeline/**,.venv/**', useDefaultExcludes: false]], stashes)
        assertEquals('harbor.dev.raulstechtips.dev/raulstechtips/serenewealth-backend-service:' +
            '0.1.0-main-0067101-13@' + digest, callNamed('trivyImageScan').argument)
        assertJobStatusSuccess()
    }

    @Test
    void podHasPinnedPythonContainerWithTwoGibibyteLimit() {
        configText = fixture('serenewealth-backend-service')
        runPipeline()

        Map pod = pod()
        assertEquals(['tools', 'trivy', 'python'], pod.spec.containers*.name)
        Map python = pod.spec.containers.find { it.name == 'python' }
        assertEquals(tools().python.image, python.image)
        assertTrue(python.image.startsWith('ghcr.io/astral-sh/uv:'))
        assertTrue(python.image ==~ /.+@sha256:[0-9a-f]{64}/)
        assertEquals([memory: '2Gi'], python.resources.limits)
        assertEquals('/home/jenkins/agent', python.env.find { it.name == 'HOME' }.value)
    }

    @Test
    void buildWithoutBuildCommandErrorsBeforePipeline() {
        configText = 'project-config:\n  project-name: demo\n  pipeline-steps:\n    build: true\n'
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertTrue(failure.message.contains('project-config.build-command'))
        assertNull(declaration)
        assertTrue(calls.empty)
    }
}
