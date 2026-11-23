package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class InfisicalConfigurablePipelineTest extends DeclarativePipelineTest {
    private static final String HEAD = '3e32d0469321c6dc67d7d82976ad965cdfad0e5d'
    private DeclarativePipeline declaration
    private String configText
    private List<Map> calls
    private List<Map> archives
    private Map observedConfig
    private final String digest = 'sha256:' + ('a' * 64)

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        observedConfig = null
        configText = getClass().getResource('/fixtures/config/secrets-manager.yaml').text
        calls = []
        archives = []
        binding.setVariable('env', [BRANCH_NAME: 'release/v0.162.21', CHANGE_ID: null,
            CHANGE_BRANCH: null, BUILD_NUMBER: '13'])
        binding.setVariable('scm', [url: 'mock-scm'])
        helper.registerAllowedMethod('merge', [], { -> 'merge' })
        helper.registerAllowedMethod('error', [String], { String message ->
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException(message)
        })
        helper.registerAllowedMethod('pipeline', [Closure], { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        })
        helper.registerAllowedMethod('readTrusted', [String], { String path ->
            if (path == 'project-pipeline-config.yaml') {
                return configText
            }
            throw new FileNotFoundException(path)
        })
        helper.registerAllowedMethod('readYaml', [Map], { Map args -> new Yaml().load(args.text as String) })
        helper.registerAllowedMethod('libraryResource', [String], { String path ->
            getClass().getResource('/' + path).text
        })
        helper.registerAllowedMethod('checkout', [Map], { Map scm ->
            assertEquals(binding.getVariable('scm'), scm)
            [GIT_COMMIT: HEAD, GIT_URL: 'https://github.com/raulstechtips/secrets-manager.git']
        })
        helper.registerAllowedMethod('stash', [Map], { Map args ->
            assertEquals([name: 'image-context', includes: '**/*',
                excludes: '.git,.git/**,.pipeline/**', useDefaultExcludes: false], args)
            record('stash', [:])
        })
        helper.registerAllowedMethod('archiveArtifacts', [Map], { Map args -> archives << args })
        helper.registerAllowedMethod('logRotator', [Map], { Map args -> args })
        helper.registerAllowedMethod('buildDiscarder', [Map], { Map args -> })
        Script configLoader = loadScript('vars/loadProjectConfig.groovy')
        Script podBuilder = loadScript('vars/agentPodYaml.groovy')
        helper.registerAllowedMethod('loadProjectConfig', [Map], configLoader.&call)
        helper.registerAllowedMethod('agentPodYaml', [Map], podBuilder.&call)
        helper.registerAllowedMethod('unpinAptPackages', [Map, List], { Map cfg, List keep ->
            record('unpinAptPackages', cfg, keep)
            []
        })
        helper.registerAllowedMethod('trivyDockerfileScan', [Map], { Map cfg -> record('trivyDockerfileScan', cfg) })
        helper.registerAllowedMethod('buildDockerImage', [Map], { Map cfg ->
            record('buildDockerImage', cfg)
            digest
        })
        helper.registerAllowedMethod('trivyImageScan', [Map, String], { Map cfg, String ref ->
            record('trivyImageScan', cfg, ref)
        })
    }

    @Test
    void releaseBranchBuildsAndScansTheImage() {
        runPipeline()
        assertEquals(['Checkout', 'Unpin apt packages', 'Trivy Dockerfile scan', 'Prepare image context',
            'Build image', 'Trivy image scan'], leafStages())
        assertEquals(['unpinAptPackages', 'trivyDockerfileScan', 'stash', 'buildDockerImage', 'trivyImageScan'],
            calls*.name)
        assertEquals(['infisical'], callNamed('unpinAptPackages').argument)
        assertEquals('v0.162.21-release-3e32d04-13', observedConfig.version)
        assertEquals('v0.162.21-release-3e32d04-13', binding.getVariable('currentBuild').displayName)
        assertEquals(HEAD, observedConfig.gitCommit)
        assertEquals('https://github.com/raulstechtips/secrets-manager.git', observedConfig.gitUrl)
        assertEquals(['INFISICAL_PLATFORM_VERSION=${VERSION}', 'DD_GIT_COMMIT_SHA=${GIT_COMMIT}',
            'DD_GIT_REPOSITORY_URL=${GIT_URL}'], observedConfig['docker-image']['build-args'])
        assertEquals('ghcr.io/raulstechtips/secrets-manager:v0.162.21-release-3e32d04-13@' + digest,
            callNamed('trivyImageScan').argument)
        assertEquals([[artifacts: '.pipeline/reports/**', allowEmptyArchive: true]], archives)
        assertTrue(helper.callStack.any { it.methodName == 'timeout' && it.args[0] == [time: 180, unit: 'MINUTES'] })
        assertJobStatusSuccess()
    }

    @Test
    void imageBuildRunsOnBuilderWithLargerKanikoMemory() {
        runPipeline()
        def pipeline = declaration.stages['Pipeline']
        assertEquals('generic', pipeline.agent.kubernetes.inheritFrom)
        Map pod = new Yaml().load(pipeline.agent.kubernetes.yaml)
        assertEquals(['tools', 'trivy'], pod.spec.containers*.name)
        def builder = pipeline.stages['Build image']
        assertEquals('builder-large', builder.agent.kubernetes.inheritFrom)
        assertNull(builder.agent.kubernetes.yaml)
        assertEquals(Boolean.TRUE, builder.when.beforeAgent)
    }

    @Test
    void otherBranchFailsAtCheckout() {
        binding.getVariable('env').BRANCH_NAME = 'main'
        assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals(['Checkout'], leafStages())
        assertTrue(calls.empty)
        assertJobStatusFailure()
    }

    @Test
    void pullRequestFailsAtCheckout() {
        binding.getVariable('env').putAll([BRANCH_NAME: 'PR-4', CHANGE_ID: '4', CHANGE_BRANCH: 'release/v0.162.21'])
        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertTrue(failure.message.contains('Pull requests are not built'))
        assertTrue(calls.empty)
    }

    @Test
    void scanFlagOffSkipsBothScans() {
        configText = configText.replace('trivy-scan: true', 'trivy-scan: false')
        runPipeline()
        assertEquals(['unpinAptPackages', 'stash', 'buildDockerImage'], calls*.name)
    }

    private void runPipeline() {
        loadScript('vars/infisicalConfigurablePipeline.groovy').call()
    }

    private void record(String name, Map cfg, Object argument = null) {
        if (cfg) {
            observedConfig = cfg
        }
        calls << [name: name, argument: argument]
    }

    private Map callNamed(String name) { calls.find { it.name == name } }

    private List<String> leafStages() {
        helper.callStack.findAll { it.methodName == 'stage' }
            .collect { it.args[0].toString() }.findAll { it != 'Pipeline' }
    }
}
