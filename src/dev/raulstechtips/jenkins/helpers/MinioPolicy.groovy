package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/** Renders MinIO policy templates and reads bucket names from policies. Pure: no steps. */
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
    List<String> bucketsOf(String policyJson) {
        Object doc = new JsonSlurperClassic().parseText(policyJson)
        doc = unwrap(doc)
        Set<String> buckets = new TreeSet<String>()
        Object statements = doc instanceof Map ? doc.Statement : null
        if (statements instanceof Map) {
            statements = [statements]
        }
        (statements instanceof List ? statements : []).each { Object statement ->
            if (!(statement instanceof Map)) {
                return
            }
            Object resource = statement.Resource
            List resources = resource instanceof List ? resource : [resource]
            resources.each { Object arn ->
                if (arn instanceof String && arn.startsWith(ARN_PREFIX)) {
                    String bucket = arn.substring(ARN_PREFIX.length()).split('/', 2)[0]
                    if (!bucket.isEmpty() && bucket != '*') {
                        buckets << bucket
                    }
                }
            }
        }
        buckets.toList()
    }

    @NonCPS
    private static Object unwrap(Object doc) {
        if (doc instanceof Map) {
            Object inner = null
            if (doc.policyInfo instanceof Map && doc.policyInfo.Policy != null) {
                inner = doc.policyInfo.Policy
            } else if (doc.policy != null) {
                inner = doc.policy
            }
            if (inner != null) {
                return inner instanceof String ? new JsonSlurperClassic().parseText(inner) : inner
            }
        }
        doc
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
