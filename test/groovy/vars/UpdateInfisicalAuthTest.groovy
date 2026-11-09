package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.InfisicalAuthCommands
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class UpdateInfisicalAuthTest extends BasePipelineTest {
    private String containerName
    private List<String> commands

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        binding.setVariable('env', [
            INFISICAL_CLIENT_ID: 'new-id',
            INFISICAL_CLIENT_SECRET: 'new-secret',
            INFISICAL_URL: 'https://new.example.test/api',
            INFISICAL_CONNECTION_NAME: 'infisical-dev'
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
        loadScript('vars/updateInfisicalAuth.groovy').call()
        assertEquals('kubectl', containerName)
        assertEquals(new InfisicalAuthCommands().update([
            clientId: 'new-id', clientSecret: 'new-secret', url: 'https://new.example.test/api',
            connectionName: 'infisical-dev'
        ]), commands)
        assertFalse(commands.any { it.contains('new-secret') || it.contains('new-id') })
    }

    @Test
    void rejectsMissingOrBlankConnectionNameBeforeShellExecution() {
        [null, '', '  ', 7].each { invalid ->
            binding.getVariable('env').INFISICAL_CONNECTION_NAME = invalid
            assertThrows(IllegalArgumentException) {
                loadScript('vars/updateInfisicalAuth.groovy').call()
            }
            assertTrue(commands.empty)
        }
    }

    @Test
    void emptyInputFailsBeforeAnyShellCommand() {
        binding.setVariable('env', [:])
        assertThrows(IllegalArgumentException) {
            loadScript('vars/updateInfisicalAuth.groovy').call()
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
            loadScript('vars/updateInfisicalAuth.groovy').call()
        }
        assertEquals(1, commands.size())
        assertTrue(commands[0].contains('--dry-run=server'))
    }
}
