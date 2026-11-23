package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.MinioPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioDeleteAccessTest extends BasePipelineTest {
    private MinioFake fake

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        binding.setVariable('env', [:])
        fake = new MinioFake()
        fake.install(this)
    }

    private void delete(Map args) {
        loadScript('vars/minioDeleteAccess.groovy').call(args)
    }

    @Test
    void removesKeysUserPolicyAndFoldersButNeverTheBucket() {
        loadScript('vars/minioCreateBucket.groovy').call(name: 'app', environment: 'dev', access: [RO: true, RW: true])
        fake.calls.clear()
        delete(name: 'app', environment: 'dev', access: [RO: true, RW: true], confirmName: 'app')
        assertEquals([:], fake.users)
        assertEquals([:], fake.policies)
        assertEquals([:], fake.keys)
        assertEquals([] as Set, fake.folders)
        assertEquals(['app-dev'] as Set, fake.buckets)
        assertEquals(['remove-access-key', 'detach-policy', 'remove-user', 'remove-policy', 'delete-keys-folder',
            'remove-access-key', 'detach-policy', 'remove-user', 'remove-policy', 'delete-keys-folder',
            'delete-name-folder'], fake.changes())
        assertFalse(fake.calls.any { it.script =~ /mc (rb|rm)\b/ })
        assertTrue(fake.logs.contains('Bucket is not removed by this job.'))
    }

    @Test
    void oneLevelKeepsTheOtherLevelFolder() {
        loadScript('vars/minioCreateBucket.groovy').call(name: 'app', environment: 'dev', access: [RO: true, RW: true])
        delete(name: 'app', environment: 'dev', access: [RO: true], confirmName: 'app')
        assertEquals(['app-dev-rw'] as Set, fake.users.keySet())
        assertEquals(['dev:/minio/users/app', 'dev:/minio/users/app/rw'] as Set, fake.folders)
    }

    @Test
    void missingItemsAreSkipped() {
        delete(name: 'gone', environment: 'dev', access: [RO: true, RW: true], confirmName: 'gone')
        assertEquals(['delete-keys-folder', 'delete-keys-folder', 'delete-name-folder'], fake.changes())
        assertTrue(fake.logs.contains('User gone-dev-ro does not exist. Skipped.'))
        assertTrue(fake.logs.contains('Policy gone-dev-rw does not exist. Skipped.'))
    }

    @Test
    void wrongConfirmNameFailsBeforeAnyShellCall() {
        [null, '', 'other', 'app2'].each { String confirm ->
            def failure = assertThrows(IllegalStateException) {
                delete(name: 'app', environment: 'dev', access: [RO: true, RW: true], confirmName: confirm)
            }
            assertTrue(failure.message.contains('is not equal to NAME'), failure.message)
        }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }

    @Test
    void emptyOrInvalidInputFailsWithoutAnyShellCall() {
        [[:], [name: '', environment: 'dev', access: [RO: true], confirmName: ''],
            [name: 'app', environment: 'dev', access: [NONE: true], confirmName: 'app']].each { Map args ->
            assertThrows(IllegalArgumentException) { delete(args) }
        }
        assertEquals([], fake.calls)
    }
}
