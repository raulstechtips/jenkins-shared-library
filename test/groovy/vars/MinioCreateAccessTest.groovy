package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioCreateAccessTest extends BasePipelineTest {
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
        loadScript('vars/minioCreateAccess.groovy').call(args)
    }

    @Test
    void firstRunCreatesPolicyUserAttachmentAndKeysForEachLevel() {
        ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: 'b-two, b-one\nb-one')
        ['ro', 'rw'].each { String level ->
            String account = 'app-dev-' + level
            assertEquals(['arn:aws:s3:::b-one', 'arn:aws:s3:::b-two'],
                new JsonSlurper().parseText(fake.policies[account]).Statement[0].Resource)
            assertEquals([account], fake.users[account])
            assertEquals(['NEWKEY' + (level == 'ro' ? 1 : 2)], fake.keys[account])
            assertEquals(fake.keys[account][0], fake.keyId('dev', '/minio/users/app/' + level))
        }
        assertEquals(['create-policy', 'add-user', 'attach-policy', 'create-keys',
            'create-policy', 'add-user', 'attach-policy', 'create-keys'], fake.changes())
    }

    @Test
    void secondRunFailsWithEveryExistingResourceAndCreatesNothing() {
        ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: ['b-one'])
        fake.calls.clear()
        IllegalStateException e = assertThrows(IllegalStateException) {
            ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: ['b-one'])
        }
        assertEquals('These MinIO resources already exist: policy app-dev-ro, user app-dev-ro, ' +
            'policy app-dev-rw, user app-dev-rw. Nothing was created.', e.message)
        assertEquals([], fake.changes())
        assertEquals(['policy-info', 'user-info', 'policy-info', 'user-info'], fake.ops())
    }

    @Test
    void oneExistingPolicyFailsBeforeAnyLevelIsCreated() {
        fake.policies['app-dev-rw'] = '{"Version":"2012-10-17","Statement":[]}'
        IllegalStateException e = assertThrows(IllegalStateException) {
            ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: 'b-one')
        }
        assertEquals('These MinIO resources already exist: policy app-dev-rw. Nothing was created.', e.message)
        assertEquals([], fake.changes())
        assertFalse(fake.users.containsKey('app-dev-ro'))
    }

    @Test
    void existingUserWithoutPolicyFails() {
        fake.users['app-dev-ro'] = []
        IllegalStateException e = assertThrows(IllegalStateException) {
            ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        }
        assertEquals('These MinIO resources already exist: user app-dev-ro. Nothing was created.', e.message)
        assertEquals([], fake.changes())
    }

    @Test
    void otherLevelIsNotChecked() {
        fake.policies['app-dev-rw'] = '{"Version":"2012-10-17","Statement":[]}'
        fake.users['app-dev-rw'] = ['app-dev-rw']
        ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        assertEquals(['create-policy', 'add-user', 'attach-policy', 'create-keys'], fake.changes())
    }

    @Test
    void existingInfisicalKeyIsOverwritten() {
        fake.storeKey('dev', 'app', 'ro', 'OLDKEY')
        ensure(name: 'app', environment: 'dev', access: 'RO', buckets: 'b-one')
        assertEquals('NEWKEY1', fake.keyId('dev', '/minio/users/app/ro'))
        assertTrue(fake.logs.contains('Created access key NEWKEY1 for user app-dev-ro.'))
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
