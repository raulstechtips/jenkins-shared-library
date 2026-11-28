package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class ReleaseConfigurablePipelineTest extends DeclarativePipelineTest {
    private DeclarativePipeline declaration
    private List<Map> calls

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        calls = []
        binding.setVariable('env', [BRANCH_NAME: 'main', CHANGE_ID: null, BUILD_NUMBER: '4'])
        binding.setVariable('params', [RELEASE_VERSION: ''])
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
            fail("The release pipeline must not read ${path}")
        })
        helper.registerAllowedMethod('readYaml', [Map], { Map args -> new Yaml().load(args.text as String) })
        helper.registerAllowedMethod('libraryResource', [String], { String path ->
            getClass().getResource('/' + path).text
        })
        helper.registerAllowedMethod('checkout', [Map], { Map scm ->
            assertEquals(binding.getVariable('scm'), scm)
            calls << [name: 'checkout']
            [GIT_COMMIT: 'c' * 40]
        })
        helper.registerAllowedMethod('logRotator', [Map], { Map args -> args })
        helper.registerAllowedMethod('buildDiscarder', [Map], { Map args -> })
        // Real pod builder: the release pod must hold only the tools container.
        Script podBuilder = loadScript('vars/agentPodYaml.groovy')
        helper.registerAllowedMethod('agentPodYaml', [Map], podBuilder.&call)
        binding.setVariable('releasePipeline', [
            prepare: { Map cfg -> calls << [name: 'prepare', cfg: cfg] },
            publish: { Map cfg -> calls << [name: 'publish', cfg: cfg] }
        ])
    }

    @Test
    void releaseOnMainChecksOutPreparesAndPublishesOnToolsOnlyPod() {
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'
        runPipeline()

        assertEquals(['checkout', 'prepare', 'publish'], calls*.name)
        Map cfg = calls[1].cfg
        assertSame(cfg, calls[2].cfg)
        assertEquals('1.0.0', cfg.releaseVersion)
        assertFalse(cfg.chartPresent as boolean)
        assertNotEquals('java', cfg.type)
        assertEquals('1.0.0', binding.getVariable('currentBuild').displayName)

        def stage = declaration.stages['Release']
        assertEquals(Boolean.TRUE, stage.when.beforeAgent)
        assertEquals('generic', stage.agent.kubernetes.inheritFrom)
        assertEquals('merge', stage.agent.kubernetes.yamlMergeStrategy)
        Map pod = new Yaml().load(stage.agent.kubernetes.yaml)
        assertEquals(['tools'], pod.spec.containers*.name)
        assertEquals('none', declaration.agent.label)
        assertEquals(1, agentEntries().size())
        assertJobStatusSuccess()
    }

    @Test
    void mainPushWithoutReleaseVersionDoesNothingAndStartsNoAgent() {
        runPipeline()

        assertTrue(calls.empty)
        assertTrue(agentEntries().empty)
        assertEquals('No RELEASE_VERSION: nothing to release.',
            binding.getVariable('currentBuild').description)
        assertJobStatusSuccess()
    }

    @Test
    void pullRequestBuildDoesNothingAndStartsNoAgent() {
        binding.getVariable('env').putAll([BRANCH_NAME: 'PR-3', CHANGE_ID: '3'])
        runPipeline()

        assertTrue(calls.empty)
        assertTrue(agentEntries().empty)
        assertJobStatusSuccess()
    }

    @Test
    void anyBranchNameIsAcceptedForTheNoOpBuild() {
        binding.getVariable('env').BRANCH_NAME = 'DEVSECOPS-131-no-type-prefix'
        runPipeline()

        assertTrue(calls.empty)
        assertJobStatusSuccess()
    }

    @Test
    void releaseVersionOnBranchFailsBeforePipeline() {
        binding.getVariable('env').BRANCH_NAME = 'feature/DEVSECOPS-219-x'
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'

        def failure = assertThrows(IllegalArgumentException) { runPipeline() }
        assertEquals('RELEASE_VERSION is only allowed on the main job.', failure.message)
        assertNull(declaration)
        assertTrue(calls.empty)
    }

    @Test
    void releaseVersionOnPullRequestFailsBeforePipeline() {
        binding.getVariable('env').putAll([BRANCH_NAME: 'main', CHANGE_ID: '3'])
        binding.getVariable('params').RELEASE_VERSION = '1.0.0'

        assertThrows(IllegalArgumentException) { runPipeline() }
        assertNull(declaration)
        assertTrue(calls.empty)
    }

    @Test
    void declaresReleaseVersionParameterAndSerialBuilds() {
        runPipeline()

        assertNotNull(declaration.params)
        assertTrue(helper.callStack.any { it.methodName == 'disableConcurrentBuilds' })
        assertTrue(helper.callStack.any { it.methodName == 'skipDefaultCheckout' })
    }

    private void runPipeline() {
        loadScript('vars/releaseConfigurablePipeline.groovy').call()
    }

    private List<String> agentEntries() {
        helper.callStack.findAll { it.methodName == 'echo' }
            .collect { it.args[0].toString() }.findAll { it.startsWith('Executing on agent [kubernetes:') }
    }
}
