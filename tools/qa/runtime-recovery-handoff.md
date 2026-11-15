# Runtime recovery + offline replay handoff

## Scope and safety

Only RuntimeJobRegistry.java, new RuntimeJobRecovery.java, new RuntimeJobsTool.java,
new tests and new files under tools/qa were edited. No AgentLoop, Main,
SessionManager, TaskDashboard or shared registration files were edited.

Restarted unfinished executions remain UNKNOWN + fenced. There is no reconnect,
PID lookup, SQL replay, execution retry, automatic completion, or fence-clear API.
`acknowledge` means a human reviewed evidence, NOT that execution has recovered.
Historical states now retain a complete previous-state JSON (excluding recursive
history), including the state before restart and late provider observations.
Legacy ledgers cannot retroactively recover history that was never recorded.
History is not silently pruned; the existing 16MB ledger cap fails closed.

## Main-agent integration (not installed in shared wiring)

Host main-agent registration, and only main-agent registration:

```java
registry.register(new RuntimeJobsTool(
    () -> new RuntimeJobRecovery(runtimeJobs(), authorizedRoots, root)));
// Equivalent helper:
RuntimeJobsTool.registerForMainAgent(registry,
    () -> new RuntimeJobRecovery(runtimeJobs(), authorizedRoots, root));
```

`runtimeJobs()` must lazily supply the same host-owned registry, not construct
one per request. The host closes it during shutdown; the tool never closes it.
Factory is called only for a validated execute request, never on construction,
name/description/schema/discovery. Compatibility constructor taking an existing
RuntimeJobRecovery is retained. The first host registry open can persist restart
fences; list/inspect/reconcile themselves never mutate the ledger. Factory failures
do not claim `noSideEffect`, because host initialization may already have written.

`authorizedRoots` must come from actual host permission checks, NOT model input.
`root` is the original submission workspace for relative paths, NOT an assumed
current directory. The two-argument recovery constructor refuses to resolve relative
paths. Prefer narrow per-session/per-task roots and ledger ownership; this adapter
is not a cross-user authorization service. Do not expose the factory to child agents.

Tool inputs:

```json
{"action":"list"}
{"action":"inspect","jobId":"<saved ID>"}
{"action":"reconcile","jobId":"<saved ID>","previousSha256":"<optional prior hash>","expectedExternalId":"<optional expected ID>"}
```

Only the ledger's existing resultPath may be read. No arbitrary path argument.
Hashing reads no more than 64MB per pass, twice, checks regular file, no symlinks,
file key/size/mtime/creation time before/between/after. Reports distinguish missing,
unauthorized, non-regular, changed, relative-base-required, too-large and stable
observations. A stable observation is not a guarantee the writer has finished.
Portable Java 8 cannot ensure race-free traversal of hostile concurrently mutated
directories on every OS; only authorize host-trusted roots. No file contents are
returned or executed. External ID comparison checks the saved ledger only; provider
completion/identity is explicitly UNVERIFIED, even if IDs or hashes match.

## Trusted human review API (NOT a model tool)

After authenticating the reviewer in the host UI/API:

```java
RuntimeJobRecovery.Report report = recovery.reconcile(jobId, previousHash, expectedId);
// show report, obtain explicit human attestation; later:
recovery.acknowledge(report, authenticatedReviewer, note);
```

This rechecks current bytes, job version and identity before recording the review.
Binding includes jobId/taskId/revision/attempt/original ownerEpoch/record version.
Superseded, cancelled, stale-version or mismatched attempts are rejected. Review
metadata and fresh file version evidence persist atomically alongside history.
The lower-level registry `acknowledgeRecovery(...)` is trusted-human attestation
only; its supplied hash is an assertion, not an independently verified hash.
Prefer the adapter above for freshly checked evidence.

Evidence available => human review possible, not execution recoverable.
Missing/changed/unauthorized evidence => waiting for user or permissions.
Superseded/cancelled/unowned attempt => not eligible for recovery review.
All cases preserve canonical status and fence. No provider completion recovery or
business-deliverable acceptance mechanism is claimed to be implemented here.

## Offline saved-evidence replay (Python 3.7+; stdlib only)

```text
python tools/qa/replay_runtime_jobs.py --before <explicit-before-metrics.json> --after <explicit-after-metrics.json> --output <new-output.json>
python -m unittest discover -s tools/qa -p test_replay_runtime_jobs.py -v
```

No network, model, tool execution, config/session crawling or overwrite. Accepts
existing TaskEvaluation metrics JSON, arrays, or `{"runs":[...]}` wrappers. Inputs
are explicit saved evidence, up to 10MB each. It does not simulate a missing run.
Matches by caseId (or taskId) + workflowRevision/revision + attempt, NOT input order.
Identical report snapshots dedup; conflicting snapshots for the same identity fail
rather than arbitrarily picking a winner. Select one run per side explicitly.
Unknown revision/attempt stays null, not inferred from goal text.

Existing fields: taskInputTokens/taskOutputTokens/activeMillis/
duplicateToolArguments/taskTokenScope/manualBusinessReview. Session totals are
never substituted for missing task usage. Token deltas require matching explicit
scope; child usage is not silently included. Deltas are after minus before with
paired-evidence denominators; missing/scope-incompatible values remain unknown.
Duplicate arguments alone are not proof of duplicate execution or side effects.

Optional ordered `events` in each run enable deterministic saved-event replay:

```json
[
 {"eventId":"r1","kind":"requirement","revision":1},
 {"eventId":"s1","kind":"execution_started","executionKey":"task/rev/attempt/operation-key","instanceId":"external-1","revision":1},
 {"eventId":"r2","kind":"requirement","revision":2},
 {"eventId":"s2","kind":"execution_started","executionKey":"task/rev/attempt/operation-key","instanceId":"external-2","revision":1},
 {"eventId":"e1","kind":"execution_finished","instanceId":"external-1"},
 {"eventId":"stop1","kind":"stop"}
]
```

The executionKey is a recorder-supplied logical operation identity, not a PID or
inferred SQL equivalence. Unique event IDs are mandatory; identical event replay
is deduplicated, conflicting same-ID events fail. Counts include latest requirement
revision, executions on a stale revision, repeated execution keys, pending-at-stop
and pending-at-end. These are observations, NOT semantic error gold: stopping while
a background process is pending need not be premature. Unknown event kinds count
as observed events but are not interpreted as execution or labels.

Latest-instruction adherence/premature-stop/cross-project-leak/false-completion
rates require explicit valid manualBusinessReview labels; denominator and unknown
counts are shown. Business accuracy remains unknown without item-level gold.
Aggregate quality rates include each side's labelled runs; unmatched IDs are shown
and excluded from performance deltas. Do not compare label rates as if coverage
were identical. No semantic leak detection or autonomous business scoring claimed.

Fixtures are SYNTHETIC examples in the existing metrics format, not real business
gold or evidence of a production improvement. This change ran no actual before/
after business task; use explicitly selected real saved recordings for that claim.
Python 3.7 grammar compatibility was checked; tests ran on Python 3.12, not 3.7.
Java tests compile and run using local Java 8 + cached Gson/JUnit, not full Maven.
