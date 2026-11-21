package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * Runs the real folder shell functions with sh. A stub inf answers like Infisical CLI 0.45.14:
 * the same compact JSON for folders get, and exit 1 when a folder that exists is created again.
 */
class MinioFolderFunctionsTest {
    /** Captured from infisical 0.45.14: secrets folders get --path /minio/users -o json. */
    private static final String REAL_TWO_FOLDERS =
        '[{"folderId":"6bdd14ec-11ea-482f-b0f0-20b9899a608e","folderName":"longhorn","folderPath":"/minio/users"},' +
        '{"folderId":"376ce365-93e4-4f22-b794-1b665c2fb415","folderName":"longhorn-backup","folderPath":"/minio/users"}]'
    /** Captured from infisical 0.45.14 for a folder without subfolders. */
    private static final String REAL_NO_FOLDERS = 'null'

    private static final String STUB = '''
state="$work/state"
mkdir -p "$state/minio/users"
inf() {
  cmd="$1 $2 $3"
  shift 3
  path=""
  name=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --path) path="$2"; shift 2 ;;
      --name) name="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  case "$cmd" in
    "secrets folders get")
      if [ -n "${FIXTURE:-}" ]; then printf '%s' "$FIXTURE"; return 0; fi
      if [ ! -d "$state$path" ]; then echo "Folder with path '$path' was not found" >&2; return 1; fi
      items=""
      for d in "$state$path"/*; do
        if [ -d "$d" ]; then items="$items ${d##*/}"; fi
      done
      if [ -z "$items" ]; then printf 'null'; return 0; fi
      printf '['
      sep=""
      for f in $items; do
        printf '%s{"folderId":"id-%s","folderName":"%s","folderPath":"%s"}' "$sep" "$f" "$f" "$path"
        sep=","
      done
      printf ']'
      ;;
    "secrets folders create")
      if [ -d "$state$path/$name" ]; then
        echo "Folder with name '$name' already exists in path '$path'" >&2
        return 1
      fi
      mkdir -p "$state$path/$name"
      echo "$path/$name" >>"$work/created.log"
      ;;
    *) echo "unexpected inf call: $cmd" >&2; return 2 ;;
  esac
}
'''

    private static Map run(String body, Map<String, String> env = [:]) {
        String script = 'set -eu\nwork="$(mktemp -d)"\ntrap \'rm -rf "$work"\' EXIT\n' + STUB +
            MinioCommands.folderFunctions() + body
        ProcessBuilder builder = new ProcessBuilder('/bin/sh', '-c', script)
        builder.environment().putAll(env)
        Process process = builder.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), 'shell did not exit')
        [exit: process.exitValue(), out: process.inputStream.getText('UTF-8'), err: process.errorStream.getText('UTF-8')]
    }

    @Test
    void roThenRwCreatesTheNameFolderOnlyOnce() {
        Map result = run('''
ensure_folder /minio/users longhorn-backup
ensure_folder /minio/users/longhorn-backup ro
ensure_folder /minio/users longhorn-backup
ensure_folder /minio/users/longhorn-backup rw
cat "$work/created.log"
''')
        assertEquals(0, result.exit, result.err as String)
        assertEquals(['/minio/users/longhorn-backup', '/minio/users/longhorn-backup/ro',
                      '/minio/users/longhorn-backup/rw'], (result.out as String).readLines())
        assertTrue((result.err as String).contains('Infisical folder /minio/users/longhorn-backup exists.'))
    }

    @Test
    void hasFolderMatchesOnlyTheExactNameInRealOutput() {
        Map result = run('''
for n in longhorn-backup longhorn longhorn-back zz; do
  if has_folder /minio/users "$n"; then echo "$n yes"; else echo "$n no"; fi
done
''', [FIXTURE: REAL_TWO_FOLDERS])
        assertEquals(0, result.exit, result.err as String)
        assertEquals(['longhorn-backup yes', 'longhorn yes', 'longhorn-back no', 'zz no'], (result.out as String).readLines())
    }

    @Test
    void countFoldersReadsRealOutput() {
        assertEquals('2', (run('count_folders /minio/users\n', [FIXTURE: REAL_TWO_FOLDERS]).out as String).trim())
        assertEquals('0', (run('count_folders /minio/users/app\n', [FIXTURE: REAL_NO_FOLDERS]).out as String).trim())
    }

    @Test
    void aListingFailureIsNotReadAsAnEmptyFolder() {
        Map result = run('''
if has_folder /minio/missing app; then echo "has yes"; else echo "has no"; fi
if count="$(count_folders /minio/missing)"; then echo "count $count"; else echo "count failed"; fi
''')
        assertEquals(0, result.exit, result.err as String)
        assertEquals(['has no', 'count failed'], (result.out as String).readLines())
    }
}
