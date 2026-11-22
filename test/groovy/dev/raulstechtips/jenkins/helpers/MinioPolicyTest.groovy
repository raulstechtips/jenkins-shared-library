package dev.raulstechtips.jenkins.helpers

import groovy.json.JsonSlurperClassic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioPolicyTest {
    private static final String BASE = 'resources/dev/raulstechtips/jenkins/minio/'
    private final MinioPolicy policy = new MinioPolicy()

    private static String template(String level) {
        new File(BASE + 'policies/' + level + '.json').text
    }

    private static Map parse(String json) {
        new JsonSlurperClassic().parseText(json) as Map
    }

    @Test
    void renderForOneBucketHasBothArnForms() {
        String json = policy.render(template('ro'), ['app-dev'])
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('__'))
        List statements = parse(json).Statement
        assertEquals(['arn:aws:s3:::app-dev'], statements[0].Resource)
        assertEquals(['arn:aws:s3:::app-dev/*'], statements[1].Resource)
        assertEquals(['s3:GetBucketLocation', 's3:ListBucket'], statements[0].Action)
        assertEquals(['s3:GetObject'], statements[1].Action)
    }

    @Test
    void renderForTwoBucketsListsEveryArn() {
        List statements = parse(policy.render(template('rw'), ['a-dev', 'b.dev'])).Statement
        assertEquals(['arn:aws:s3:::a-dev', 'arn:aws:s3:::b.dev'], statements[0].Resource)
        assertEquals(['arn:aws:s3:::a-dev/*', 'arn:aws:s3:::b.dev/*'], statements[1].Resource)
        assertTrue(statements[1].Action.containsAll(['s3:PutObject', 's3:DeleteObject']))
    }

    @Test
    void renderNeedsBucketsAndValidJson() {
        assertThrows(IllegalArgumentException) { policy.render(template('ro'), []) }
        assertThrows(IllegalArgumentException) { policy.render('{not json', ['a-dev']) }
    }

    @Test
    void aNewTemplateAndALevelLineAreEnough() {
        // A new level needs only a template file and a line in levels.yaml: no code change.
        String levels = new File(BASE + 'levels.yaml').text
        assertTrue(levels.contains('ro') && levels.contains('rw'))
        String audit = '{"Version":"2012-10-17","Statement":[{"Effect":"Allow",' +
            '"Action":["s3:GetBucketLocation"],"Resource":"__BUCKET_ARNS__"},' +
            '{"Effect":"Allow","Action":["s3:GetObjectTagging"],"Resource":"__OBJECT_ARNS__"}]}'
        String json = policy.render(audit, ['new-dev'])
        assertEquals(['arn:aws:s3:::new-dev'], parse(json).Statement[0].Resource)
        assertEquals(['arn:aws:s3:::new-dev/*'], parse(json).Statement[1].Resource)
        assertEquals(['audit'], new MinioNames().levels([AUDIT: true], ['ro', 'rw', 'audit']))
    }

}
