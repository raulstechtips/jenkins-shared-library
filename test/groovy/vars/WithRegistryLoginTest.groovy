package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class WithRegistryLoginTest extends BasePipelineTest {
    private List bindings
    private boolean credentialsActive

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        bindings = []
        credentialsActive = false
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            assertEquals('dev/raulstechtips/jenkins/registries.yaml', path)
            new File('resources/' + path).text
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals(new File('resources/dev/raulstechtips/jenkins/registries.yaml').text, args.text)
            [
                ghcr: ['credentials-id': 'ghcr-push'],
                harbor: ['credentials-id': 'harbor-push']
            ]
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List args, Closure body ->
            bindings.addAll(args)
            credentialsActive = true
            try {
                body.call()
            } finally {
                credentialsActive = false
            }
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
    }

    private void checkTarget(String target, String credentialsId) {
        int bodyCalls = 0
        def result = loadScript('vars/withRegistryLogin.groovy').call(target) {
            assertTrue(credentialsActive)
            bodyCalls++
            'body result'
        }
        assertEquals([[credentialsId: credentialsId, usernameVariable: 'REG_USER',
            passwordVariable: 'REG_PASS']], bindings)
        assertEquals(1, bodyCalls)
        assertEquals('body result', result)
        assertFalse(credentialsActive)
    }

    @Test
    void bindsGhcrCredentialsAroundBody() {
        checkTarget('ghcr', 'ghcr-push')
    }

    @Test
    void bindsHarborCredentialsAroundBody() {
        checkTarget('harbor', 'harbor-push')
    }

    @Test
    void unknownTargetFailsBeforeBindingOrRunningBody() {
        boolean called = false
        def failure = assertThrows(IllegalArgumentException) {
            loadScript('vars/withRegistryLogin.groovy').call('unknown') { called = true }
        }
        assertTrue(failure.message.contains('unknown'))
        assertFalse(called)
        assertTrue(bindings.empty)
    }

    @Test
    void bodyFailurePropagatesAndCredentialsScopeEnds() {
        def failure = assertThrows(IllegalStateException) {
            loadScript('vars/withRegistryLogin.groovy').call('ghcr') {
                throw new IllegalStateException('body failed')
            }
        }
        assertEquals('body failed', failure.message)
        assertFalse(credentialsActive)
    }
}
