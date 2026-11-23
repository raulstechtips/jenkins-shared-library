package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/** Validates MinIO names and builds bucket, account and Infisical path names. Pure: no steps. */
class MinioNames implements Serializable {

    private static final String NAME_PATTERN = '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
    private static final String BUCKET_PATTERN = '^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$'
    private static final String KEYS_ROOT = '/minio/users'

    @NonCPS
    List<String> environments() {
        ['dev', 'staging', 'prod']
    }

    @NonCPS
    String validateName(String name) {
        String value = name == null ? '' : name.trim()
        if (value.isEmpty()) {
            throw new IllegalArgumentException('NAME must not be empty.')
        }
        if (!(value ==~ NAME_PATTERN)) {
            throw new IllegalArgumentException("NAME '${value}' must use only lowercase letters, digits and '-', and must start and end with a letter or digit.")
        }
        if (value.contains('--')) {
            throw new IllegalArgumentException("NAME '${value}' must not contain '--'.")
        }
        value
    }

    @NonCPS
    String validateEnvironment(String env) {
        String value = env == null ? '' : env.trim()
        if (!(value in environments())) {
            throw new IllegalArgumentException("ENVIRONMENT '${value}' must be one of: ${environments().join(', ')}.")
        }
        value
    }

    @NonCPS
    String bucketName(String name, String env) {
        String bucket = "${validateName(name)}-${validateEnvironment(env)}"
        if (bucket.length() < 3 || bucket.length() > 63) {
            throw new IllegalArgumentException("Bucket name '${bucket}' must be 3 to 63 characters long, including the environment suffix.")
        }
        bucket
    }

    @NonCPS
    String accountName(String name, String env, String level) {
        "${bucketName(name, env)}-${validateLevel(level)}"
    }

    @NonCPS
    String namePath(String name) {
        "${KEYS_ROOT}/${validateName(name)}"
    }

    @NonCPS
    String keysPath(String name, String level) {
        "${namePath(name)}/${validateLevel(level)}"
    }

    /**
     * @param access the job parameters: one checkbox per level, named as the level in uppercase (RO, RW)
     * @param knownLevels the levels from levels.yaml
     * @return the checked levels, in the knownLevels order
     * @throws IllegalArgumentException when no level is checked
     */
    @NonCPS
    List<String> levels(Map access, List<String> knownLevels) {
        List<String> selected = knownLevels.findAll { String level ->
            access != null && access[level.toUpperCase()]?.toString() == 'true'
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Select at least one access level: ${knownLevels.collect { it.toUpperCase() }.join(', ')}.")
        }
        selected
    }

    @NonCPS
    List<String> parseBuckets(String raw) {
        List<String> items = (raw == null ? '' : raw).split('[,\\s]+').collect { it.trim() }.findAll { !it.isEmpty() }.unique().sort()
        if (items.isEmpty()) {
            throw new IllegalArgumentException('BUCKETS must contain at least one bucket name.')
        }
        items.each { String bucket ->
            if (!(bucket ==~ BUCKET_PATTERN)) {
                throw new IllegalArgumentException("Bucket '${bucket}' is not a valid S3 bucket name (3 to 63 characters of a-z, 0-9, '.' and '-').")
            }
        }
        items
    }

    @NonCPS
    private static String validateLevel(String level) {
        String value = level == null ? '' : level.trim()
        if (!(value ==~ '^[a-z0-9]+$')) {
            throw new IllegalArgumentException("Level '${value}' must use only lowercase letters and digits.")
        }
        value
    }
}
