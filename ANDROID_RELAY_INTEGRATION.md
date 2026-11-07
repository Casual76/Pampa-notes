# Android temporary cloud file relay

Implemented in core/relay/ plus the new CloudRelayWorker and CloudRelayScheduler.
The authoritative wire protocol is worker/RELAY_API.md. No existing Android files, database
schema, engine files, archive markers or workflow state were changed by this implementation.

## Public entry points

Hilt supplies RelayRepository, RelaySettingsStore and CloudRelayScheduler automatically;
RelayModule is inside the new core relay package. No existing DI module needs editing.

For normal UI actions inject CloudRelayScheduler:

~~~kotlin
// Suspend calls: run in a ViewModel coroutine, show their returned RelayReport immediately.
relayScheduler.uploadNote(noteId)                // audio and stored originals for this note
relayScheduler.uploadAllLocalFiles()            // every locally present audio/original
relayScheduler.uploadNote(noteId, resend = true) // explicit fresh request after terminal expiry/failure
relayScheduler.uploadAllLocalFiles(resend = true)
relayScheduler.receiveNow()                     // explicit receiving, independent of opt-in
relayScheduler.observe()                        // Flow<List<WorkInfo>>, progress and output Data
relayScheduler.cancel()                         // stops sockets/work; journal stays resumable
~~~

Queueing hashes the current file and obtains server time, then durably records the exact begin JSON.
It does not POST begin: WorkManager starts/resumes that saved job. Queueing therefore needs a working
authenticated connection. The ten-minute cloud window starts when the server accepts begin, including
all upload time. Returning QUEUED does not mean the recipient can download yet.

The repository also supports direct suspend operations for foreground flows:

~~~kotlin
relay.uploadNote(noteId, resend = false, onProgress = ::showProgress)
relay.uploadAllLocalFiles(resend = false, onProgress = ::showProgress)
relay.queueNote(noteId)                            // persist only; schedule kick afterwards
relay.queueAllLocalFiles()                         // persist only; schedule kick afterwards
relay.resumeUploads(onProgress = ::showProgress)    // NEVER creates a fresh begin identity
relay.receiveReady(onProgress = ::showProgress)     // discover, match existing rows, receive
relay.jobs()                                      // current-account saved public statuses
relay.forget(jobId)                               // explicit local dismissal, not remote deletion
~~~

RelayReport.results contains one RelayResult per file/job or an operation-level failure.
Results have state, optional job/transfer/row identity, verified/acknowledged byte counts, total bytes,
server expiresAt, stable diagnostic code, optional retry delay and a verified local File after
successful on-demand reception. RelayProgress supplies job/row/kind/state/bytes/total/expiry.
Translate RelayState with Italian/English resources instead of displaying diagnostic codes as prose.
Important states are READY, RECEIVED, ALREADY_LOCAL, RETRY, EXPIRED, UNMATCHED,
QUOTA, UNSUPPORTED, SOURCE_CHANGED, ROW_CHANGED, ACCOUNT_CHANGED, INTEGRITY_ERROR
and AUTH_REQUIRED. UNMATCHED means wait for text sync, not create a row.

resend = false reuses the existing record, including terminal expiry. resend = true creates a new
request identity for a terminal record; it still reuses a currently active job. UI should label the
explicit action "Send again" and explain that both devices must finish within ten minutes.
Rows without a stored original and files absent locally are skipped; zero/oversized/invalid-identity
files return UNSUPPORTED. Files must fit 256 MiB each; server reservations enforce 512 MiB / eight
transfers per account. All-local upload reports quota failures rather than silently re-sending later.

## Exact hooks for the next implementer

1. Inject CloudRelayScheduler in startup and call kick() once after interrupted work recovery.
   This resumes persisted uploads, without inventing new begin IDs or upload work from Room data.
2. In SyncWorker, after a successful text pull/repair, call relayScheduler.kick().
   It resumes uploads and receives only if the new opt-in is enabled. Manual pull-to-refresh can
   use this hook too. There is no new periodic relay worker: an ordinary periodic sync cannot promise
   to catch a ten-minute transfer.
3. Expose RelaySettingsStore.automaticReceive and suspend setAutomaticReceive(Boolean) in the
   synchronization/transfer UI. Default is **false**. On enabling, optionally call receiveNow().
   Uploads always require explicit actions; opt-in never creates upload requests from Room data.
4. Add per-note and all-local-files send actions, explicit receive/refresh, progress, expiry and
   resend controls. WorkManager progress keys are job, entity, kind, state, bytes, total,
   expiresAt. Output keys are constants on CloudRelayWorker (states, ready, received,
   unmatched, failed, bytes). relay.jobs() supplies per-job detail after process death.
5. Integrate on-demand lookup/fetch below before companion-only dead ends. Keep companion archive
   fallback and its existing archive marker semantics. No relay success may set archivedAt,
   enable eviction, or imply a durable remote copy.

No startup, text-sync worker, WorkScheduler, settings UI, resources or ArchiveFetcher integration
has been edited here; those are reserved for the integration implementer.

## ArchiveFetcher / session / source hooks

~~~kotlin
val availability = relay.availability(RelayKind.AUDIO, part.id)
val availability = relay.availability(RelayKind.SOURCE, source.id)
// availability.ready includes only live ready transfers matching kind + SHA256 + bytes.
// availability.result explains authentication/account/protocol failures.

val result = relay.fetch(RelayKind.AUDIO, part.id, ::showProgress)
val result = relay.fetch(RelayKind.SOURCE, source.id, ::showProgress)
when (result.state) {
  RelayState.RECEIVED, RelayState.ALREADY_LOCAL -> result.file // verified local file
  else -> null // surface error or continue appropriate companion fallback
}
~~~

Call by current row ID, not by a remote transfer name. Original remote entity ID is only a matching
hint; another existing local row with identical kind/hash/length is supported. Never clear a
companion archivedAt marker because a relay was absent or expired. Hold session/source files
through the existing FilesInUse mechanism while consuming them, as before.

## Persistence, cancellation and bounds

Journals and verified chunks live in context.noBackupFilesDir/cloud-relay-v1, outside backup,
text sync, AppFiles' job sweeper and cache eviction. They contain account/server identity hashes,
row/path snapshots, exact begin metadata and progress; never session/API tokens. At most 64 records
of 32 KiB are retained, with at most 32 chunks per file. Download staging is capped at 512 MiB,
plus at most one 256 MiB private assembled file. Terminal/expired/foreign-account staging is removed
on subsequent operations; orphan partial stage directories are swept too. Ready transfers are never
automatically deleted remotely, so multiple receivers can use them.

Upload memory is bounded to one 8 MiB chunk; fingerprinting, downloading and assembly use 64 KiB
buffers. Every saved/downloaded chunk is rehashed before reuse after interruption; complete files
are rehashed before placement. Source files are fully hashed before begin/retry and again before
commit, with each uploaded chunk also checked against the saved fingerprint.

All operations use IO and one repository mutex; the unique WorkManager chain also serializes
background receive/upload requests. A stopped coroutine promptly disconnects HttpURLConnection and
keeps only fully verified chunk boundaries. Local final paths are validated AppFiles paths, with
symlink/traversal rejection; remote originalName is display metadata only. Placement re-reads the
row inside a Room transaction, rechecks the current account, and uses an atomic move on the same
private filesystem. An atomic move failure stays private: there is no partial-copy fallback.

Server observations anchor expiry to elapsed realtime across process death. A detected device-clock
discontinuity conservatively expires the local job. Every network resumption also obtains authenticated
status; exact begin replays preserve original request ID/time/body, even after the begin freshness fence.
404/410/stale begin and other permanent failures are terminal. IO/5xx/reconcilable conflicts
retry at most six attempts per persisted job within its original deadline. No background pass renews
TTL. New begin requests need an explicit upload/resend call.

## Worker foreground and resources

CloudRelayWorker uses ordinary cancellable **90-second work slices**, below normal
WorkManager's execution limit. It needs **no foreground service, channel, notification permission,
manifest entry or new resource strings**. Slice stops leave safe journals and retry at chunk
boundaries. WorkManager may delay execution beyond expiry, and a very slow 8 MiB chunk might not
finish within a slice; the UI must state this limit and expose retry/resend. A foreground alternative
can be added by the integration owner using existing notification conventions if longer uninterrupted
work is desired. It must retain the same fixed TTL and must not regenerate begin JSON on retries.

## Verification

Run with exclusive Gradle ownership:

~~~powershell
.\gradlew.bat --no-daemon --no-watch-fs :core:testDebugUnitTest --tests "dev.pampa.pampanotes.core.relay.*" :app:compileDebugKotlin
~~~

JVM tests use loopback HTTP and injectable account/Room adapters; no production or paid API.
They cover persisted exact begin replay, expiry/stale begin, acknowledged upload chunks,
download chunk resume/revalidation, length/chunk/whole-file integrity, matching, existing files,
concurrent account/row guards, URL/redirect policy and cancellation/disconnect. Real Room/device
execution and deployed Worker interoperability remain integration checks.

Implementation verification on 2026-11-07: **34 relay JVM tests passed, zero failures/errors**
(28 repository/HTTP-flow cases, three HTTP cancellation/security cases, three Android-adapter cases).
Core production and test sources compiled successfully. An earlier run also passed both
:core:compileDebugKotlin and :app:compileDebugKotlin with the new relay worker/scheduler.
The final combined run passed :core:testDebugUnitTest but :app:compileDebugKotlin was blocked
by concurrent, out-of-scope ui/cloudcost changes: missing cloud_cost_*/cloud_folder_* resources
and the FolderCloudViewModel RoomDatabase classpath error. Re-run combined app checks once that
unit is complete. No device, deployed Worker, real account or paid API run is claimed.
