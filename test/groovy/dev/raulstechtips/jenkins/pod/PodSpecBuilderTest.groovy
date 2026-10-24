package dev.raulstechtips.jenkins.pod

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import groovy.json.JsonSlurper
import static org.junit.jupiter.api.Assertions.*

class PodSpecBuilderTest extends BasePipelineTest {
    private final String toolsText = new File('resources/dev/raulstechtips/jenkins/tools.yaml').text
    private final Map tools = ['tools', 'helm', 'kubeconform', 'trivy', 'jdk'].collectEntries { String name ->
        [(name): [image: "registry.example/${name}:pinned@sha256:${'a' * 64}".toString()]]
    }

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            assertEquals('dev/raulstechtips/jenkins/tools.yaml', path)
            toolsText
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            if (args.text == toolsText) {
                return tools
            }
            return new JsonSlurper().parseText(args.text)
        }
    }

    private Map config(String type, boolean chart, Map steps = [:]) {
        Map flags = [
            'helm-test': false, 'trivy-scan': false, 'opengrep-scan': false,
            'unit-test': false, 'build-docker-image': false,
            'helm-package-upload': false, 'helm-install': false,
            'helm-clean-install': false, 'argo-deploy': false
        ]
        if (type == 'java') {
            flags.build = false
        }
        flags.putAll(steps)
        [type: type, chartPresent: chart, 'pipeline-steps': flags]
    }

    private Map pod(Map cfg) {
        new JsonSlurper().parseText(new PodSpecBuilder().build(cfg, tools))
    }

    @Test
    void allFlagsClusterRoundTripsThroughPipelineReadYaml() {
        Map cfg = config('cluster', true, [
            'helm-test': true, 'trivy-scan': true, 'opengrep-scan': true,
            'unit-test': true, 'build-docker-image': true,
            'helm-package-upload': true, 'helm-install': true,
            'helm-clean-install': true, 'argo-deploy': true
        ])
        def script = loadScript('vars/agentPodYaml.groovy')
        String yaml = script.call(cfg)
        Map result = script.readYaml(text: yaml)
        assertEquals(['tools', 'helm', 'kubeconform', 'trivy'], result.spec.containers*.name)
        assertEquals(pod(cfg), result)
        assertEquals(false, result.spec.automountServiceAccountToken)
        assertEquals([runAsNonRoot: true, runAsUser: 1000], result.spec.securityContext)
        assertTrue(result.spec.containers.every { !(it.name in ['builder', 'deployer', 'jnlp', 'shell']) })
    }

    @Test
    void clusterWithOnlyHelmTestHasNoTrivyOrJdk() {
        assertEquals(['tools', 'helm', 'kubeconform'],
            pod(config('cluster', true, ['helm-test': true])).spec.containers*.name)
    }

    @Test
    void javaAlwaysAddsJdkEvenWhenBuildIsDisabled() {
        assertEquals(['tools', 'jdk'],
            pod(config('java', false, [build: false])).spec.containers*.name)
    }

    @Test
    void noChartExcludesHelmEvenWithHelmTestEnabled() {
        assertEquals(['tools', 'kubeconform', 'trivy'],
            pod(config('cluster', false, ['helm-test': true, 'trivy-scan': true])).spec.containers*.name)
    }

    @Test
    void chartAddsHelmWithoutHelmTestAndToolsIsAlwaysPresent() {
        assertEquals(['tools', 'helm'], pod(config('cluster', true)).spec.containers*.name)
        assertEquals(['tools'], pod(config('cluster', false)).spec.containers*.name)
    }

    @Test
    void everyContainerUsesPinnedImagesSecurityEnvironmentAndResources() {
        Map result = pod(config('java', true, ['helm-test': true, 'trivy-scan': true]))
        assertEquals(['tools', 'helm', 'kubeconform', 'trivy', 'jdk'], result.spec.containers*.name)
        result.spec.containers.each { Map container ->
            assertEquals(tools[container.name].image, container.image)
            assertTrue(container.image ==~ /.+@sha256:[0-9a-f]{64}/)
            assertEquals(['sleep'], container.command)
            assertEquals(['infinity'], container.args)
            assertEquals([runAsUser: 1000], container.securityContext)
            Map expected = [
                HOME: '/home/jenkins/agent',
                XDG_CACHE_HOME: '/home/jenkins/agent/.cache',
                HELM_CACHE_HOME: '/home/jenkins/agent/.helm/cache',
                HELM_CONFIG_HOME: '/home/jenkins/agent/.helm/config',
                HELM_DATA_HOME: '/home/jenkins/agent/.helm/data',
                TRIVY_CACHE_DIR: '/home/jenkins/agent/.cache/trivy',
                GRADLE_USER_HOME: '/home/jenkins/agent/.gradle'
            ]
            if (container.name == 'trivy') {
                expected.TRIVY_DB_REPOSITORY = 'public.ecr.aws/aquasecurity/trivy-db,ghcr.io/aquasecurity/trivy-db'
                expected.TRIVY_JAVA_DB_REPOSITORY = 'public.ecr.aws/aquasecurity/trivy-java-db,ghcr.io/aquasecurity/trivy-java-db'
            }
            assertEquals(expected, container.env.collectEntries { [(it.name): it.value] })
            assertEquals(expected.size(), container.env.size())
            assertEquals([cpu: '100m', memory: '128Mi'], container.resources.requests)
            assertEquals([memory: container.name in ['trivy', 'jdk'] ? '2Gi' : '1Gi'],
                container.resources.limits)
        }
    }
}
