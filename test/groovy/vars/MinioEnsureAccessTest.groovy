package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.MinioPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioEnsureAccessTest extends BasePipelineTest {
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
        loadScript('vars/minioEnsureAccess.groovy').call(args)
    }

    @Test
    void firstRunCreatesPolicyUserAttachmentAndKeysForEachLevel() {
        ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: 'b-two, b-one\nb-one')
        ['ro', 'rw'].each { String level ->
            String account = 'app-dev-' + level
            assertEquals(['b-one', 'b-two'], new MinioPolicy().bucketsOf(fake.policies[account]))
            assertEquals([account], fake.users[account])
            assertEquals(['NEWKEY' + (level == 'ro' ? 1 : 2)], fake.keys[account])
            assertEquals(fake.keys[account][0], fake.keyId('dev', '/minio/users/app/' + level))
        }
        assertEquals(['create-policy', 'add-user', 'attach-policy', 'create-keys',
            'create-policy', 'add-user', 'attach-policy', 'create-keys'], fake.changes())
    }

    @Test
    void secondRunWithEverythingPresentCreatesNothing() {
        ensure(name: 'app', environment: 'dev', access: 'RW', buckets: ['b-one'])
        fake.calls.clear()
        ensure(name: 'app', environment: 'dev', access: 'RW', buckets: ['b-one'])
        assertEquals([], fake.changes())
        assertEquals(['policy-info', 'user-info', 'read-key-id', 'list-access-keys'], fake.ops())
    }

    @Test
    void policyForOtherBucketsFailsBeforeAnyChange() {
        fake.policies['app-dev-rw'] = new MinioPolicy().render(new File('resources/dev/raulstechtips/jenkins/minio/policies/rw.json').text, ['other'])
        def failure = assertThrows(IllegalStateException) {
            ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: 'b-one')
        }
        assertEquals('policy app-dev-rw exists for other buckets: other, use another name', failure.message)
        assertEquals([], fake.changes())
    }

    @Test
    void attachesOnlyWhenNotAttached() {
        fake.users['app-dev-ro'] = ['unrelated']
        ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        assertEquals(['unrelated', 'app-dev-ro'], fake.users['app-dev-ro'])
        assertFalse(fake.ops().contains('add-user'))
    }

    @Test
    void staleInfisicalKeyCreatesANewKeyAndRemovesEveryOtherKey() {
        ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        fake.keys['app-dev-ro'] = ['OLD1', 'OLD2']
        fake.calls.clear()
        ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        assertEquals(['create-keys', 'remove-access-key', 'remove-access-key'], fake.changes())
        assertEquals(['NEWKEY2'], fake.keys['app-dev-ro'])
        assertEquals('NEWKEY2', fake.keyId('dev', '/minio/users/app/ro'))
        assertTrue(fake.logs.contains('Created access key NEWKEY2 for user app-dev-ro.'))
    }

    @Test
    void emptyOrInvalidInputFailsWithoutAnyShellCall() {
        [[:], [name: '', environment: 'dev', access: 'RO', buckets: 'b'],
            [name: 'app', environment: '', access: 'RO', buckets: 'b'],
            [name: 'app', environment: 'dev', access: '', buckets: 'b'],
            [name: 'app', environment: 'dev', access: 'ro', buckets: 'b'],
            [name: 'app', environment: 'dev', access: 'RO', buckets: ' , '],
            [name: 'app', environment: 'dev', access: 'RO', buckets: 'Bad_Bucket']].each { Map args ->
            assertThrows(IllegalArgumentException) { ensure(args) }
        }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }
}
