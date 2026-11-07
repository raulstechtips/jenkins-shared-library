package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class UpdateChartVersionTest extends BasePipelineTest {
    private Script step
    private Map environment
    private List<Map> commands
    private List<String> containers
    private List<String> echoes
    private int commitStatus
    private int pushStatus
    private boolean credentialScope
    private final String token = 'test-only-secret-sentinel'

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        environment = [:]
        binding.setVariable('env', environment)
        commands = []
        containers = []
        echoes = []
        commitStatus = 0
        pushStatus = 0
        credentialScope = false
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            containers << name
            body()
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            Map saved = new LinkedHashMap(environment)
            values.each { String value ->
                int equals = value.indexOf('=')
                environment[value.substring(0, equals)] = value.substring(equals + 1)
            }
            try {
                body()
            } finally {
                environment.clear()
                environment.putAll(saved)
            }
        }
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
        helper.registerAllowedMethod('echo', [String]) { String message -> echoes << message }
        helper.registerAllowedMethod('sh', [String]) { String script -> shell([script: script]) }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> shell(args) }
        step = loadScript('vars/updateChartVersion.groovy')
    }

    private Object shell(Map args) {
        Object script = args.script
        assertTrue(script instanceof String, 'sh must receive a plain String, not a GString')
        assertFalse(script.contains(token), 'secret was interpolated into sh')
        commands << [script: script, environment: new LinkedHashMap(environment), credentials: credentialScope]
        if (script.contains('git push')) {
            assertTrue(credentialScope)
            return pushStatus
        }
        assertFalse(credentialScope, 'credentials must wrap only the push')
        if (script.contains(' commit -m ')) {
            return commitStatus
        }
        return 0
    }

    @Test
    void commitsOnlyChartYamlAsBotAndPushesToMain() {
        step.call([version: '0.1.0-main-0067101-7'])

        assertEquals(['tools'], containers)
        assertEquals(3, commands.size())
        assertEquals('yq -i \'.version = strenv(V)\' charts/Chart.yaml', commands[0].script)
        assertEquals('0.1.0-main-0067101-7', commands[0].environment.V)
        assertEquals("git add charts/Chart.yaml && git -c user.name='raulstechtips-jenkins[bot]' " +
            "-c user.email='raulstechtips-jenkins[bot]@users.noreply.github.com' " +
            'commit -m "chore: set chart version to $V"', commands[1].script)
        assertEquals('raulstechtips-jenkins[bot]', commands[1].environment.GIT_COMMITTER_NAME)
        assertEquals('raulstechtips-jenkins[bot]@users.noreply.github.com', commands[1].environment.GIT_COMMITTER_EMAIL)
        assertTrue(commands[2].script.startsWith('set +x\n'))
        assertTrue(commands[2].script.contains('${GH_TOKEN}'))
        assertTrue(commands[2].script.endsWith('git push origin HEAD:refs/heads/main'))
        assertTrue(echoes.empty)
        assertFalse(environment.containsKey('GH_TOKEN'))
    }

    @Test
    void failedPushOnlyWarns() {
        pushStatus = 1
        step.call([version: '0.1.0-main-0067101-7'])
        assertEquals(1, echoes.size())
        assertTrue(echoes[0].startsWith('WARNING: Could not push chart version 0.1.0-main-0067101-7 to main'))
    }

    @Test
    void failedCommitOnlyWarnsAndDoesNotPush() {
        commitStatus = 1
        step.call([version: '0.1.0-main-0067101-7'])
        assertFalse(commands.any { it.script.contains('git push') })
        assertEquals(1, echoes.size())
        assertTrue(echoes[0].startsWith('WARNING:'))
    }
}
