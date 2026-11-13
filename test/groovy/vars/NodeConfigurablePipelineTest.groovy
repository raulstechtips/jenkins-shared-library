package vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class NodeConfigurablePipelineTest extends LanguagePipelineTestSupport {

    private void runPipeline() {
        loadScript('vars/nodeConfigurablePipeline.groovy').call()
    }

    @Test
    void frontEndConfigOnMainBuildsInNodeContainerAndPublishesChart() {
        configText = fixture('serenewealth-front-end')
        chartPresent = true
        runPipeline()

        assertEquals(['Checkout', 'Render', 'Trivy scan', 'Opengrep scan', 'Build', 'Unit test',
            'Helm test', 'Prepare image context', 'Build image', 'Trivy image scan',
            'Package and upload chart', 'Trivy chart scan', 'Update chart version'], leafStages())
        assertEquals('node', observedConfig.type)
        assertEquals('pnpm install --frozen-lockfile --ignore-scripts && pnpm build', observedConfig['build-command'])
        assertEquals('node', callNamed('runBuildCommand').argument)
        assertEquals('node', callNamed('runTestCommand').argument)
        assertEquals([[name: 'image-context', includes: '**/*',
            excludes: '.git,.git/**,.pipeline/**,node_modules/**,.next/cache/**', useDefaultExcludes: false]], stashes)
        assertNull(callNamed('helmInstall'))
        assertJobStatusSuccess()
    }

    @Test
    void frontEndConfigOnBranchInstallsWithHelm() {
        configText = fixture('serenewealth-front-end')
        chartPresent = true
        binding.getVariable('env').BRANCH_NAME = 'feature/SWEALTH-333-x'
        runPipeline()

        assertEquals('helmInstall', calls.last().name)
        assertEquals('Helm install', calls.last().stage)
        assertNull(callNamed('updateChartVersion'))
        assertJobStatusSuccess()
    }

    @Test
    void podHasPinnedNodeContainerWithThreeGibibyteLimit() {
        configText = fixture('serenewealth-front-end')
        chartPresent = true
        runPipeline()

        Map pod = pod()
        assertEquals(['tools', 'helm', 'kubeconform', 'trivy', 'node'], pod.spec.containers*.name)
        Map node = pod.spec.containers.find { it.name == 'node' }
        assertEquals(tools().node.image, node.image)
        assertTrue(node.image.startsWith('node:24.'))
        assertTrue(node.image ==~ /.+@sha256:[0-9a-f]{64}/)
        assertEquals([memory: '3Gi'], node.resources.limits)
        assertEquals([cpu: '10m', memory: '128Mi'], node.resources.requests)
    }
}
