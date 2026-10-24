import com.cloudbees.groovy.cps.NonCPS

/**
 * Returns the head and base commits of the current pull request build.
 *
 * It reads them from the SCM revision action of the current build. It runs no shell command and
 * needs no container.
 *
 * @return a map with head and base
 * @throws IllegalArgumentException when the build has no pull request revision or the commits
 *     cannot be read
 */
@NonCPS
Map call() {
    readRevision(currentBuild.rawBuild)
}

/**
 * Reads the pull request head and base commits from the SCM revision action of a build.
 *
 * @param rawBuild the build to read
 * @return a map with head and base
 * @throws IllegalArgumentException when the action, the revision or either commit is missing, or
 *     the SCM API classes cannot be loaded
 */
@NonCPS
Map readRevision(Object rawBuild) {
    String message = 'Cannot read the PR head and base commits from the build.'
    try {
        Class resolvedClass = getClass().classLoader.loadClass('jenkins.scm.api.SCMRevisionAction')
        def revision = rawBuild.getAction(resolvedClass)?.getRevision()
        if (revision == null) {
            throw new IllegalArgumentException(message)
        }
        String head = revision.getPullHash()?.toString()
        String base = revision.getBaseHash()?.toString()
        if (!head || !base) {
            throw new IllegalArgumentException(message)
        }
        return [head: head, base: base]
    } catch (ClassNotFoundException ignored) {
        throw new IllegalArgumentException(message)
    } catch (LinkageError ignored) {
        throw new IllegalArgumentException(message)
    } catch (MissingMethodException ignored) {
        throw new IllegalArgumentException(message)
    }
}
