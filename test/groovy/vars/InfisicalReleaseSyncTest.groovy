package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import dev.raulstechtips.jenkins.helpers.InfisicalReleases
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class InfisicalReleaseSyncTest extends BasePipelineTest {
    private List<Map> commands
    private Map environment
    private String activeContainer
    private boolean credentials
    private String tags
    private String branches

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        environment = [:]
        credentials = false
        tags = 'v0.162.20\nv0.162.21\nv0.162.22\nv0.162.23\nv0.163.0-nightly-1\n'
        branches = 'release/v0.162.21\n'
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            activeContainer = name
            try { body.call() } finally { activeContainer = null }
        }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List bindings, Closure body ->
            credentials = true
            try { body.call() } finally { credentials = false }
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args ->
            assertEquals([credentialsId: 'github-app', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN'], args)
            args
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            Map previous = new LinkedHashMap(environment)
            values.each { String value ->
                int separator = value.indexOf('=')
                environment[value.substring(0, separator)] = value.substring(separator + 1)
            }
            try { body.call() } finally { environment = previous }
        }
        helper.registerAllowedMethod('sh', [String]) { String text ->
            commands << [text: text, container: activeContainer, credentials: credentials,
                environment: new LinkedHashMap(environment)]
        }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            assertTrue(args.returnStdout)
            args.script.contains('tag -l') ? tags : branches
        }
        helper.registerAllowedMethod('error', [String]) { String message -> throw new IllegalStateException(message) }
    }

    @Test
    void allCreatesEachNewerTagFromThePreviousBranch() {
        assertEquals(['v0.162.22', 'v0.162.23'], loadScript('vars/infisicalReleaseSync.groovy').call('all'))
        assertEquals(3, commands.size())
        assertTrue(commands.every { it.container == 'tools' && it.credentials })
        assertTrue(commands[0].text.endsWith(InfisicalReleases.FETCH_SCRIPT))
        assertTrue(commands.every { it.text.startsWith(InfisicalReleases.FORK_AUTH) })
        List branchRuns = commands.drop(1)
        assertTrue(branchRuns.every { it.text.endsWith(InfisicalReleases.BRANCH_SCRIPT) })
        assertEquals([['v0.162.21', 'v0.162.22', 'refs/remotes/origin/release/v0.162.21'],
            ['v0.162.22', 'v0.162.23', 'refs/remotes/origin/release/v0.162.22']],
            branchRuns.collect { [it.environment.PREV, it.environment.TAG, it.environment.SOURCE] })
        Map env = commands[0].environment
        assertEquals('https://github.com/raulstechtips/secrets-manager.git', env.REPO_URL)
        assertEquals('https://github.com/Infisical/infisical.git', env.UPSTREAM_URL)
        assertEquals('infisical-release-jenkins[bot]', env.COMMITTER_NAME)
        assertEquals('infisical-release-jenkins[bot]@users.noreply.github.com', env.COMMITTER_EMAIL)
    }

    @Test
    void nextCreatesOnlyOneBranch() {
        assertEquals(['v0.162.22'], loadScript('vars/infisicalReleaseSync.groovy').call('next'))
        assertEquals(2, commands.size())
    }

    @Test
    void nothingNewCreatesNothing() {
        branches = 'release/v0.162.23\n'
        assertEquals([], loadScript('vars/infisicalReleaseSync.groovy').call('all'))
        assertEquals(1, commands.size())
    }

    @Test
    void missingFirstBranchFails() {
        branches = ''
        def failure = assertThrows(IllegalStateException) { loadScript('vars/infisicalReleaseSync.groovy').call('all') }
        assertTrue(failure.message.contains('Create the first one by hand'))
        assertEquals(1, commands.size())
    }

    @Test
    void tokenOnlyReachesForkUrlsAndIsNeverTraced() {
        String auth = InfisicalReleases.FORK_AUTH
        assertTrue(auth.startsWith('set +x\n'))
        assertTrue(auth.contains('url.https://x-access-token:${GH_TOKEN}@github.com/raulstechtips/.insteadOf'))
        assertTrue(auth.contains('GIT_CONFIG_VALUE_0=https://github.com/raulstechtips/\n'))
        assertFalse(auth.contains('Infisical'))
    }
}
