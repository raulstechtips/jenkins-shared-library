package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class KubeconformCheckTest extends BasePipelineTest {
    private List commands
    private List containers
    private List files

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        containers = []
        files = [[path: '.pipeline/render/default.yaml'], [path: '.pipeline/render/ci-all-on-values.yaml']]
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers.add(name)
            body.call()
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args ->
            assertEquals([glob: '.pipeline/render/*.yaml'], args)
            files
        }
        helper.registerAllowedMethod('sh', [String]) { String command -> commands.add(command) }
    }
    @Test void validatesEveryRenderWithExactSchemasAndUnionSkipList() {
        loadScript('vars/kubeconformCheck.groovy').call([chartPresent: true])
        assertEquals(['kubeconform'], containers)
        assertEquals([
            "kubeconform -strict -summary -schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -skip InfisicalSecret,InfisicalStaticSecret,InfisicalPushSecret,apiextensions.k8s.io/v1/CustomResourceDefinition '.pipeline/render/default.yaml'",
            "kubeconform -strict -summary -schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -skip InfisicalSecret,InfisicalStaticSecret,InfisicalPushSecret,apiextensions.k8s.io/v1/CustomResourceDefinition '.pipeline/render/ci-all-on-values.yaml'"
        ], commands)
    }
    @Test void emptyRenderFolderDoesNotRunShell() {
        files = []
        loadScript('vars/kubeconformCheck.groovy').call([chartPresent: true])
        assertTrue(commands.empty)
    }
    @Test void missingChartDoesNothing() {
        loadScript('vars/kubeconformCheck.groovy').call([chartPresent: false])
        assertTrue(commands.empty)
        assertTrue(containers.empty)
    }
}
