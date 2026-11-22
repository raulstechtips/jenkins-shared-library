package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioCommandsTest {
    private static final Map SETTINGS = [
        minioEndpoint: 'https://s3.example.test',
        infisicalApiUrl: 'https://infisical.example.test/api',
        infisicalProjectId: 'project-1'
    ]

    private final MinioCommands commands = new MinioCommands(SETTINGS)

    private Map<String, String> allScripts() {
        [
            'list-buckets': commands.listBuckets(),
            'create-bucket': commands.createBucket('app-dev'),
            'policy-info': commands.policyInfo('app-dev-ro'),
            'create-policy': commands.createPolicy('app-dev-ro', '{"Version":"2012-10-17"}'),
            'user-info': commands.userInfo('app-dev-ro'),
            'add-user': commands.addUser('app-dev-ro'),
            'attach-policy': commands.attachPolicy('app-dev-ro', 'app-dev-ro'),
            'detach-policy': commands.detachPolicy('app-dev-ro', 'app-dev-ro'),
            'remove-user': commands.removeUser('app-dev-ro'),
            'remove-policy': commands.removePolicy('app-dev-ro'),
            'list-access-keys': commands.listAccessKeys('app-dev-ro'),
            'remove-access-key': commands.removeAccessKey('app-dev-ro', 'KEY1'),
            'create-keys': commands.createKeys('app-dev-ro', 'dev', 'app', 'ro'),
            'delete-keys-folder': commands.deleteKeysFolder('dev', 'app', 'ro'),
            'delete-name-folder': commands.deleteNameFolderIfEmpty('dev', 'app')
        ]
    }

    @Test
    void allScriptsCoversEveryPublicScriptMethod() {
        Set<String> parsers = ['newKeyId'] as Set
        Set<String> methods = MinioCommands.declaredMethods.findAll {
            java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.synthetic && it.returnType == String &&
                !it.name.startsWith('get') && !it.name.startsWith('\$') && !(it.name in parsers)
        }*.name as Set
        Set<String> ops = allScripts().keySet().collect { String op ->
            op.split('-').toList().withIndex().collect { String part, int i -> i == 0 ? part : part.capitalize() }.join('')
        } as Set
        Map<String, String> renamed = [deleteNameFolder: 'deleteNameFolderIfEmpty']
        assertEquals(methods, ops.collect { renamed[it] ?: it } as Set)
    }

    @Test
    void everyScriptBeginsWithSetPlusXAndNamesItsOperation() {
        allScripts().each { String op, String script ->
            assertTrue(script.startsWith('set +x\n# op: ' + op + '\nset -eu\n'), script)
        }
    }

    @Test
    void everyScriptTrapsAndRemovesItsTempFolderBeforeAnyWork() {
        allScripts().each { String op, String script ->
            int mktemp = script.indexOf('work="$(mktemp -d)"')
            int trap = script.indexOf('trap \'rm -rf "$work"\' EXIT')
            assertTrue(mktemp > 0 && trap > mktemp, op)
            assertTrue(script.contains('umask 077'), op)
            ['mc ', 'infisical ', 'jq '].each { String tool ->
                int first = script.indexOf('\n' + tool)
                assertTrue(first < 0 || first > trap, op + ' runs ' + tool + 'before the trap')
            }
        }
    }

    @Test
    void secretsComeOnlyFromEnvironmentVariables() {
        allScripts().each { String op, String script ->
            assertFalse(script.contains('mc alias set'), op)
            assertFalse(script.contains('MC_HOST_'), op)
            assertFalse(script.contains('--token'), op)
            assertFalse(script.contains('--show-values'), op)
            List<String> printed = script.readLines().findAll {
                it.trim() ==~ /(echo|printf|cat)\b.*/ && !it.contains('| mc admin user add')
            }
            assertFalse(printed.any { it =~ /SECRET|secretKey|INFISICAL_TOKEN|password/ }, op + ': ' + printed)
            assertFalse(script.contains('--client-secret'), op)
            assertFalse(script.contains('infisical login'), op)
            assertFalse((script =~ '\\$\\{?INFISICAL_CLIENT_(ID|SECRET)').find(), op + ' passes an Infisical credential as an argument')
            assertFalse((script =~ '\\$\\{?MINIO_(ACCESS|SECRET)_KEY').find(), op + ' passes a MinIO credential as an argument')
            // Direct CLI calls exist only inside the inf() wrapper, which adds --domain.
            List<String> direct = script.readLines().findAll { it.contains('infisical ') && !it.contains('echo ') }
            assertTrue(direct.every { it.startsWith('inf() { infisical "$@"') && it.contains('--domain "$INFISICAL_API_URL"') }, op + ': ' + direct)
        }
    }

    @Test
    void aliasIsWrittenWithJqFromTheEnvironmentIntoAMode600Config() {
        String script = commands.listBuckets()
        assertTrue(script.contains('export MC_CONFIG_DIR="$work/mc"'), script)
        assertTrue(script.contains("jq -n --arg url 'https://s3.example.test'"), script)
        assertTrue(script.contains('accessKey: env.MINIO_ACCESS_KEY'), script)
        assertTrue(script.contains('secretKey: env.MINIO_SECRET_KEY'), script)
        assertTrue(script.contains('chmod 600 "$MC_CONFIG_DIR/config.json"'), script)
    }

    @Test
    void infisicalTokenComesFromLoginAndIsExported() {
        String script = commands.deleteKeysFolder('dev', 'app', 'ro')
        assertTrue(script.contains("jq -n '{clientId: env.INFISICAL_CLIENT_ID, clientSecret: env.INFISICAL_CLIENT_SECRET}' | " +
            "curl -fsS -X POST -H 'Content-Type: application/json' --data @- " +
            '"$INFISICAL_API_URL/v1/auth/universal-auth/login" >"$work/login.json"'), script)
        assertTrue(script.contains('INFISICAL_TOKEN="$(jq -r \'.accessToken // empty\' "$work/login.json")"'), script)
        assertTrue(script.indexOf('export INFISICAL_TOKEN') < script.indexOf('inf secrets folders delete'), script)
        assertTrue(script.contains('inf() { infisical "$@" --env "$infisical_env" --projectId "$project_id" --domain "$INFISICAL_API_URL" --silent; }'), script)
        assertTrue(script.contains("infisical_env='dev'"), script)
        assertTrue(script.contains("keys_path='/minio/users/app/ro'"), script)
        assertTrue(script.contains("project_id='project-1'"), script)
    }

    @Test
    void newKeysGoThroughModeSixHundredFilesAndOnlyTheIdIsPrinted() {
        String script = commands.createKeys('app-dev-ro', 'dev', 'app', 'ro')
        assertTrue(script.indexOf('ensure_folder "$keys_root" "$name"') < script.indexOf('ensure_folder "$name_path" "$level"'))
        assertTrue(script.indexOf('ensure_folder "$name_path" "$level"') < script.indexOf('mc admin accesskey create'))
        assertTrue(script.contains('mc admin accesskey create --json "$alias" "$user" >"$work/key.json"'), script)
        assertTrue(script.contains('chmod 600 "$work/key.json"'), script)
        assertTrue(script.contains('chmod 600 "$work/keys.env"'), script)
        assertTrue(script.contains('inf secrets set --file "$work/keys.env" --path "$keys_path"'), script)
        assertTrue(script.trim().endsWith('printf \'%s\\n\' "$key_id"'), script)
        assertTrue(script.contains("keys_root='/minio/users'"), script)
        assertFalse(script.contains('accesskey rm'), 'createKeys must not remove keys; the next run cleans up')
    }

    @Test
    void userPasswordIsGeneratedInTheShellAndSentOnStdin() {
        String script = commands.addUser('app-dev-ro')
        assertTrue(script.contains('password="$(head -c 24 /dev/urandom | base64)"'), script)
        assertTrue(script.contains('| mc admin user add "$alias" >/dev/null'), script)
    }

    @Test
    void namesAreQuotedShellAssignments() {
        String script = commands.createBucket("x'; touch BAD; '")
        assertTrue(script.contains("bucket='x'\\''; touch BAD; '\\'''"), script)
        assertTrue(commands.createPolicy('p', "{\"a\":\"'\"}").contains("printf '%s' '{\"a\":\"'\\''\"}'"))
    }

    @Test
    void infoScriptsReportMissingOnlyForDoesNotExist() {
        String script = commands.policyInfo('app-dev-ro')
        assertTrue(script.contains("grep -qiE 'does not exist'"), script)
        assertTrue(script.contains("echo '__MISSING__'"), script)
        assertTrue(commands.missing('__MISSING__\n'))
        assertFalse(commands.missing('{"status":"success"}'))
    }

    @Test
    void parsesOutputs() {
        assertEquals(['a', 'b'], commands.lines(' a\n\n b \n'))
        assertEquals('KEY2', commands.newKeyId('noise\nKEY2\n'))
        assertThrows(IllegalStateException) { commands.newKeyId('') }
        assertEquals(['app-dev-ro', 'other'],
            commands.attachedPolicies('{"status":"success","accessKey":"u","policyName":"app-dev-ro,other"}'))
        assertEquals([], commands.attachedPolicies('{"status":"success","accessKey":"u"}'))
        assertEquals(['p'], commands.attachedPolicies('junk\n{"policyName":"p"}'))
    }

    @Test
    void missingSettingsFail() {
        assertThrows(IllegalArgumentException) { new MinioCommands([:]) }
        assertThrows(IllegalArgumentException) { new MinioCommands(SETTINGS + [infisicalProjectId: ' ']) }
    }

    @Test
    void infisicalPathsAreValidated() {
        assertThrows(IllegalArgumentException) { commands.deleteKeysFolder('qa', 'app', 'ro') }
        assertThrows(IllegalArgumentException) { commands.deleteKeysFolder('dev', '../x', 'ro') }
    }
}
