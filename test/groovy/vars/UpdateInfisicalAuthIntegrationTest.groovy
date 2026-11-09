package vars

import dev.raulstechtips.jenkins.helpers.InfisicalAuthCommands
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** Executes the command helper with Bash and jq against a local Kubernetes merge-patch fixture. */
@Tag('operations-integration')
class UpdateInfisicalAuthIntegrationTest {
    private File root
    private File bin
    private Map<String, String> environment
    private final Map initialCredentials = [
        metadata: [name: 'universal-auth-credentials', labels: [keep: 'yes']],
        data: [clientId: encoded('old-id'), clientSecret: encoded('old-secret'), unrelated: encoded('keep')]]
    private final Map initialConnection = [
        metadata: [name: 'infisical-prod', labels: [keep: 'yes']],
        spec: [address: 'https://old.example.test/api', unrelated: 'keep']]

    @BeforeEach
    void setUp() {
        root = Files.createTempDirectory('infisical-update-').toFile()
        bin = new File(root, 'bin')
        assertTrue(bin.mkdir())
        File kubectl = new File(bin, 'kubectl')
        kubectl.text = fakeKubectl()
        assertTrue(kubectl.setExecutable(true))
        environment = [
            PATH: bin.path + File.pathSeparator + System.getenv('PATH'),
            TEST_STATE: root.path,
            INFISICAL_CREDENTIALS_NAMESPACE: 'infisical',
            INFISICAL_CREDENTIALS_SECRET: 'universal-auth-credentials',
            INFISICAL_CONNECTION_NAMESPACE: 'default',
            INFISICAL_CONNECTION_NAME: 'infisical-prod',
            INFISICAL_CLIENT_ID: '',
            INFISICAL_CLIENT_SECRET: '',
            INFISICAL_URL: ''
        ]
        resetState()
    }

    @AfterEach
    void cleanUp() {
        if (root != null) {
            assertTrue(root.deleteDir())
        }
    }

    @Test
    void allSevenNonEmptyCombinationsChangeOnlySuppliedFields() {
        (1..7).each { int mask ->
            resetState()
            boolean id = (mask & 1) != 0
            boolean secret = (mask & 2) != 0
            boolean url = (mask & 4) != 0
            String replacementSecret = 'quotes\'" dollar$ backtick` newline\n$(touch unexpected)'
            environment.INFISICAL_CLIENT_ID = id ? 'new-id' : ''
            environment.INFISICAL_CLIENT_SECRET = secret ? replacementSecret : ''
            environment.INFISICAL_URL = url ? 'https://new.example.test/api' : ''
            Map result = runScript()
            assertEquals(0, result.code, result.output as String)
            Map expectedCredentials = new JsonSlurper().parseText(JsonOutput.toJson(initialCredentials))
            Map expectedConnection = new JsonSlurper().parseText(JsonOutput.toJson(initialConnection))
            if (id) { expectedCredentials.data.clientId = encoded('new-id') }
            if (secret) { expectedCredentials.data.clientSecret = encoded(replacementSecret) }
            if (url) { expectedConnection.spec.address = 'https://new.example.test/api' }
            assertEquals(expectedCredentials, readState('credentials'))
            assertEquals(expectedConnection, readState('connection'))
            List<String> resources = []
            if (id || secret) { resources << 'secret' }
            if (url) { resources << 'infisicalconnections.secrets.infisical.com' }
            assertEquals(resources.collect { 'true ' + it } + resources.collect { 'false ' + it }, calls())
            assertFalse(new File(root, 'unexpected').exists())
            assertFalse(result.output.contains(replacementSecret))
            assertFalse(result.output.contains(encoded(replacementSecret)))
        }
    }

    @Test
    void emptyAndWhitespaceOnlyInputsFailBeforeAnyKubernetesCall() {
        ['', ' \t\n'].each { String value ->
            environment.INFISICAL_CLIENT_ID = value
            environment.INFISICAL_CLIENT_SECRET = value
            environment.INFISICAL_URL = value
            assertThrows(IllegalArgumentException) { runScript() }
            assertTrue(calls().empty)
            assertEquals(initialCredentials, readState('credentials'))
            assertEquals(initialConnection, readState('connection'))
        }
    }

    @Test
    void blankOmittedFieldsDoNotClearExistingValues() {
        environment.INFISICAL_CLIENT_ID = ' \t'
        environment.INFISICAL_CLIENT_SECRET = 'replacement'
        environment.INFISICAL_URL = '\n'
        assertEquals(0, runScript().code)
        assertEquals(initialCredentials.data.clientId, readState('credentials').data.clientId)
        assertEquals(encoded('replacement'), readState('credentials').data.clientSecret)
        assertEquals(initialConnection, readState('connection'))
    }

    @Test
    void invalidUrlRejectsTheWholeRequestBeforeCredentialChanges() {
        environment.INFISICAL_CLIENT_ID = 'new-id'
        ['not-a-url', 'file:///etc/passwd', 'https://user:pass@example.test/api',
            'https://example.test/api?token=x', 'https://example.test/api\n'].each { String url ->
            environment.INFISICAL_URL = url
            assertThrows(IllegalArgumentException) { runScript() }
            assertTrue(calls().empty)
            assertEquals(initialCredentials, readState('credentials'))
        }
    }

    @Test
    void missingConnectionFailsPreflightWithoutUpdatingCredentials() {
        assertTrue(new File(root, 'connection.json').delete())
        environment.INFISICAL_CLIENT_ID = 'new-id'
        environment.INFISICAL_URL = 'https://new.example.test/api'
        assertNotEquals(0, runScript().code)
        assertEquals(initialCredentials, readState('credentials'))
        assertTrue(calls().every { it.startsWith('true ') })
    }

    @Test
    void permissionDenialIsReportedWithoutAnyWrite() {
        environment.INFISICAL_CLIENT_SECRET = 'replacement'
        environment.TEST_DENY_RESOURCE = 'secret'
        assertNotEquals(0, runScript().code)
        assertEquals(initialCredentials, readState('credentials'))
        assertEquals(['true secret'], calls())
    }

    private void resetState() {
        new File(root, 'credentials.json').text = JsonOutput.toJson(initialCredentials)
        new File(root, 'connection.json').text = JsonOutput.toJson(initialConnection)
        new File(root, 'calls').text = ''
    }

    private Map runScript() {
        List<String> commands = new InfisicalAuthCommands().update([
            clientId: environment.INFISICAL_CLIENT_ID,
            clientSecret: environment.INFISICAL_CLIENT_SECRET,
            url: environment.INFISICAL_URL
        ], [
            credentialsNamespace: environment.INFISICAL_CREDENTIALS_NAMESPACE,
            credentialsSecretName: environment.INFISICAL_CREDENTIALS_SECRET,
            connectionNamespace: environment.INFISICAL_CONNECTION_NAMESPACE,
            connectionName: environment.INFISICAL_CONNECTION_NAME
        ])
        ProcessBuilder builder = new ProcessBuilder('bash', '-c', commands.join('\n'))
        builder.directory(root)
        builder.environment().putAll(environment)
        builder.redirectErrorStream(true)
        File output = new File(root, 'output')
        builder.redirectOutput(output)
        Process process = builder.start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail('Authentication update script exceeded its test deadline')
        }
        [code: process.exitValue(), output: output.text]
    }

    private Map readState(String name) {
        new JsonSlurper().parse(new File(root, name + '.json')) as Map
    }

    private List<String> calls() {
        new File(root, 'calls').readLines()
    }

    private static String encoded(String value) {
        value.getBytes('UTF-8').encodeBase64().toString()
    }

    private static String fakeKubectl() {
        '''#!/bin/bash
set -euo pipefail
[[ "$1" == --namespace ]]
namespace=$2
[[ "$3" == patch ]]
resource=$4
name=$5
shift 5
dry_run=false
[[ "$1" == --type=merge && "$2" == --patch-file=/dev/stdin ]]
shift 2
if [[ "$1" == --dry-run=server ]]; then
    dry_run=true
    shift
fi
[[ "$1" == -o && "$2" == name && $# == 2 ]]
case "$resource" in
    secret)
        [[ "$namespace" == infisical && "$name" == universal-auth-credentials ]]
        file="$TEST_STATE/credentials.json"
        ;;
    infisicalconnections.secrets.infisical.com)
        [[ "$namespace" == default && "$name" == infisical-prod ]]
        file="$TEST_STATE/connection.json"
        ;;
    *) exit 20 ;;
esac
printf '%s %s\\n' "$dry_run" "$resource" >> "$TEST_STATE/calls"
[[ -f "$file" ]] || { printf 'Resource not found\\n' >&2; exit 1; }
[[ "${TEST_DENY_RESOURCE:-}" != "$resource" ]] || { printf 'Patch forbidden\\n' >&2; exit 1; }
patch=$(jq -c '.')
if [[ "$dry_run" == false ]]; then
    jq --argjson patch "$patch" '. * $patch' "$file" > "$file.new"
    mv "$file.new" "$file"
fi
printf '%s/%s\\n' "$resource" "$name"
'''
    }
}
