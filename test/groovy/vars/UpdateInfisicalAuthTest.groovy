package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.InfisicalAuthCommands
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class UpdateInfisicalAuthTest extends BasePipelineTest {
    private String containerName
    private List<String> commands
    private Map targets

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        targets = [credentialsNamespace: 'infisical', credentialsSecretName: 'universal-auth-credentials',
            connectionNamespace: 'default', connectionName: 'infisical-prod']
        commands = []
        binding.setVariable('env', [
            INFISICAL_CLIENT_ID: 'new-id',
            INFISICAL_CLIENT_SECRET: 'new-secret',
            INFISICAL_URL: 'https://new.example.test/api'
        ])
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containerName = name
            body()
        }
        helper.registerAllowedMethod('sh', [String]) { String command ->
            assertEquals('kubectl', containerName)
            commands << command
        }
    }

    @Test
    void runsTheRealCommandHelperOutputInOrder() {
        loadScript('vars/updateInfisicalAuth.groovy').call(targets)
        assertEquals('kubectl', containerName)
        assertEquals(new InfisicalAuthCommands().update([
            clientId: 'new-id', clientSecret: 'new-secret', url: 'https://new.example.test/api'
        ], targets), commands)
        assertFalse(commands.any { it.contains('new-secret') || it.contains('new-id') })
    }

    @Test
    void rejectsMissingOrBlankResourceConfigurationBeforeShellExecution() {
        targets.keySet().each { String key ->
            [null, '', '  ', 7].each { invalid ->
                Map bad = new LinkedHashMap(targets)
                bad[key] = invalid
                assertThrows(IllegalArgumentException) {
                    loadScript('vars/updateInfisicalAuth.groovy').call(bad)
                }
                assertTrue(commands.empty)
            }
        }
    }

    @Test
    void emptyInputFailsBeforeAnyShellCommand() {
        binding.setVariable('env', [:])
        assertThrows(IllegalArgumentException) {
            loadScript('vars/updateInfisicalAuth.groovy').call(targets)
        }
        assertTrue(commands.empty)
    }

    @Test
    void stopsOnPreflightFailureWithoutRunningTheUpdate() {
        helper.registerAllowedMethod('sh', [String]) { String command ->
            commands << command
            throw new IllegalStateException('Patch forbidden')
        }
        assertThrows(IllegalStateException) {
            loadScript('vars/updateInfisicalAuth.groovy').call(targets)
        }
        assertEquals(1, commands.size())
        assertTrue(commands[0].contains('--dry-run=server'))
    }
}
