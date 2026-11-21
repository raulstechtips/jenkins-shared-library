package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.MinioPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioSessionTest extends BasePipelineTest {
    private MinioFake fake

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        binding.setVariable('env', [:])
        fake = new MinioFake()
        fake.install(this)
    }

    @Test
    void installsToolsBindsBothCredentialsAndRunsTheBodyInTheToolsContainer() {
        def script = loadScript('vars/minioSession.groovy')
        String result = script.call { c ->
            script.sh(script: c.listBuckets(), returnStdout: true)
            'done'
        }
        assertEquals('done', result)
        assertEquals(1, fake.installs)
        assertEquals(['jenkins-minio', 'jenkins-infisical'], fake.credentialIds)
        assertEquals(['list-buckets'], fake.ops())
        assertNull(binding.getVariable('env').MINIO_TOOLS_BIN)
        assertNull(binding.getVariable('env').MINIO_SECRET_KEY)
    }

    @Test
    void nestedSessionReusesTheOpenSession() {
        def script = loadScript('vars/minioSession.groovy')
        script.call { c ->
            script.minioSession { inner -> script.sh(script: inner.listBuckets(), returnStdout: true) }
        }
        assertEquals(1, fake.installs)
        assertEquals(2, fake.credentialIds.size())
        assertEquals(['list-buckets'], fake.ops())
    }

    @Test
    void credentialIdsComeFromSettings() {
        Map settings = new org.yaml.snakeyaml.Yaml().load(new File('resources/dev/raulstechtips/jenkins/minio/settings.yaml').text)
        assertEquals('jenkins-minio', settings.minioCredentialsId)
        assertEquals('jenkins-infisical', settings.infisicalCredentialsId)
    }
}
