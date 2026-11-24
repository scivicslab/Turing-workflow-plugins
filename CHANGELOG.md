# Changelog

## 5.0.0

Changes since `v4.8.0` (`7a76ef5`). The plugins move to 5.0.0 together with POJO-actor 5.0.0 and Turing-workflow 5.0.0 (see their `CHANGELOG.md`). Most entries below follow one rule: an action that did not do all its work returns `false` with the reason, and a wait on another process or server has a limit.

Breaking changes for Java callers are in `plugin-rst-frame`: the public constructor `BookStore(String)` is gone, and a books database from before schema version 2 is refused until it is migrated.

### `plugin-chatui` replaces `plugin-chatui3`

Commit `0822dd8`. `plugin-chatui3`, which called quarkus-chat-ui3, is removed. `plugin-chatui` calls the chat-ui that runs today (chat-ui-with-audit-trail, through `/api/projects` and `/api/sessions`). Its actions work on one conversation, `project1/chat-01` at first: `setBaseUrl`, `setChat`, `setTimeLimits`, `send`, `cancel`, `getStatus`, `getConversation`, `clearConversation`.

**What 4.8 YAML must change:** a workflow that loaded `plugin-chatui3` loads `plugin-chatui` and uses its actions.

### `plugin-ssh`: a host key that is not in `known_hosts` is refused

Commit `977c3ad`. Up to 4.8.0, with no `~/.ssh/known_hosts`, every host key was accepted on every connection, so anyone on the network path could answer as the host. Now a key that is not in `~/.ssh/known_hosts` is refused, for the jump host too. A program that must accept unknown keys says so by name: `-Dturingworkflow.ssh.acceptUnknownHostKeys=true`, which logs a warning at every connection. `NodeInterpreter` also quotes and checks every path and URL it puts in a shell command line (`538080a`).

**What 4.8 code must change:** add each host to `~/.ssh/known_hosts` (`ssh-keyscan`, after checking the fingerprint).

### Listening and inputs: `log-serve` binds to 127.0.0.1; the book steps' `db` is a file path

- `log-serve` listens on 127.0.0.1 unless `--bind` names another address, and says so when it does (`869e2b6`).
- The `db` argument of the `plugin-rst-frame` book steps is a file path and can no longer add settings to the JDBC URL (`5826f1d`).

### Threads: no real thread outside the `ManagedThreadPool`

Commits `5fc9247`, `d2b3c35`, `58836d9`, `b29724f`, `af2954b`, `916f5a5`.

- Every `HttpClient` is built with a virtual-thread executor; without one the JDK makes a pool of real threads of its own.
- `plugin-rst-frame` batches run their items on virtual threads, at most `parallel` at once.
- The database write pool of `plugin-log-db` and `plugin-inventory` is a `ManagedThreadPool`.
- `NodeGroupActor.apply` orchestrates like the interpreter's `apply`: a plain step runs its nodes in parallel, each node's work in that node's turn.
- The guard test of `module-path-check` reads every module's `src/main/java` and fails on a real thread made outside the `ManagedThreadPool`.

**What 4.8 YAML or code must change:** nothing. Nodes of one `apply` may now run at the same time.

### A reply cut off at the token limit, or with no text, is a failure

Commits `a66ef65`, `406c0f3`, `488668c`. `plugin-llm` (`callOpenAi`), `plugin-kana-kanji` (vLLM) and `plugin-rst-frame` treat a reply cut off at `max_tokens`, empty or unreadable as a failed action or item, with the reason, instead of using the partial text.

### Dependencies

`org.json` 20260719, which fixes CVE-2026-59171 (an unbounded number in `JSONTokener`) (`1368cf0`).

### `plugin-codedoc`: lists come back in the result; lists and texts go in as values

Commit `d7fb3ee`. Needs Turing-workflow 5.0.0, which removes the `list` and `str` actors these actions used.

Up to 4.8.0, every `CodeDocActor` action took the name of a `list` or `str` actor (`listActor`, `strActor`) and wrote into it or read from it. The action names stay; their arguments and results change. An action that cannot do its work is false, with the reason (for example `readFileToStr: <path>` for a file that is not there). Now:

| Action | 4.8 arguments → what it did | 5.0 arguments → what it answers |
|---|---|---|
| `chunkFile` | `{file, listActor, maxChars}` → added the chunks to the list, answered their count | `{file, maxChars}` → the chunks as a JSON array |
| `scanProject` | `{directory, listActor, maxChars}` → added the files, answered their count | `{directory, maxChars}` → the files as a JSON array |
| `listMarkdownNoDescription` | `{directory, listActor}` → added the paths, answered their count | `{directory}` → the paths as a JSON array |
| `writeFileFromList` | `{file, listActor, separator}` | `{file, items, separator}` → the output path |
| `joinListToStr` | `{listActor, strActor, separator}` → stored the text in the `str` actor | `{items, separator}` → the joined text |
| `readFileToStr` | `{file, strActor}` → stored the content in the `str` actor | `{file}` → the content |
| `writeStrToFile` | `{file, strActor}` | `{file, content}` → the output path |
| `setFrontmatterDescription` | `{file, strActor}` | `{file, description}` |

**What 4.8 YAML must change:**

```yaml
# 4.8
      - actor: codedoc
        method: chunkFile
        arguments: {file: doc.md, listActor: "list:chunks"}
      - actor: codedoc
        method: writeFileFromList
        arguments: {file: out.md, listActor: "list:chunks"}

# 5.0
      - actor: codedoc
        method: chunkFile
        arguments: {file: doc.md}
      - actor: this
        method: putJson
        arguments: {path: chunks, value: "jexl: result"}
      - actor: codedoc
        method: writeFileFromList
        arguments: {file: out.md, items: "jexl: state.select('chunks')"}
```

No workflow in `~/works` called these actions.

### `plugin-rst-frame`: the books database is at schema version 2; `BookStore(String)` is replaced by `forWriting` and `forReading`

Commits `cadc4fd`, `92960df`. New public classes `BookSchema` and `BookMigration`. New public methods `BookStore.forWriting(String)`, `BookStore.forReading(String)`, `BookStore.dataVersion()` and `BookStore.saveEntities(String, String, List<Entity>, Set<String>)`. Removed: the public constructor `BookStore(String url)`.

Up to 4.8.0, every text column was a CLOB. Reading a CLOB makes H2 copy it into a temporary large object inside the database file, so a process that only read the books wrote to the file and grew it. Every `BookStore` also ran DDL when it opened a database.

Now:

- Every text column is `VARCHAR(1000000)`. Reading writes nothing.
- The table `schema_version` holds the version, and `BookSchema.VERSION` is 2.
- `BookStore.forWriting(url)` creates the tables of an empty database, and uses a database at version 2 as it is, with no DDL.
- `BookStore.forReading(url)` checks the version and runs no DDL.
- Both refuse, with an `SQLException`, a database from before the schema versions and a database at another version.
- `data_version` counts the changes: every write adds one inside its own transaction, and `BookStore.dataVersion()` reads it. The twelve writes that ran without a transaction now run in one. A reader that keeps what it read can watch this number instead of the file's modification time.
- The indexes `frame_registry_member(id)`, `frame_registry(parent)` and `frame_relation(to_id)` are gone. No query used them.

**What 4.8 code must change:**

```java
// 4.8
try (BookStore store = new BookStore("jdbc:h2:" + path + ";AUTO_SERVER=TRUE")) { ... }

// 5.0: a process that writes
try (BookStore store = BookStore.forWriting("jdbc:h2:" + path + ";AUTO_SERVER=TRUE")) { ... }
// 5.0: a process that only reads (for example a viewer)
try (BookStore store = BookStore.forReading("jdbc:h2:" + path + ";AUTO_SERVER=TRUE")) { ... }
```

An existing database must be converted once, before any 5.0 process opens it, by running the `BookImport` command-line tool of `plugin-rst-frame`:

```
BookImport migrate <db-file>
```

The tool works in this order:

1. It backs the file up and prints the backup's SHA-256.
2. It converts the file.
3. It checks that every table kept its rows, characters and row hashes, and restores the backup if any check fails.
4. It compacts the file.

Every process that opens the file must stop during the conversion.

### `plugin-rst-frame`: a batch with a failed item is false, naming the item and why

Commit `9732fd9`.

Up to 4.8.0, the 17 batch loops over sections, concepts and frames counted the items that failed and still returned their summary as a success. Now:

- When any item fails, the batch fails with this text:

  ```
  <F> of <T> failed: <id>: error: <class>: <message>; ... . <summary>
  ```

- `BookStructureIIAR.step` turns that failure into `false` with that text.
- The `BookImport` command-line tool exits with a non-zero status and prints that text.
- The items that succeeded stay saved, so a rerun redoes only the failed ones.
- `GeneralFrames.identifyAll` no longer stores a name the model could not judge as one concept. That name keeps the concepts and items an earlier run stored, and the names that were judged are replaced as before.

**What 4.8 YAML must change:** a workflow that runs several batches in a row and should go on after a partial failure needs a transition from the same state that takes the `false`. Otherwise the workflow now stops with `No matching state transition from …`. `chat-ui-with-audit-trail`'s `book-frame-data.yaml` and `book-generalize.yaml` were changed this way in that repository.

### `plugin-rst-frame`: `FileGate.of` refuses when the `files` actor cannot be asked

Commit `1eaec17`.

Up to 4.8.0, `FileGate.of` asked an existing `files` actor whether its object is a `FileGate`. When that ask failed (`ExecutionException`, `InterruptedException`), `FileGate.of` returned `FileGate.open()`, a gate that allows everything. A configured sandbox was thus turned off without a word, and the interrupt flag was lost.

Now:

- A failed check throws `SecurityException`, and an interrupt is restored before it is thrown.
- `FileGate.open()` is returned only when no `files` actor is registered, or when its object is not a `FileGate`.
- The `IIActorRef` constructors that call `FileGate.of` fail instead of running without the gate.

**What 4.8 code must change:** code that creates `rst-frame` actors while the `files` actor may be closed or busy must expect `SecurityException` from the constructor.

### `plugin-rst-frame`: `BookStore` rolls back a write that fails with any `Throwable`

Commit `8849a87`.

Up to 4.8.0, thirteen save methods ran their own transaction and rolled back only on `SQLException`. When a `RuntimeException` or an `Error` escaped, `finally { db.setAutoCommit(true); }` committed the pending work, as JDBC specifies. `saveSections`, for example, kept its deletes although its inserts had failed. Now every save goes through one helper, which rolls back on any `Throwable` before it turns auto-commit back on. A failed rollback is added to the thrown exception as a suppressed exception.

**What 4.8 code must change:** nothing.

### `plugin-ssh`: clone, build and deploy of changed documents are false when a document failed

Commit `5b659fb`.

Up to 4.8.0, `cloneChangedDocuments`, `buildChangedDocuments` and `deployChangedDocuments` reported the documents that failed only on stderr, and returned `true`. Now each returns `true` only when every document was done. Otherwise it returns `false` with:

```
<F> of <T> failed: <document>: <reason>; ... . Cloned <N> documents
```

The reason holds the exit code and the stderr. The documents that were done stay done. A document fails when:

- it has no git URL to clone;
- `rm -rf` of its old copy fails;
- `git clone`, `yarn install && yarn build` or `cp -r` exits non-zero.

A failed `mkdir -p ~/works` or `mkdir -p ~/public_html` makes the action `false` before any document.

**What 4.8 YAML must change:** a workflow that calls these actions needs a transition for the `false`. No YAML in `~/works` calls them.

### `plugin-ssh`: `Node` bounds its waits on the SSH server and on local commands

Commit `ae1e306`.

- `session.connect`, for the node and for a `ProxyJump` host, takes at most `turingworkflow.ssh.connectTimeoutSeconds` (default 30 s). A server that accepts the connection and never answers fails the command with an `IOException` naming `host:port` and the limit.
- Sessions send keepalives: `turingworkflow.ssh.serverAliveIntervalSeconds` (default 30 s) and `turingworkflow.ssh.serverAliveCountMax` (default 3).
- A command whose session was lost fails with an `IOException`. Up to 4.8.0 it returned exit status `-1` as a result.
- A local command, with or without `sudo`, runs at most `turingworkflow.ssh.localCommandTimeoutSeconds` (default 300 s). Past that limit, the command and the processes it started are killed, and the result has exit code `-1` with a stderr naming the command and the limit.

**What 4.8 code must change:** a remote or local command that legitimately runs longer than these defaults must raise the system property. Code that read exit status `-1` as "the session dropped" must catch the `IOException` instead.

### `plugin-ssh`: `detectDocumentChanges` bounds `git` and checks the fetch's exit code

Commit `bcc332b`.

`git fetch` and `git rev-parse` run at most `turingworkflow.ssh.gitTimeoutSeconds` (default 300 s), and are killed past that limit. Their stdout and stderr are read while they run. A fetch that failed or did not finish reports the document as `[UNKNOWN]`, which is counted as changed, with the directory and the limit or the stderr. Up to 4.8.0 the action compared against a stale `origin/*` and reported `[UP-TO-DATE]`.

**What 4.8 code must change:** nothing, unless a caller treated `[UNKNOWN]` as "no change".

### `plugin-llm`, `plugin-kana-kanji`, `plugin-rst-frame`: total and idle limits on LLM requests

Commit `c21336e`.

Each request has two limits:

- A total limit from sending the request to the last byte of the body. The defaults are the old timeouts: `LlmClient` 300 s, `VllmClient` 600 s, `ChatEndpoint` 600 s.
- An idle limit of 60 s, which applies only after the first byte of the body has arrived.

On expiry the call fails with the URL, the model, and `total limit` or `idle limit`, and the connection is closed (for `LlmClient`, `curl` is killed). The limits are set as follows:

| Client | How to set the limits |
|---|---|
| `LlmActor` (`plugin-llm`) | new action `setTimeLimits` with arguments `{totalSeconds: <n>, idleSeconds: <n>}` |
| `VllmActor` (`plugin-kana-kanji`) | the same action `setTimeLimits` |
| `ChatEndpoint` (`plugin-rst-frame`) | system properties `turingworkflow.rstframe.chatTotalTimeoutSeconds` and `turingworkflow.rstframe.chatIdleTimeoutSeconds` |

The `ChatEndpoint` limits apply to each attempt. Its 4 attempts and the 5–20 s sleeps between them are unchanged.

**What 4.8 YAML must change:** a model that sends nothing for more than 60 s once the body has started now fails. For such a model, call `setTimeLimits` with a larger `idleSeconds`:

```yaml
  - states: ["0", "1"]
    actions:
      - actor: llm
        method: setTimeLimits
        arguments: {totalSeconds: 900, idleSeconds: 180}
```

### `plugin-vault`: the Vault request and `kubectl exec` are bounded; stderr is kept out of the secret

Commit `6d63141`.

- `VaultClient` keeps its 10 s connect timeout, now set by `turingworkflow.vault.connectTimeoutSeconds`. A whole request, the response body included, takes at most `turingworkflow.vault.requestTimeoutSeconds` (default 60 s). A request that times out fails with the URL and the limit.
- `KubectlVaultBackend` runs `kubectl exec` for at most `turingworkflow.vault.kubectlTimeoutSeconds` (default 60 s), and kills it and its children past that limit.
- stdout and stderr are read separately. Up to 4.8.0 a `kubectl` warning on stderr became part of the secret value.
- A non-zero exit fails with the exit code and the stderr, never with the stdout, which holds the secret.

**What 4.8 code must change:** code that worked around the stderr text in the secret value must drop the workaround.

### `plugin-chatui3`: `chat` fails at once when the chat POST is refused

Commit `2372431`.

Up to 4.8.0, the status of `POST /api/chat` was discarded, and a refused POST left `chat` reading an SSE stream on which no result would come. Now a non-2xx POST fails with the URL, the status and the response body. The SSE stream is closed on that path, on a non-200 SSE response, and when the POST throws.

**What 4.8 code must change:** nothing.

### `plugin-arxiv`, `plugin-semantic-scholar`: a single retry wait is at most 60 s

Commit `38b0788`.

Both clients took the server's `Retry-After` and tripled the wait on each of the 3 retries, so `Retry-After: 3600` held one action for about 13 hours. A single wait is now at most `turingworkflow.arxiv.maxRetryWaitSeconds` or `turingworkflow.semanticscholar.maxRetryWaitSeconds` (default 60 s). The retry count, the 3 s pacing and the 15 min cooldown are unchanged.

**What 4.8 code must change:** nothing. An action that honoured a long `Retry-After` now gives up sooner and returns `false`.

### `plugin-log-output`: an entry the multiplexer cannot take goes to `System.err`

Commit `3bb12f3`.

`MultiplexerAccumulatorActor.add` handed the entry over without waiting, but ignored the hand-over's future. An entry sent to a closed multiplexer, or one whose accumulator threw, was lost without a word, and the `catch` logged through JUL, which re-entered the log path. Now a failed hand-over writes the entry to `System.err`, and so does the `catch`.

**What 4.8 code must change:** nothing.

### `plugin-ssh`, `plugin-report`, `plugin-inventory`: output reaches `outputMultiplexer`; a workflow can make its own `NodeGroupActor`

Branch `t253` (job t273, `20fe168`); the commit hash changes when it is rebased onto `main`. It needs Turing-workflow 5.0.0, where `RunCLI` registers the multiplexer as `outputMultiplexer`.

**Fixed:** under `RunCLI`, the output of node actors and of `ReportBuilder` was dropped without a word. The writers looked up `outputMultiplexer`, but `RunCLI` had registered the multiplexer only as `log`. The writers now find it by `MultiplexerAccumulatorIIAR.ACTOR_NAME`, and it is registered under that name.

**Changed:** `NodeGroupActor.createAccumulator` is false, with a reason, when no multiplexer is registered. It used to be true every time.

**New:** a workflow can make a node group itself, in three steps:

```yaml
      - actor: loader
        method: createChild
        arguments: ["ROOT", "nodeGroup", "com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor"]
      - actor: nodeGroup
        method: loadInventory
        arguments: {path: "inventory.ini"}
      - actor: nodeGroup
        method: createNodeActors
        arguments: {group: "webservers"}
```

`loadInventory` resolves the path under the workflow's base directory, as `PathUnderBase` does, with the same rule: with no base directory there is no restriction.

### Build: `NodeInterpreterGitTest` is skipped when `git` is not installed

Commit `8ca59c0`. A build machine without `git` (a bare `ubuntu:24.04` pod) reports the test as skipped instead of failing the build. Nothing to change.

### Every plugin: one action manifest per class, and a module name for every jar

Commit `8e6c42b`. Needs Turing-workflow 5.0.0.

- Each plugin's build runs the new `TuringPluginDoclet` with only `-turingClassesDir ${project.build.outputDirectory}`. Up to `cf82849` every plugin passed `-turingOutputDir ${project.build.outputDirectory}/META-INF` and so wrote the same shared file, `META-INF/turing-plugin.json`, with no services index. An uber-jar that bundled several plugins could keep only one of those identically named files, so it kept one plugin's action descriptions and lost the others'. Each plugin jar now carries one manifest per class at `META-INF/turing-actions/<binary class name>.json`; the file names differ, so an uber-jar keeps every plugin's descriptions.
- Every plugin jar declares an `Automatic-Module-Name` of `com.scivicslab.turingworkflow.plugins.<name>`, where `<name>` is `llm`, `promptbuilder`, `secret`, `vault`, `logdb`, `logoutput`, `kanakanji`, `report`, `ssh`, `inventory`, `web`, `chatui3`, `finewebsearch`, `openalex`, `arxiv`, `semanticscholar`, `ocr`, `codedoc`, `rstframe` or `text`. Up to 4.8.0 the JVM derived the names from the jar file names.

**What 4.8 code must change:** a plugin built outside this repository replaces its `-turingOutputDir ${project.build.outputDirectory}/META-INF` lines with `-turingClassesDir ${project.build.outputDirectory}` (see Turing-workflow's `CHANGELOG.md`). A `module-info.java` that required a derived plugin name requires the new one.
