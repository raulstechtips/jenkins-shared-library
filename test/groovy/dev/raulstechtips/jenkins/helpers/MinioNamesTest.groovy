package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MinioNamesTest {
    private final MinioNames names = new MinioNames()

    @Test
    void environmentsAreFixed() {
        assertEquals(['dev', 'staging', 'prod'], names.environments())
    }

    @Test
    void validNamesAreTrimmedAndAccepted() {
        assertEquals('app', names.validateName('  app\n'))
        ['a', 'a1', 'my-app', '9lives', 'a-b-c'].each { assertEquals(it, names.validateName(it)) }
    }

    @Test
    void invalidNamesFail() {
        [null, '', '  ', 'App', '-app', 'app-', 'my_app', 'my.app', 'a b', 'a--b', 'a/b'].each { String name ->
            assertThrows(IllegalArgumentException) { names.validateName(name) }
        }
    }

    @Test
    void environmentMustBeKnown() {
        ['dev', 'staging', 'prod'].each { assertEquals(it, names.validateEnvironment(it)) }
        [null, '', 'test', 'DEV'].each { String env ->
            assertThrows(IllegalArgumentException) { names.validateEnvironment(env) }
        }
    }

    @Test
    void bucketLimitIncludesTheEnvironmentSuffix() {
        assertEquals(63, names.bucketName('a' * 59, 'dev').length())
        assertEquals(63, names.bucketName('a' * 58, 'prod').length())
        assertEquals(63, names.bucketName('a' * 55, 'staging').length())
        assertThrows(IllegalArgumentException) { names.bucketName('a' * 60, 'dev') }
        assertThrows(IllegalArgumentException) { names.bucketName('a' * 59, 'prod') }
        assertThrows(IllegalArgumentException) { names.bucketName('a' * 56, 'staging') }
    }

    @Test
    void shortestBucketNameFitsTheLowerLimit() {
        assertEquals('a-dev', names.bucketName('a', 'dev'))
    }

    @Test
    void doubleHyphenIsRejected() {
        assertThrows(IllegalArgumentException) { names.bucketName('my--app', 'dev') }
    }

    @Test
    void accountPoliciesAndPathsAreBuilt() {
        assertEquals('app-dev', names.bucketName('app', 'dev'))
        assertEquals('app-dev-ro', names.accountName('app', 'dev', 'ro'))
        assertEquals('/minio/users/app', names.namePath('app'))
        assertEquals('/minio/users/app/rw', names.keysPath('app', 'rw'))
    }

    @Test
    void checkedLevelsAreReturnedInLevelsYamlOrder() {
        assertEquals(['ro', 'rw'], names.levels([RW: true, RO: true], ['ro', 'rw']))
        assertEquals(['ro'], names.levels([NAME: 'app', RO: true, RW: false], ['ro', 'rw']))
        assertEquals(['rw'], names.levels([RO: false, RW: 'true'], ['ro', 'rw']))
        assertEquals(['ro', 'audit'], names.levels([RO: true, AUDIT: true], ['ro', 'rw', 'audit']))
    }

    @Test
    void noCheckedKnownLevelFails() {
        [null, [:], [RO: false, RW: false], [ADMIN: true], [ro: true], [AUDIT: true]].each { Map access ->
            IllegalArgumentException e = assertThrows(IllegalArgumentException) { names.levels(access, ['ro', 'rw']) }
            assertEquals('Select at least one access level: RO, RW.', e.message)
        }
    }

    @Test
    void bucketListIsSplitTrimmedDedupedAndSorted() {
        assertEquals(['a-bucket', 'b.bucket', 'c-bucket'],
            names.parseBuckets(' c-bucket,a-bucket\n\nb.bucket  a-bucket ,, \r\n c-bucket'))
    }

    @Test
    void emptyOrInvalidBucketListsFail() {
        [null, '', ' \n ,, '].each { String raw ->
            assertThrows(IllegalArgumentException) { names.parseBuckets(raw) }
        }
        ['ab', 'Bucket', '-bucket', 'bucket-', 'buck_et', 'a' * 64].each { String bucket ->
            assertThrows(IllegalArgumentException) { names.parseBuckets("good-one,${bucket}") }
        }
    }
}
