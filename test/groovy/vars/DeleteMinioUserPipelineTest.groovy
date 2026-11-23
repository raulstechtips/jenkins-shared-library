package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.ParametersDeclaration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class DeleteMinioUserPipelineTest extends DeclarativePipelineTest {
    private DeclarativePipeline declaration
    private MinioFake fake
    private List<Map> parameters
    private Map<String, Object> options

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        parameters = []
        options = [:]
        binding.setVariable('env', [:])
        binding.setVariable('params', [:])
        fake = new MinioFake()
        fake.install(this)
        ['string', 'text', 'choice', 'password', 'booleanParam'].each { String type ->
            ParametersDeclaration.metaClass."${type}" = { Map args ->
                parameters << ([type: type] + args)
                Map params = binding.getVariable('params') as Map
                if (!params.containsKey(args.name)) {
                    params[args.name] = type == 'choice' ? args.choices[0] : args.defaultValue
                }
            }
        }
        helper.registerAllowedMethod('pipeline', [Closure]) { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        }
        helper.registerAllowedMethod('skipDefaultCheckout', []) { options.skipDefaultCheckout = true }
        helper.registerAllowedMethod('disableConcurrentBuilds', []) { options.disableConcurrentBuilds = true }
        helper.registerAllowedMethod('timeout', [Map]) { Map args -> options.timeout = args }
        helper.registerAllowedMethod('logRotator', [Map]) { Map args -> args }
        helper.registerAllowedMethod('buildDiscarder', [Map]) { Map args -> options.buildDiscarder = args }
        helper.registerAllowedMethod('buildDiscarder', [Object]) { Object args -> options.buildDiscarder = args }
        helper.registerAllowedMethod('minioAgentPodYaml', []) { 'MINIO-POD-YAML' }
    }

    @AfterEach
    void removeParameterRecorder() {
        GroovySystem.metaClassRegistry.removeMetaClass(ParametersDeclaration)
    }

    private void run(Map params) {
        binding.setVariable('params', new LinkedHashMap(params))
        loadScript('vars/deleteMinioUserPipeline.groovy').call()
    }

    @Test
    void hasTheExactParametersOptionsAndAgent() {
        run([NAME: 'app', ENVIRONMENT: 'dev', RO: true, RW: true, CONFIRM_NAME: 'app'])
        assertEquals([['string', 'NAME'], ['choice', 'ENVIRONMENT', ['dev', 'staging', 'prod']], ['booleanParam', 'RO'], ['booleanParam', 'RW'], ['string', 'CONFIRM_NAME']], parameters.collect { [it.type, it.name] + (it.choices ? [it.choices] : []) })
        assertTrue(parameters.findAll { it.type in ['string', 'text'] }.every { it.defaultValue == '' })
        assertEquals([skipDefaultCheckout: true, disableConcurrentBuilds: true,
            timeout: [time: 10, unit: 'MINUTES'], buildDiscarder: [numToKeepStr: '20']], options)
        assertEquals('none', declaration.agent.label)
        def kubernetes = declaration.stages['Delete MinIO user'].agent.kubernetes
        assertEquals('generic', kubernetes.inheritFrom)
        assertEquals('MINIO-POD-YAML', kubernetes.yaml)
        assertFalse(helper.callStack.any { it.methodName in ['checkout', 'git'] })
        assertJobStatusSuccess()
    }

    @Test
    void firstBuildWithEmptyParametersFailsWithoutAnyShellCall() {
        assertThrows(Exception) { run([:]) }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }

    @Test
    void wrongConfirmNameFailsBeforeAnyShellCall() {
        assertThrows(IllegalStateException) { run([NAME: 'app', ENVIRONMENT: 'dev', RO: true, RW: true, CONFIRM_NAME: 'ap']) }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }

    @Test
    void neverRemovesABucket() {
        fake.buckets << 'app-dev'
        run([NAME: 'app', ENVIRONMENT: 'dev', RO: true, RW: true, CONFIRM_NAME: 'app'])
        assertEquals(['app-dev'] as Set, fake.buckets)
        assertFalse(fake.calls.any { it.script =~ /mc (rb|rm)\b/ })
    }
}
