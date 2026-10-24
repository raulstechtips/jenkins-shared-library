import com.cloudbees.groovy.cps.NonCPS

@NonCPS
Map call() {
    readRevision(currentBuild.rawBuild)
}

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
