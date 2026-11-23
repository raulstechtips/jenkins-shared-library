package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class InfisicalReleaseSyncPipelineTest extends DeclarativePipelineTest {
    private DeclarativePipeline declaration
    private List<String> modes
    private List<String> crons

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        modes = []
        crons = []
        helper.registerAllowedMethod('cron', [String]) { String spec -> crons << spec }
        helper.registerAllowedMethod('error', [String]) { String message -> throw new IllegalArgumentException(message) }
        binding.setVariable('params', [:])
        helper.registerAllowedMethod('merge', [], { -> 'merge' })
        helper.registerAllowedMethod('pipeline', [Closure]) { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        }
        helper.registerAllowedMethod('libraryResource', [String]) { String path -> getClass().getResource('/' + path).text }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> new Yaml().load(args.text as String) }
        helper.registerAllowedMethod('logRotator', [Map]) { Map args -> args }
        helper.registerAllowedMethod('buildDiscarder', [Map]) { Map args -> }
        helper.registerAllowedMethod('infisicalReleaseSync', [String]) { String mode ->
            modes << mode
            ['v0.162.22']
        }
    }

    @Test
    void runsTheSyncWithTheDefaultModeOnTheToolsPod() {
        loadScript('vars/infisicalReleaseSyncPipeline.groovy').call()
        assertEquals('next', binding.getVariable('params').MODE)
        assertEquals(['next'], modes)
        assertEquals('Created v0.162.22', binding.getVariable('currentBuild').description)
        def agent = declaration.stages['Create release branches'].agent.kubernetes
        assertEquals('generic', agent.inheritFrom)
        assertEquals(['tools'], new Yaml().load(agent.yaml).spec.containers*.name)
        assertFalse(helper.callStack.any { it.methodName in ['checkout', 'archiveArtifacts'] })
        assertEquals([''], crons)
        assertJobStatusSuccess()
    }

    @Test
    void consumerSetsTheModeDefaultAndTheSchedule() {
        loadScript('vars/infisicalReleaseSyncPipeline.groovy').call(mode: 'all', schedule: 'TZ=UTC\n0 6 * * *')
        assertEquals('all', binding.getVariable('params').MODE)
        assertEquals(['all'], modes)
        assertEquals(['TZ=UTC\n0 6 * * *'], crons)
    }

    @Test
    void unknownModeFailsBeforeThePipeline() {
        assertThrows(IllegalArgumentException) {
            loadScript('vars/infisicalReleaseSyncPipeline.groovy').call(mode: 'latest')
        }
        assertNull(declaration)
    }
}
