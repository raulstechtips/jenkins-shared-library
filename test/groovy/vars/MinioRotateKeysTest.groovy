package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.MinioPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioRotateKeysTest extends BasePipelineTest {
    private MinioFake fake

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        binding.setVariable('env', [:])
        fake = new MinioFake()
        fake.install(this)
    }

    private void rotate(Map args) {
        loadScript('vars/minioRotateKeys.groovy').call(args)
    }

    @Test
    void createsANewKeyWritesItAndRemovesEveryOtherKey() {
        fake.users['app-prod-rw'] = ['app-prod-rw']
        fake.keys['app-prod-rw'] = ['OLD1', 'OLD2']
        fake.storeKey('prod', 'app', 'rw', 'OLD1')
        rotate(name: 'app', environment: 'prod', access: 'RW')
        assertEquals(['NEWKEY1'], fake.keys['app-prod-rw'])
        assertEquals('NEWKEY1', fake.keyId('prod', '/minio/users/app/rw'))
        assertEquals(['create-keys', 'remove-access-key', 'remove-access-key'], fake.changes())
    }

    @Test
    void missingUserFailsBeforeAnyChange() {
        fake.users['app-dev-ro'] = ['app-dev-ro']
        def failure = assertThrows(IllegalStateException) { rotate(name: 'app', environment: 'dev', access: 'BOTH') }
        assertEquals('user app-dev-rw does not exist', failure.message)
        assertEquals([], fake.changes())
    }

    @Test
    void emptyOrInvalidInputFailsWithoutAnyShellCall() {
        [[:], [name: '', environment: 'dev', access: 'RO'], [name: 'app', environment: 'qa', access: 'RO'],
            [name: 'app', environment: 'dev', access: 'ADMIN']].each { Map args ->
            assertThrows(IllegalArgumentException) { rotate(args) }
        }
        assertEquals([], fake.calls)
        assertEquals(0, fake.installs)
    }
}
