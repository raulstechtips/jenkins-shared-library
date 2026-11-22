package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurperClassic
import groovy.transform.PackageScope

/**
 * Builds the shell scripts of the MinIO jobs and reads their output. Pure: no steps.
 *
 * Every script starts with set +x, works in a mktemp -d folder that a trap removes on exit, and
 * reads secrets only from the environment (MINIO_ACCESS_KEY, MINIO_SECRET_KEY, INFISICAL_CLIENT_ID,
 * INFISICAL_CLIENT_SECRET). Groovy puts only names, paths and settings in a script, always quoted.
 * The second line of each script is "# op: <name>", so a log reader and the tests can identify it.
 * Scripts with output for the caller must run with returnStdout: true.
 */
class MinioCommands implements Serializable {

    /** The mc alias that every script writes into its own config.json. */
    static final String ALIAS = 'minio'
    /** Output of policyInfo and userInfo when the item does not exist. */
    static final String MISSING = '__MISSING__'

    // MinIO says "The specified user does not exist" and "The canned policy does not exist".
    // Network errors (for example "no such host") must not match, so they fail the script.
    private static final String MISSING_PATTERN = 'does not exist'

    private final String endpoint
    private final String apiUrl
    private final String projectId
    private final MinioNames names = new MinioNames()

    /**
     * @param settings the settings.yaml map; reads minioEndpoint, infisicalApiUrl and infisicalProjectId
     * @throws IllegalArgumentException when one of the three values is missing
     */
    MinioCommands(Map settings) {
        endpoint = required(settings, 'minioEndpoint')
        apiUrl = required(settings, 'infisicalApiUrl')
        projectId = required(settings, 'infisicalProjectId')
    }

    /** Prints one bucket name per line. */
    @NonCPS
    String listBuckets() {
        mcScript('list-buckets', [:],
            'mc ls --json "$alias" >"$work/ls.json"\n' +
            'jq -r \'select(.type == "folder") | .key | rtrimstr("/")\' "$work/ls.json"\n')
    }

    @NonCPS
    String createBucket(String bucket) {
        mcScript('create-bucket', [bucket: bucket],
            'mc mb "$alias/$bucket" >/dev/null\n' +
            'echo "Created bucket $bucket."\n')
    }

    /** Prints the mc policy info JSON, or MISSING. */
    @NonCPS
    String policyInfo(String policy) {
        mcScript('policy-info', [policy: policy], infoBody('mc admin policy info --json "$alias" "$policy"'))
    }

    @NonCPS
    String createPolicy(String policy, String policyJson) {
        mcScript('create-policy', [policy: policy],
            'printf \'%s\' ' + ShellQuote.quote(policyJson) + ' >"$work/policy.json"\n' +
            'mc admin policy create "$alias" "$policy" "$work/policy.json" >/dev/null\n' +
            'echo "Created policy $policy."\n')
    }

    /** Prints the mc user info JSON, or MISSING. */
    @NonCPS
    String userInfo(String user) {
        mcScript('user-info', [user: user], infoBody('mc admin user info --json "$alias" "$user"'))
    }

    /** Adds the user with a random password that the script never prints or keeps. */
    @NonCPS
    String addUser(String user) {
        mcScript('add-user', [user: user],
            'password="$(head -c 24 /dev/urandom | base64)"\n' +
            'printf \'%s\\n%s\\n\' "$user" "$password" | mc admin user add "$alias" >/dev/null\n' +
            'unset password\n' +
            'echo "Created user $user."\n')
    }

    @NonCPS
    String attachPolicy(String policy, String user) {
        mcScript('attach-policy', [policy: policy, user: user],
            'mc admin policy attach --user "$user" "$alias" "$policy" >/dev/null\n' +
            'echo "Attached policy $policy to user $user."\n')
    }

    @NonCPS
    String detachPolicy(String policy, String user) {
        mcScript('detach-policy', [policy: policy, user: user],
            'mc admin policy detach --user "$user" "$alias" "$policy" >/dev/null\n' +
            'echo "Detached policy $policy from user $user."\n')
    }

    @NonCPS
    String removeUser(String user) {
        mcScript('remove-user', [user: user],
            'mc admin user rm "$alias" "$user" >/dev/null\n' +
            'echo "Removed user $user."\n')
    }

    @NonCPS
    String removePolicy(String policy) {
        mcScript('remove-policy', [policy: policy],
            'mc admin policy rm "$alias" "$policy" >/dev/null\n' +
            'echo "Removed policy $policy."\n')
    }

    /** Prints the access key IDs of the user, one per line. */
    @NonCPS
    String listAccessKeys(String user) {
        mcScript('list-access-keys', [user: user],
            'mc admin accesskey ls --json "$alias" "$user" >"$work/keys.json"\n' +
            'jq -r \'..|.accessKey? // empty\' "$work/keys.json" >"$work/ids"\n' +
            'grep -vxF "$user" "$work/ids" | sort -u || true\n')
    }

    @NonCPS
    String removeAccessKey(String user, String keyId) {
        mcScript('remove-access-key', [user: user, key_id: keyId],
            'mc admin accesskey rm "$alias" "$key_id" >/dev/null\n' +
            'echo "Removed access key $key_id of user $user."\n')
    }

    /**
     * Creates the Infisical folders when missing, creates a new access key for the user, and writes
     * both keys to Infisical from a mode-600 .env file. Prints only the new access key ID (last line).
     * When the Infisical write fails, the script fails. The rotate job then makes a new key and removes the others.
     */
    @NonCPS
    String createKeys(String user, String env, String name, String level) {
        Map vars = [user: user] + infisicalVars(env, name, level)
        infisicalScript('create-keys', vars, true,
            'ensure_folder "$keys_root" "$name"\n' +
            'ensure_folder "$name_path" "$level"\n' +
            'if ! mc admin accesskey create --json "$alias" "$user" >"$work/key.json"; then\n' +
            '  echo "mc could not create an access key for user $user." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            'chmod 600 "$work/key.json"\n' +
            'if ! jq -e \'(.accessKey | type == "string" and length > 0) and (.secretKey | type == "string" and length > 0)\' "$work/key.json" >/dev/null; then\n' +
            '  echo "mc returned no access key for user $user." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            'key_id="$(jq -r .accessKey "$work/key.json")"\n' +
            'jq -r \'"AWS_ACCESS_KEY_ID=" + .accessKey, "AWS_SECRET_ACCESS_KEY=" + .secretKey\' "$work/key.json" >"$work/keys.env"\n' +
            'chmod 600 "$work/keys.env"\n' +
            'if ! inf secrets set --file "$work/keys.env" --path "$keys_path" >/dev/null 2>&1; then\n' +
            '  echo "Infisical write to $keys_path failed." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            'echo "Wrote access key $key_id of user $user to Infisical $infisical_env $keys_path." >&2\n' +
            'printf \'%s\\n\' "$key_id"\n')
    }

    /** Deletes the Infisical folder of one level. A missing folder is skipped. */
    @NonCPS
    String deleteKeysFolder(String env, String name, String level) {
        infisicalScript('delete-keys-folder', infisicalVars(env, name, level), false,
            'if has_folder "$name_path" "$level"; then\n' +
            '  inf secrets folders delete --path "$name_path" --name "$level" >/dev/null\n' +
            '  echo "Deleted Infisical folder $keys_path."\n' +
            'else\n' +
            '  echo "Infisical folder $keys_path does not exist. Skipped."\n' +
            'fi\n')
    }

    /** Deletes the Infisical folder of the name when it has no subfolders and no secrets. */
    @NonCPS
    String deleteNameFolderIfEmpty(String env, String name) {
        Map vars = infisicalVars(env, name, null)
        infisicalScript('delete-name-folder', vars, false,
            'if ! has_folder "$keys_root" "$name"; then\n' +
            '  echo "Infisical folder $name_path does not exist. Skipped."\n' +
            '  exit 0\n' +
            'fi\n' +
            'if ! subfolders="$(count_folders "$name_path")"; then\n' +
            '  echo "Cannot list the subfolders of $name_path." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            '# secrets get needs a secret name, so export lists the folder. The file is mode 600 in $work.\n' +
            'if ! inf export --path "$name_path" --format dotenv --output-file "$work/name.env" >/dev/null 2>&1; then\n' +
            '  echo "Cannot list the secrets of $name_path." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            'secrets="$(grep -c \'=\' "$work/name.env" || true)"\n' +
            'if [ "$subfolders" = "0" ] && [ "$secrets" = "0" ]; then\n' +
            '  inf secrets folders delete --path "$keys_root" --name "$name" >/dev/null\n' +
            '  echo "Deleted Infisical folder $name_path."\n' +
            'else\n' +
            '  echo "Infisical folder $name_path is not empty ($subfolders subfolders, $secrets secrets). Kept."\n' +
            'fi\n')
    }

    /** @return true when the output of policyInfo or userInfo is MISSING */
    @NonCPS
    boolean missing(String output) {
        output != null && output.trim() == MISSING
    }

    /** @return the non-empty trimmed lines of a script output */
    @NonCPS
    List<String> lines(String output) {
        (output == null ? '' : output).readLines().collect { it.trim() }.findAll { !it.isEmpty() }
    }

    /**
     * @param output the createKeys output
     * @return the new access key ID (the last line)
     * @throws IllegalStateException when the output has no valid access key ID
     */
    @NonCPS
    String newKeyId(String output) {
        List<String> all = lines(output)
        String id = all.isEmpty() ? '' : all.last()
        if (!(id ==~ '[A-Za-z0-9._+=/-]+')) {
            throw new IllegalStateException('createKeys did not return an access key ID.')
        }
        id
    }

    /** @return the policies attached to the user, from the mc user info JSON (policyName, comma list) */
    @NonCPS
    List<String> attachedPolicies(String userInfoJson) {
        Object doc = null
        try {
            doc = new JsonSlurperClassic().parseText(userInfoJson)
        } catch (Exception ignored) {
            for (String line : lines(userInfoJson)) {
                try {
                    Object item = new JsonSlurperClassic().parseText(line)
                    if (item instanceof Map && item.policyName != null) {
                        doc = item
                    }
                } catch (Exception ignoredLine) {
                    // Not a JSON line.
                }
            }
        }
        Object value = doc instanceof Map ? ((Map) doc).policyName : null
        if (value instanceof List) {
            return value.collect { it.toString().trim() }.findAll { !it.isEmpty() }
        }
        (value == null ? '' : value.toString()).split(',').collect { it.trim() }.findAll { !it.isEmpty() }
    }

    @NonCPS
    private String mcScript(String op, Map vars, String body) {
        header(op, vars) + mcSetup() + body
    }

    @NonCPS
    private String infisicalScript(String op, Map vars, boolean withMc, String body) {
        header(op, vars) + (withMc ? mcSetup() : '') + infisicalSetup() + body
    }

    @NonCPS
    private Map infisicalVars(String env, String name, String level) {
        String namePath = names.namePath(name)
        Map vars = [
            infisical_env: names.validateEnvironment(env),
            name: name,
            keys_root: namePath.substring(0, namePath.lastIndexOf('/')),
            name_path: namePath
        ]
        if (level != null) {
            vars.level = level
            vars.keys_path = names.keysPath(name, level)
        }
        vars
    }

    @NonCPS
    private static String header(String op, Map vars) {
        StringBuilder text = new StringBuilder('set +x\n# op: ' + op + '\nset -eu\n')
        vars.each { k, v -> text.append(k).append('=').append(ShellQuote.quote(v as String)).append('\n') }
        text.append('alias=').append(ShellQuote.quote(ALIAS)).append('\n')
        text.append('work="$(mktemp -d)"\n')
        text.append('trap \'rm -rf "$work"\' EXIT\n')
        text.append('trap \'exit 1\' HUP INT TERM\n')
        text.append('umask 077\n')
        text.append('export HOME="$work/home"\n')
        text.append('mkdir -p "$HOME"\n')
        text.append('export PATH="$MINIO_TOOLS_BIN:$PATH"\n')
        text.toString()
    }

    @NonCPS
    private String mcSetup() {
        'export MC_CONFIG_DIR="$work/mc"\n' +
            'mkdir -p "$MC_CONFIG_DIR"\n' +
            'jq -n --arg url ' + ShellQuote.quote(endpoint) +
            ' \'{version: "10", aliases: {"' + ALIAS + '": {url: $url, accessKey: env.MINIO_ACCESS_KEY, ' +
            'secretKey: env.MINIO_SECRET_KEY, api: "S3v4", path: "auto"}}}\' >"$MC_CONFIG_DIR/config.json"\n' +
            'chmod 600 "$MC_CONFIG_DIR/config.json"\n'
    }

    @NonCPS
    private String infisicalSetup() {
        'export INFISICAL_API_URL=' + ShellQuote.quote(apiUrl) + '\n' +
            'export INFISICAL_DOMAIN="$INFISICAL_API_URL"\n' +
            'project_id=' + ShellQuote.quote(projectId) + '\n' +
            'jq -n \'{clientId: env.INFISICAL_CLIENT_ID, clientSecret: env.INFISICAL_CLIENT_SECRET}\' | ' +
            'curl -fsS -X POST -H \'Content-Type: application/json\' --data @- ' +
            '"$INFISICAL_API_URL/v1/auth/universal-auth/login" >"$work/login.json"\n' +
            'INFISICAL_TOKEN="$(jq -r \'.accessToken // empty\' "$work/login.json")"\n' +
            'rm -f "$work/login.json"\n' +
            'if [ -z "$INFISICAL_TOKEN" ]; then\n' +
            '  echo "Infisical login returned no token." >&2\n' +
            '  exit 1\n' +
            'fi\n' +
            'export INFISICAL_TOKEN\n' +
            'inf() { infisical "$@" --env "$infisical_env" --projectId "$project_id" --domain "$INFISICAL_API_URL" --silent; }\n' +
            folderFunctions()
    }

    /**
     * Shell functions for Infisical folders. They need only sh and grep, so the tests run them in CI.
     * The pinned CLI prints `folders get -o json` as compact JSON:
     * [{"folderId":"...","folderName":"...","folderPath":"..."}], or null when there are no subfolders.
     */
    @PackageScope
    @NonCPS
    static String folderFunctions() {
        'list_folders() {\n' +
            '  inf secrets folders get --path "$1" -o json >"$work/folders.json" 2>"$work/folders.err"\n' +
            '}\n' +
            'has_folder() {\n' +
            '  list_folders "$1" || return 1\n' +
            '  grep -qF "\\"folderName\\":\\"$2\\"" "$work/folders.json"\n' +
            '}\n' +
            'count_folders() {\n' +
            '  list_folders "$1" || return 1\n' +
            '  grep -o \'"folderName":"\' "$work/folders.json" | wc -l | tr -d \' \'\n' +
            '}\n' +
            'ensure_folder() {\n' +
            '  if has_folder "$1" "$2"; then\n' +
            '    echo "Infisical folder $1/$2 exists." >&2\n' +
            '  else\n' +
            '    inf secrets folders create --path "$1" --name "$2" >/dev/null\n' +
            '    echo "Created Infisical folder $1/$2." >&2\n' +
            '  fi\n' +
            '}\n'
    }

    @NonCPS
    private static String infoBody(String command) {
        'if ' + command + ' >"$work/out" 2>&1; then\n' +
            '  cat "$work/out"\n' +
            'elif grep -qiE \'' + MISSING_PATTERN + '\' "$work/out"; then\n' +
            '  echo \'' + MISSING + '\'\n' +
            'else\n' +
            '  cat "$work/out" >&2\n' +
            '  exit 1\n' +
            'fi\n'
    }

    @NonCPS
    private static String required(Map settings, String key) {
        Object value = settings == null ? null : settings[key]
        if (value == null || !value.toString().trim()) {
            throw new IllegalArgumentException("settings.yaml must define ${key}")
        }
        value.toString().trim()
    }
}
