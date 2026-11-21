package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.MinioPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioEnsureBucketTest extends BasePipelineTest {
    private MinioFake fake

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        binding.setVariable('env', [:])
        fake = new MinioFake()
        fake.install(this)
    }

    private void ensure(Map args) {
        loadScript('vars/minioEnsureBucket.groovy').call(args)
    }

    @Test
    void createsTheBucketAndBothLevelsInOneSession() {
        ensure(name: 'app', environment: 'staging')
        assertEquals(['app-staging'] as Set, fake.buckets)
        assertEquals(['app-staging-ro', 'app-staging-rw'] as Set, fake.policies.keySet())
        assertEquals(['arn:aws:s3:::app-staging'],
            new groovy.json.JsonSlurper().parseText(fake.policies['app-staging-rw']).Statement[0].Resource)
        assertEquals(['app-staging-ro', 'app-staging-rw'] as Set, fake.users.keySet())
        assertEquals(1, fake.installs)
        assertEquals('create-bucket', fake.changes()[0])
    }

    @Test
    void secondRunWithExistingBucketPolicyUserAndKeyCreatesNothing() {
        ensure(name: 'app', environment: 'dev')
        fake.calls.clear()
        ensure(name: 'app', environment: 'dev')
        assertEquals([], fake.changes())
        assertTrue(fake.logs.contains('Bucket app-dev exists. Skipped.'))
    }

    @Test
    void existingPolicyWithOtherContentIsSkipped() {
        fake.buckets << 'app-dev'
        String edited = '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject"],' +
            '"Resource":["arn:aws:s3:::other/*"]}]}'
        fake.policies['app-dev-ro'] = edited
        ensure(name: 'app', environment: 'dev')
        assertEquals(edited, fake.policies['app-dev-ro'])
        assertTrue(fake.logs.contains('Policy app-dev-ro exists. Skipped.'))
        assertFalse(fake.changes().contains('create-bucket'))
    }

    @Test
    void emptyOrInvalidInputFailsWithoutAnyShellCall() {
        [[:], [name: '', environment: 'dev'], [name: 'app', environment: null],
            [name: 'App', environment: 'dev'], [name: 'a--b', environment: 'dev'],
            [name: 'x' * 60, environment: 'dev']].each { Map args ->
            assertThrows(IllegalArgumentException) { ensure(args) }
        }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }

    @Test
    void neverChecksTheBucketWithAHeadOrIgnoreExisting() {
        ensure(name: 'app', environment: 'dev')
        assertFalse(fake.calls.any { it.script.contains('--ignore-existing') || it.script.contains('mc stat') })
        assertTrue(fake.calls.find { it.op == 'list-buckets' }.script.contains('mc ls --json "$alias"'))
    }
}
