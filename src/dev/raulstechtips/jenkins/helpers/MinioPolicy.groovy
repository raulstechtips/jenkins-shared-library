package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/** Renders MinIO policy templates. Pure: no steps. */
class MinioPolicy implements Serializable {

    private static final String BUCKET_ARNS = '__BUCKET_ARNS__'
    private static final String OBJECT_ARNS = '__OBJECT_ARNS__'
    private static final String ARN_PREFIX = 'arn:aws:s3:::'

    @NonCPS
    String render(String templateText, List<String> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            throw new IllegalArgumentException('At least one bucket is required to render a policy.')
        }
        Object template
        try {
            template = new JsonSlurperClassic().parseText(templateText)
        } catch (Exception e) {
            throw new IllegalArgumentException("Policy template is not valid JSON: ${e.message}")
        }
        List<String> bucketArns = buckets.collect { ARN_PREFIX + it }
        List<String> objectArns = buckets.collect { ARN_PREFIX + it + '/*' }
        JsonOutput.toJson(substitute(template, bucketArns, objectArns))
    }

    @NonCPS
    private static Object substitute(Object node, List<String> bucketArns, List<String> objectArns) {
        if (node instanceof Map) {
            Map out = [:]
            node.each { k, v -> out[k] = substitute(v, bucketArns, objectArns) }
            return out
        }
        if (node instanceof List) {
            List out = []
            node.each { Object v ->
                if (v == BUCKET_ARNS) {
                    out.addAll(bucketArns)
                } else if (v == OBJECT_ARNS) {
                    out.addAll(objectArns)
                } else {
                    out << substitute(v, bucketArns, objectArns)
                }
            }
            return out
        }
        if (node == BUCKET_ARNS) {
            return bucketArns
        }
        if (node == OBJECT_ARNS) {
            return objectArns
        }
        node
    }
}
