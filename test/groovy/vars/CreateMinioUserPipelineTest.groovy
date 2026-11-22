package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import com.lesfurets.jenkins.unit.declarative.ParametersDeclaration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class CreateMinioUserPipelineTest extends DeclarativePipelineTest {
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
        ['string', 'text', 'choice', 'password'].each { String type ->
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
        loadScript('vars/createMinioUserPipeline.groovy').call()
    }

    @Test
    void hasTheExactParametersOptionsAndAgent() {
        run([USER_NAME: 'reader', ACCESS: 'RO', BUCKETS: 'app-dev\nlogs-dev', ENVIRONMENT: 'dev'])
        assertEquals([['string', 'USER_NAME'], ['choice', 'ACCESS', ['RO', 'RW', 'BOTH']], ['text', 'BUCKETS'], ['choice', 'ENVIRONMENT', ['dev', 'staging', 'prod']]], parameters.collect { [it.type, it.name] + (it.choices ? [it.choices] : []) })
        assertTrue(parameters.findAll { it.type in ['string', 'text'] }.every { it.defaultValue == '' })
        assertEquals([skipDefaultCheckout: true, disableConcurrentBuilds: true,
            timeout: [time: 10, unit: 'MINUTES'], buildDiscarder: [numToKeepStr: '20']], options)
        assertEquals('none', declaration.agent.label)
        def kubernetes = declaration.stages['Create MinIO user'].agent.kubernetes
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
    void accessChoicesComeFromLevelsYaml() {
        run([USER_NAME: 'reader', ACCESS: 'BOTH', BUCKETS: 'app-dev', ENVIRONMENT: 'dev'])
        List levels = new org.yaml.snakeyaml.Yaml().load(new File('resources/dev/raulstechtips/jenkins/minio/levels.yaml').text).levels
        assertEquals(levels.collect { it.toUpperCase() } + ['BOTH'], parameters.find { it.name == 'ACCESS' }.choices)
        assertEquals(['reader-dev-ro', 'reader-dev-rw'] as Set, fake.users.keySet())
    }

    @Test
    void secondRunFailsAndChangesNothing() {
        run([USER_NAME: 'reader', ACCESS: 'RO', BUCKETS: 'app-dev', ENVIRONMENT: 'dev'])
        fake.calls.clear()
        assertThrows(IllegalStateException) { run([USER_NAME: 'reader', ACCESS: 'RO', BUCKETS: 'app-dev', ENVIRONMENT: 'dev']) }
        assertEquals([], fake.changes())
    }

    @Test
    void emptyBucketsFailWithoutAnyShellCall() {
        assertThrows(IllegalArgumentException) { run([USER_NAME: 'reader', ACCESS: 'RO', BUCKETS: ' \n ', ENVIRONMENT: 'dev']) }
        assertEquals([], fake.calls)
    }
}
