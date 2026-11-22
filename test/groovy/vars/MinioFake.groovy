package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonOutput
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

/**
 * In-memory MinIO and Infisical for the MinIO step and pipeline tests.
 *
 * It answers the real MinioCommands scripts by their "# op:" line and their quoted shell
 * assignments, so the tests run the real steps and the real script builder.
 */
class MinioFake {
    static final Map<String, String> SECRETS = [
        MINIO_ACCESS_KEY: 'minio-access-sentinel',
        MINIO_SECRET_KEY: 'minio-secret-sentinel',
        INFISICAL_CLIENT_ID: 'infisical-id-sentinel',
        INFISICAL_CLIENT_SECRET: 'infisical-secret-sentinel'
    ]

    Set<String> buckets = [] as Set
    Map<String, String> policies = [:]
    Map<String, List<String>> users = [:]
    Map<String, List<String>> keys = [:]
    /** Infisical folders and the stored AWS_ACCESS_KEY_ID, key "env:path". */
    Set<String> folders = [] as Set
    Map<String, String> keyIds = [:]
    List<Map> calls = []
    List<String> logs = []
    List<String> credentialIds = []
    int installs = 0
    int nextKey = 0
    private String activeContainer
    private BasePipelineTest test

    List<String> ops() {
        calls.collect { it.op as String }
    }

    List<String> changes() {
        ops().findAll { !(it in ['list-buckets', 'policy-info', 'user-info', 'list-access-keys']) }
    }

    String keyId(String env, String path) {
        keyIds[env + ':' + path]
    }

    void storeKey(String env, String name, String level, String id) {
        folders << env + ':/minio/users/' + name
        folders << env + ':/minio/users/' + name + '/' + level
        keyIds[env + ':/minio/users/' + name + '/' + level] = id
    }

    void install(BasePipelineTest t) {
        test = t
        def helper = t.helper
        if (!t.binding.hasVariable('env')) {
            t.binding.setVariable('env', [:])
        }
        t.binding.getVariable('env').WORKSPACE = '/ws'
        helper.registerAllowedMethod('libraryResource', [String]) { String path -> new File('resources/' + path).text }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> new Yaml().load(args.text as String) }
        helper.registerAllowedMethod('echo', [String]) { String message -> logs << message }
        helper.registerAllowedMethod('error', [String]) { String message -> throw new IllegalStateException(message) }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            String previous = activeContainer
            activeContainer = name
            try { body.call() } finally { activeContainer = previous }
        }
        helper.registerAllowedMethod('installMinioTools', []) {
            installs++
            '/ws/.minio-tools/bin'
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List creds, Closure body ->
            Map env = t.binding.getVariable('env') as Map
            creds.each { Map c ->
                credentialIds << (c.credentialsId as String)
                env[c.usernameVariable] = SECRETS[c.usernameVariable]
                env[c.passwordVariable] = SECRETS[c.passwordVariable]
            }
            try { body.call() } finally { creds.each { Map c -> env.remove(c.usernameVariable); env.remove(c.passwordVariable) } }
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List pairs, Closure body ->
            Map env = t.binding.getVariable('env') as Map
            Map saved = [:]
            pairs.each { Object pair ->
                List<String> kv = pair.toString().split('=', 2) as List
                saved[kv[0]] = env[kv[0]]
                env[kv[0]] = kv[1]
            }
            try { body.call() } finally { saved.each { k, v -> v == null ? env.remove(k) : env.put(k, v) } }
        }
        helper.registerAllowedMethod('sh', [String]) { String script -> run(script) }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> run(args.script as String) }
        ['minioSession': Closure, 'minioCreateAccess': Map, 'minioCreateBucket': Map,
            'minioRotateKeys': Map, 'minioDeleteAccess': Map].each { String step, Class type ->
            helper.registerAllowedMethod(step, [type]) { Object arg -> t.loadScript('vars/' + step + '.groovy').call(arg) }
        }
    }

    private String run(String script) {
        Map env = test.binding.getVariable('env') as Map
        assertEquals('tools', activeContainer, 'MinIO scripts run in the tools container')
        assertEquals('/ws/.minio-tools/bin', env.MINIO_TOOLS_BIN)
        SECRETS.each { k, v ->
            assertEquals(v, env[k], k + ' is set by withCredentials')
            assertFalse(script.contains(v), 'secret value in script: ' + k)
        }
        assertTrue(script.startsWith('set +x\n'), script)
        String op = (script =~ /(?m)^# op: (\S+)$/)[0][1]
        Map<String, String> vars = [:]
        (script =~ /(?m)^([a-z_]+)='([^']*)'$/).each { List m -> vars[m[1]] = m[2] }
        calls << [op: op, vars: vars, script: script]
        String env2 = vars.infisical_env
        switch (op) {
            case 'list-buckets': return buckets.join('\n') + '\n'
            case 'create-bucket': buckets << vars.bucket; return ''
            case 'policy-info':
                return policies.containsKey(vars.policy) ?
                    JsonOutput.toJson([status: 'success', policy: vars.policy, policyInfo: [PolicyName: vars.policy, Policy: new groovy.json.JsonSlurper().parseText(policies[vars.policy])]]) + '\n' :
                    '__MISSING__\n'
            case 'create-policy':
                String line = script.readLines().find { it.startsWith("printf '%s' '") }
                policies[vars.policy] = line.substring("printf '%s' '".length(), line.lastIndexOf("' >"))
                return ''
            case 'user-info':
                return users.containsKey(vars.user) ?
                    JsonOutput.toJson([status: 'success', accessKey: vars.user, policyName: users[vars.user].join(',')]) + '\n' :
                    '__MISSING__\n'
            case 'add-user': users[vars.user] = []; return ''
            case 'attach-policy': users[vars.user] << vars.policy; return ''
            case 'detach-policy': users[vars.user].remove(vars.policy); return ''
            case 'remove-user': users.remove(vars.user); keys.remove(vars.user); return ''
            case 'remove-policy': policies.remove(vars.policy); return ''
            case 'list-access-keys': return (keys[vars.user] ?: []).join('\n') + '\n'
            case 'remove-access-key': keys[vars.user].remove(vars.key_id); return ''
            case 'create-keys':
                assertTrue(users.containsKey(vars.user), 'create-keys for a missing user')
                String id = 'NEWKEY' + (++nextKey)
                keys.computeIfAbsent(vars.user) { [] } << id
                folders << env2 + ':' + vars.name_path
                folders << env2 + ':' + vars.keys_path
                keyIds[env2 + ':' + vars.keys_path] = id
                return id + '\n'
            case 'delete-keys-folder':
                folders.remove(env2 + ':' + vars.keys_path)
                keyIds.remove(env2 + ':' + vars.keys_path)
                return ''
            case 'delete-name-folder':
                if (!folders.any { it.startsWith(env2 + ':' + vars.name_path + '/') }) {
                    folders.remove(env2 + ':' + vars.name_path)
                }
                return ''
            default: fail('unknown op ' + op)
        }
        ''
    }
}
