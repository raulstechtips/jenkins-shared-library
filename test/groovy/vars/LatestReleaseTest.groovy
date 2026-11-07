package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class LatestReleaseTest extends BasePipelineTest {
    private Script step
    private Map environment
    private List<Map> commands
    private String tags
    private boolean credentialScope
    private final String token = 'test-only-secret-sentinel'

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        environment = [:]
        binding.setVariable('env', environment)
        commands = []
        tags = 'a\trefs/tags/0.1.0\nb\trefs/tags/0.2.0\nc\trefs/tags/0.2.0^{}\nd\trefs/tags/v9.0.0\n' +
            'e\trefs/tags/1.0.0-rc1\n'
        credentialScope = false
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List values, Closure body ->
            assertEquals('github-app', values[0].credentialsId)
            assertEquals('GH_TOKEN', values[0].passwordVariable)
            environment.GH_TOKEN = token
            credentialScope = true
            try {
                body()
            } finally {
                environment.remove('GH_TOKEN')
                credentialScope = false
            }
        }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            String script = args.script
            commands << [script: script, credentials: credentialScope]
            assertTrue(script instanceof String)
            assertFalse(script.contains(token), 'secret was interpolated into sh')
            assertEquals(true, args.returnStdout)
            tags
        }
        step = loadScript('vars/latestRelease.groovy')
    }

    @Test
    void returnsHighestReleaseTagFromAuthenticatedLsRemote() {
        assertEquals('0.2.0', step.call())
        assertEquals(1, commands.size())
        assertTrue(commands[0].credentials)
        assertTrue(commands[0].script.startsWith('set +x\n'))
        assertTrue(commands[0].script.contains('${GH_TOKEN}'))
        assertTrue(commands[0].script.endsWith('git ls-remote --tags origin'))
        assertFalse(environment.containsKey('GH_TOKEN'))
    }

    @Test
    void returnsNullWhenThereIsNoRelease() {
        tags = 'a\trefs/tags/v1.0.0\n'
        assertNull(step.call())
        tags = ''
        assertNull(step.call())
    }
}
