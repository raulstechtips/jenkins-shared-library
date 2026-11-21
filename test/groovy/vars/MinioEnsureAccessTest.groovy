package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonSlurper
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
    void secondRunWithEverythingPresentCreatesNothing() {
        ensure(name: 'app', environment: 'dev', access: 'RW', buckets: ['b-one'])
        fake.calls.clear()
        ensure(name: 'app', environment: 'dev', access: 'RW', buckets: ['b-one'])
        assertEquals([], fake.changes())
        assertEquals(['policy-info', 'user-info', 'read-key-id', 'list-access-keys'], fake.ops())
    }

    @Test
    void existingPolicyIsSkippedByNameAndNotChanged() {
        // Policy document captured from MinIO after a hand edit: it names another bucket.
        String edited = '{"Version":"2012-10-17","Statement":[{"Effect":"Allow",' +
            '"Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::longhorn-backup"]},' +
            '{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::longhorn-backup/*"]}]}'
        fake.policies['app-dev-ro'] = edited
        ensure(name: 'app', environment: 'dev', access: 'BOTH', buckets: 'b-one')
        assertEquals(edited, fake.policies['app-dev-ro'])
        assertTrue(fake.logs.contains('Policy app-dev-ro exists. Skipped.'))
        assertEquals(['add-user', 'attach-policy', 'create-keys',
            'create-policy', 'add-user', 'attach-policy', 'create-keys'], fake.changes())
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
