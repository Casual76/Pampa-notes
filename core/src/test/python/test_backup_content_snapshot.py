"""Synthetic production-schema SQL/file regressions, NOT execution of Android/Room/Kotlin.

Run serially: python -B core/src/test/python/test_backup_content_snapshot.py
The public/private table policy and sanitation/search/VACUUM SQL are extracted from production
BackupContentSql.kt. The SQLite cursor/file adapter below models the Kotlin rebuild; real adapter,
Room writer exclusion, BackupArchive and migration regressions are prepared in androidTest.
All databases/bytes are synthetic and temporary, under this owned test directory.
"""
import ctypes
from contextlib import closing
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import textwrap
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[4]
BACKUP = ROOT / "core/src/main/kotlin/dev/pampa/pampanotes/core/backup"
POLICY = (BACKUP / "BackupContentSql.kt").read_text(encoding="utf-8")
ADAPTER = (BACKUP / "BackupDatabaseSnapshot.kt").read_text(encoding="utf-8")
SERVICE = (BACKUP / "BackupService.kt").read_text(encoding="utf-8")
DATABASE = (ROOT / "core/src/main/kotlin/dev/pampa/pampanotes/core/db/PampaDatabase.kt").read_text(encoding="utf-8")
SCHEMAS = ROOT / "core/schemas/dev.pampa.pampanotes.core.db.PampaDatabase"


def strings(text):
    return [json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', text)]


def policy_set(name):
    return set(strings(re.search(r"val " + name + r" = setOf\((.*?)\)", POLICY, re.S)[1]))


def production_sql(name):
    return strings(re.search(r"const val " + name + r' = ("(?:\\.|[^"\\])*")', POLICY)[1])[0]


CONTENT = policy_set("contentTables")
PRIVATE = policy_set("privateTables")
METADATA = policy_set("metadataTables")
SEARCH = policy_set("searchTables")
SHADOWS = {table + suffix for table in SEARCH for suffix in policy_set("searchShadowSuffixes")}
SENTINELS = ["PRIVATE_JOB_QUEUED_417", "PRIVATE_DEVICE_ID_418", "PRIVATE_ACCOUNT_OWNER_419",
             "PRIVATE_PENDING_RELAY_JOURNAL_420", "PRIVATE_OUTBOX_421", "PRIVATE_SESSION_WORK_422",
             "PRIVATE_DELETED_PAGE_423", "PRIVATE_STALE_SEARCH_424"]
EXACT_SOURCE = ' \r\n{ "future": {"opaque":[null,"",1]}, "text":"Keep  spaces\\nΩ" }\t'


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def connect(path):
    db = sqlite3.connect(path, timeout=0, isolation_level=None)
    db.execute("PRAGMA threads=1")
    db.execute("PRAGMA cache_size=-1024")
    return db


def production_triggers(names):
    block = DATABASE.split("val SEARCH_TRIGGERS: List<String> = listOf(", 1)[1].split("fun build(", 1)[0]
    search = [textwrap.dedent(sql).strip() for sql in re.findall(r'"""(.*?)"""', block, re.S)]
    yield from (sql for sql in search if re.search(r" ON (\w+)", sql)[1] in names)
    synced = strings(re.search(r"val SYNCED_TABLES: List<String> = listOf\((.*?)\)", DATABASE)[1])
    if "sync_guard" in names:
        guard = strings(re.search(r'val guard = (".*")', DATABASE)[1])[0]
        block = DATABASE.split("val SYNC_TRIGGERS:", 1)[1].split("// I tag", 1)[0]
        templates = [strings(value)[0] for value in re.findall(r'add\((".*?")\)', block)]
        for table in synced:
            if table in names:
                for template in templates:
                    yield template.replace("${table}", table).replace("$table", table).replace("$guard", guard)
        # These production literals are not inside the per-table loop.
        block = DATABASE.split("// I tag", 1)[1].split("val SEARCH_TRIGGERS:", 1)[0]
        for value in re.findall(r'add\((".*?")\)', block):
            yield strings(value)[0].replace("$guard", guard)


def seed(db, version=15):
    schema = json.loads((SCHEMAS / (str(version) + ".json")).read_text(encoding="utf-8"))["database"]
    entities = {entry["tableName"]: entry for entry in schema["entities"]}
    for table, entity in entities.items():
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for sql in schema["setupQueries"]:
        db.execute(sql)
    for sql in production_triggers(entities):
        db.execute(sql)
    db.execute("PRAGMA user_version=" + str(version))

    def row(table, **values):
        if table not in entities:
            return
        entity = entities[table]
        fields = {field["columnName"]: (None if not field.get("notNull") else
                  0 if field["affinity"] in ("INTEGER", "REAL") else "") for field in entity["fields"]}
        fields.update({key: value for key, value in values.items() if key in fields})
        db.execute("INSERT INTO " + quote(table) + " (" + ",".join(map(quote, fields)) + ") VALUES (" +
                   ",".join("?" for _ in fields) + ")", list(fields.values()))

    row("folders", id="f", name="Personal Ω", kind="personal", createdAt=1, updatedAt=2)
    row("folders", id="child", parentId="f", name="Nested", createdAt=3, updatedAt=4)
    row("notes", id="n", folderId="child", title="Title", body=" Body \r\nΩ\t\x00 tail ", pinned=1,
        language="it", bundleBodyBase=" Exact baseline\r\n", bundleNativeAdopted=1, createdAt=5, updatedAt=6)
    row("note_tags", noteId="n", tag="  exact tag Ω ")
    row("sessions", id="s", noteId="n", title="Session", date="2026-11-08", activeTranscriptId="refined",
        transcribingOn=SENTINELS[5], transcribingSince=99999, voiceNames=' {"p:voice":"Speaker Ω"} ', createdAt=7, updatedAt=8)
    for part, position in (("p", 0), ("p2", 1)):
        row("audio_parts", id=part, sessionId="s", position=position, fileName=part + ".wav", originalName="Original.wav",
            mime="audio/wav", sizeBytes=32, durationMs=1234, sha256="same-byte-hash", createdAt=9, archivedAt=88,
            recordedAtEpochMs=1780000000123 if part == "p" else None,
            recordedAtLocal="2026-11-08T11:22:33.123" if part == "p" else "",
            recordedAtZoneId="Europe/Rome" if part == "p" else None,
            recordedAtSource="samsung_recording" if part == "p" else "filename",
            recordedAtPrecision="millisecond" if part == "p" else "day")
    for transcript, cloud, kind in (("raw", EXACT_SOURCE, "RAW"), ("empty", "", "RAW"), ("null", None, "RAW"), ("refined", None, "REFINED")):
        row("transcripts", id=transcript, sessionId="s", kind=kind, provider="mistral", model="safe-public-model",
            language="it", text="  " + transcript + "\r\nΩ\t", parentId="raw" if kind == "REFINED" else None,
            preset="CUSTOM" if kind == "REFINED" else None, promptHash="public-prompt-hash", wordCount=4,
            status="OK", createdAt=10, cloudSourceJson=cloud)
        row("segments", id={"raw": 1, "empty": 2, "null": 3, "refined": 4}[transcript], transcriptId=transcript,
            partId="p", text=" Exact segment Ω ", partEndMs=1234, sessionEndMs=1234,
            wordsJson=' [{"word":" Ω ","start":0.25,"end":0.75}] ', wordsEstimated=0, speaker="SPEAKER_01",
            noSpeechProb=0.125, avgLogProb=-0.375)
    row("sources", id="source", noteId="n", kind="SDOCX", originalName="material.sdocx", mime="application/zip",
        sizeBytes=44, sha256="source-hash", storedFileName="source.sdocx", extractedChars=23, status="PARTIAL",
        detail=" exact material note ", importedAt=14, archivedAt=15, sdocxCreatedAtMillis=1770000000000)
    row("export_presets", id="preset", name="Preset", optionsJson=' {"format":"pn","unknown":"keep"} ', lastUsedAt=16, isDefault=1)
    row("transcription_runs", id="run", jobId="completed-history-id", sessionId="s", noteId="n", provider="mistral",
        model="public-model", device="cpu", deviceName="Public historical device label", audioMs=1234, wallMs=80,
        processingMs=50, words=4, segments=1, resumed=0, finishedAt=17)
    for job, state in (("queued", "QUEUED"), ("running", "UPLOADING"), ("done", "DONE")):
        row("jobs", id=job, sessionId="s", type="TRANSCRIBE", provider="mistral", model="model", state=state,
            phase="private phase", optionsJson=SENTINELS[0], errorMessage=SENTINELS[0], createdAt=18, updatedAt=19)
    row("sync_state", id=1, deviceId=SENTINELS[1], lastPullSeq=9876)
    row("sync_guard", id=1, applying=1)
    row("sync_origin", tbl="notes", rowId="n", ownerId=SENTINELS[2])
    row("sync_meta", tbl="pending.relay.private", rowId=SENTINELS[3], serverSeq=20, hash=SENTINELS[3], updatedAt=21)
    row("sync_outbox", id=1000, tbl="notes", rowId=SENTINELS[4], op="D")
    # Recoverable deleted page and stale FTS data must not enter a content rebuild.
    db.execute("PRAGMA secure_delete=OFF")
    row("jobs", id="deleted", sessionId="s", type="TRANSCRIBE", provider="custom", state="FAILED",
        optionsJson=SENTINELS[6] * 1000, createdAt=22, updatedAt=23)
    db.execute("DELETE FROM jobs WHERE id='deleted'")
    db.execute("INSERT INTO notes_fts (noteId,title,body) VALUES ('orphan','stale',?)", (SENTINELS[7],))
    return entities


class CancelSnapshot(Exception):
    pass


def rebuild(source, destination, after_table=lambda _: None):
    """File/cursor adapter model. All sanitation SQL below comes from the production literals."""
    schema = source.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid").fetchall()
    tables = [entry for entry in schema if entry[0] == "table" and not entry[1].startswith("sqlite_") and entry[1] not in SHADOWS]
    names = {entry[1] for entry in tables}
    if not names <= CONTENT | PRIVATE | METADATA | SEARCH:
        raise ValueError("Unknown table; refuse rather than silently lose content")
    target = connect(destination)
    try:
        target.execute("PRAGMA journal_mode=DELETE")
        target.execute("PRAGMA foreign_keys=OFF")
        target.execute("BEGIN")
        for _, _, _, sql in tables:
            target.execute(sql)
        for _, name, _, _ in tables:
            if name in CONTENT | METADATA:
                cursor = source.execute("SELECT * FROM " + quote(name))
                fields = [column[0] for column in cursor.description]
                insert = "INSERT INTO " + quote(name) + " (" + ",".join(map(quote, fields)) + ") VALUES (" + ",".join("?" for _ in fields) + ")"
                for row in cursor:
                    target.execute(insert, row)
                after_table(name)
        columns = {row[1] for row in source.execute("PRAGMA table_info(sessions)")}
        if {"transcribingOn", "transcribingSince"} <= columns:
            target.execute(production_sql("CLEAR_SESSION_WORK"))
        for table, sql in (("notes_fts", "REBUILD_NOTES_SEARCH"), ("transcripts_fts", "REBUILD_TRANSCRIPTS_SEARCH")):
            if table in names:
                target.execute(production_sql(sql))
        if any(entry[1] == "sqlite_sequence" for entry in schema):
            for table, seq in source.execute("SELECT name,seq FROM sqlite_sequence"):
                if table in CONTENT:
                    target.execute("DELETE FROM sqlite_sequence WHERE name=?", (table,))
                    target.execute("INSERT INTO sqlite_sequence (name,seq) VALUES (?,?)", (table, seq))
        for kind, _, _, sql in schema:
            if kind in ("index", "trigger", "view"):
                target.execute(sql)
        target.execute("PRAGMA user_version=" + str(source.execute("PRAGMA user_version").fetchone()[0]))
        target.execute("COMMIT")
        target.execute(production_sql("COMPACT"))
        assert target.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
        assert target.execute("PRAGMA foreign_key_check").fetchall() == []
    except BaseException:
        target.close()
        for suffix in ("", "-wal", "-shm", "-journal"):
            Path(str(destination) + suffix).unlink(missing_ok=True)
        raise
    target.close()


def rows(db, table):
    return sorted(db.execute("SELECT * FROM " + quote(table)).fetchall(), key=repr)


def schema_signature(db):
    return db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").fetchall()


class BackupContentSnapshotSqlTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="backup-content-", dir=Path(__file__).resolve().parent)
        self.root = Path(self.temp.name)
        self.source = connect(self.root / "source.sqlite")
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.source.close)

    def test_current_graph_exact_values_schema_room_identity_and_private_byte_absence(self):
        entities = seed(self.source)
        before = {table: rows(self.source, table) for table in entities}
        signature = schema_signature(self.source)
        rebuild(self.source, self.root / "content.sqlite")
        with closing(connect(self.root / "content.sqlite")) as target:
            for table in CONTENT:
                expected = before[table]
                if table == "sessions":
                    fields = [column[1] for column in self.source.execute("PRAGMA table_info(sessions)")]
                    expected = [tuple(None if fields[index] in ("transcribingOn", "transcribingSince") else value
                                      for index, value in enumerate(row)) for row in expected]
                self.assertEqual(expected, rows(target, table), table)
            for table in PRIVATE:
                self.assertEqual([], rows(target, table), table)
            self.assertEqual(signature, schema_signature(target))
            self.assertEqual(rows(self.source, "room_master_table"), rows(target, "room_master_table"))
            self.assertEqual([(15,)], target.execute("PRAGMA user_version").fetchall())
            self.assertEqual([("raw",)], target.execute("SELECT parentId FROM transcripts WHERE id='refined'").fetchall())
            self.assertEqual([("refined",)], target.execute("SELECT activeTranscriptId FROM sessions").fetchall())
            self.assertEqual([("empty", ""), ("null", None), ("raw", EXACT_SOURCE)],
                             target.execute("SELECT id,cloudSourceJson FROM transcripts WHERE kind='RAW' ORDER BY id").fetchall())
            self.assertEqual([(4,)], target.execute("SELECT count(*) FROM transcripts_fts").fetchall())
            self.assertEqual([(1,)], target.execute("SELECT count(*) FROM notes_fts").fetchall())
            self.assertEqual([], target.execute("SELECT * FROM notes_fts WHERE notes_fts MATCH 'PRIVATE_STALE_SEARCH_424'").fetchall())
        for table in entities:
            self.assertEqual(before[table], rows(self.source, table), "Source unchanged: " + table)
        raw = (self.root / "content.sqlite").read_bytes()
        self.assertFalse(any((self.root / ("content.sqlite" + suffix)).exists() for suffix in ("-wal", "-shm", "-journal")))
        # Synthetic ZIP carrier only. Actual BackupArchive path is prepared in androidTest.
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            output.writestr("database/pampa_notes.db", raw)
        with zipfile.ZipFile(io.BytesIO(archive.getvalue())) as output:
            self.assertEqual(raw, output.read("database/pampa_notes.db"))
        for sentinel in SENTINELS:
            self.assertNotIn(sentinel.encode(), raw)
            self.assertNotIn(sentinel.encode(), archive.getvalue())
        self.assertIn(SENTINELS[5].encode(), (self.root / "source.sqlite").read_bytes())

    def test_every_historical_schema_keeps_schema_version_and_public_rows_on_restore(self):
        for version in range(1, 16):
            with self.subTest(version=version):
                source = connect(":memory:")
                try:
                    entities = seed(source, version)
                    destination = self.root / (str(version) + ".sqlite")
                    rebuild(source, destination)
                    target = connect(destination)
                    try:
                        self.assertEqual(schema_signature(source), schema_signature(target))
                        self.assertEqual([(version,)], target.execute("PRAGMA user_version").fetchall())
                        self.assertEqual(rows(source, "room_master_table"), rows(target, "room_master_table"))
                        for table in CONTENT & entities.keys():
                            if table != "sessions":
                                self.assertEqual(rows(source, table), rows(target, table), table)
                        for table in PRIVATE & entities.keys():
                            self.assertEqual([], rows(target, table))
                        self.assertEqual([], target.execute("PRAGMA foreign_key_check").fetchall())
                    finally:
                        target.close()
                    for sentinel in SENTINELS:
                        self.assertNotIn(sentinel.encode(), destination.read_bytes())
                finally:
                    source.close()

    def test_wal_snapshot_cannot_mix_writer_revisions_or_mutate_source_files(self):
        self.source.execute("PRAGMA journal_mode=WAL")
        self.source.execute("PRAGMA wal_autocheckpoint=0")
        seed(self.source)
        self.source.execute("UPDATE notes SET title='before'")
        writer = connect(self.root / "source.sqlite")
        self.addCleanup(writer.close)
        before = {suffix: hashlib.sha256(Path(str(self.root / "source.sqlite") + suffix).read_bytes()).hexdigest()
                  for suffix in ("", "-wal")}
        self.source.execute("BEGIN IMMEDIATE")
        attempts = []

        def interleave(table):
            if table == "notes":
                with self.assertRaisesRegex(sqlite3.OperationalError, "locked"):
                    writer.execute("BEGIN IMMEDIATE")
                attempts.append(table)

        rebuild(self.source, self.root / "wal-content.sqlite", interleave)
        self.source.execute("ROLLBACK")
        self.assertEqual(["notes"], attempts)
        for suffix, digest in before.items():
            self.assertEqual(digest, hashlib.sha256(Path(str(self.root / "source.sqlite") + suffix).read_bytes()).hexdigest())
        writer.execute("BEGIN IMMEDIATE")
        writer.execute("UPDATE notes SET title='after'")
        writer.execute("UPDATE sessions SET title='after'")
        writer.execute("COMMIT")
        target = connect(self.root / "wal-content.sqlite")
        try:
            self.assertEqual([("before",)], target.execute("SELECT title FROM notes").fetchall())
            self.assertEqual([("Session",)], target.execute("SELECT title FROM sessions").fetchall())
        finally:
            target.close()

    def test_cancelled_copy_removes_snapshot_sidecars_and_source_remains_usable(self):
        seed(self.source)
        original = schema_signature(self.source)
        jobs = rows(self.source, "jobs")
        self.source.execute("BEGIN IMMEDIATE")
        try:
            def cancel(table):
                if table == "sessions":
                    raise CancelSnapshot()
            with self.assertRaises(CancelSnapshot):
                rebuild(self.source, self.root / "cancelled.sqlite", cancel)
        finally:
            self.source.execute("ROLLBACK")
        self.assertFalse(any(self.root.glob("cancelled.sqlite*")))
        self.assertEqual(original, schema_signature(self.source))
        self.assertEqual(jobs, rows(self.source, "jobs"))
        self.source.execute("BEGIN IMMEDIATE")
        self.source.execute("UPDATE notes SET title='still writable'")
        self.source.execute("COMMIT")

    def test_restore_private_defaults_and_triggers_remain_operational_without_stale_work(self):
        seed(self.source)
        rebuild(self.source, self.root / "restored.sqlite")
        target = connect(self.root / "restored.sqlite")
        try:
            self.assertEqual([], target.execute("SELECT * FROM jobs WHERE state='QUEUED'").fetchall())
            self.assertEqual([], rows(target, "sync_state"))
            self.assertEqual([], rows(target, "sync_origin"))
            self.assertEqual([(None, None)], target.execute("SELECT transcribingOn,transcribingSince FROM sessions").fetchall())
            target.execute("UPDATE notes SET title='Fresh local edit' WHERE id='n'")
            self.assertEqual([("notes", "n", "U")], target.execute("SELECT tbl,rowId,op FROM sync_outbox").fetchall())
            self.assertEqual([("n",)], target.execute("SELECT noteId FROM notes_fts WHERE notes_fts MATCH 'Fresh'").fetchall())
            target.execute("PRAGMA foreign_keys=ON")
            target.execute("DELETE FROM folders WHERE id='f'")
            self.assertEqual([], rows(target, "notes"))
            self.assertEqual([], rows(target, "transcripts"))
            self.assertEqual(1, len(rows(target, "transcription_runs")))
        finally:
            target.close()

    def test_unknown_content_table_fails_closed_without_dropping_it_from_source(self):
        seed(self.source)
        self.source.execute("CREATE TABLE future_public_content (exact TEXT)")
        self.source.execute("INSERT INTO future_public_content VALUES ('keep')")
        with self.assertRaises(ValueError):
            rebuild(self.source, self.root / "unknown.sqlite")
        self.assertEqual([("keep",)], rows(self.source, "future_public_content"))
        self.assertFalse((self.root / "unknown.sqlite").exists())

    def test_logical_marker_reset_alone_leaves_bytes_that_production_compaction_removes(self):
        seed(self.source)
        # Force overflow pages: SQLite may overwrite a small in-page old cell immediately.
        self.source.execute("UPDATE sessions SET transcribingOn=?", (SENTINELS[5] * 1000,))
        self.source.execute(production_sql("CLEAR_SESSION_WORK"))
        self.assertEqual([(None, None)], self.source.execute("SELECT transcribingOn,transcribingSince FROM sessions").fetchall())
        # Negative control: successful logical cleanup does not establish byte privacy.
        self.assertTrue(SENTINELS[5].encode() in (self.root / "source.sqlite").read_bytes(), "Negative control must retain recoverable marker bytes")
        self.source.execute(production_sql("COMPACT"))
        self.assertNotIn(SENTINELS[5].encode(), (self.root / "source.sqlite").read_bytes())

    def test_production_chunk_query_preserves_large_exact_source_with_nul_and_unicode(self):
        seed(self.source)
        exact = (" Ω🙂\x00\r\n\t" * 300000) + " Exact final tail "
        self.source.execute("UPDATE transcripts SET cloudSourceJson=? WHERE id='raw'", (exact,))
        row = self.source.execute("SELECT rowid FROM transcripts WHERE id='raw'").fetchone()[0]
        quoted_field = quote("cloudSourceJson")
        where = "FROM transcripts WHERE rowid = " + str(row)
        # Execute the actual Kotlin adapter's chunk SQL template, including BLOB conversion.
        template = next(value for value in strings(ADAPTER) if value.startswith("SELECT substr(CAST($quotedField AS BLOB)"))
        size_parts = re.search(r"const val VALUE_CHUNK_BYTES = (\d+) \* (\d+)", ADAPTER)
        chunk_size = int(size_parts[1]) * int(size_parts[2])
        length = self.source.execute("SELECT length(CAST(cloudSourceJson AS BLOB)) " + where).fetchone()[0]
        self.assertGreater(length, 2 * 1024 * 1024)
        result = bytearray()
        offset = 1
        while offset <= length:
            sql = template.replace("$quotedField", quoted_field).replace("$offset", str(offset)).replace("$VALUE_CHUNK_BYTES", str(chunk_size)).replace("$where", where)
            chunk = self.source.execute(sql).fetchone()[0]
            self.assertGreater(len(chunk), 0)
            self.assertLessEqual(len(chunk), chunk_size)
            result.extend(chunk)
            offset += len(chunk)
        self.assertEqual(exact.encode("utf-8"), result)

    def test_production_composition_uses_writer_boundary_then_sanitizes_restore_before_live_close(self):
        # Source-only contract guards, in addition to executed SQL/file assertions above.
        self.assertIn("return transactions.run {", ADAPTER)
        self.assertIn("check(source.inTransaction())", ADAPTER)
        self.assertIn("tables.filter { it.name in BackupContentSql.contentTables || it.name in BackupContentSql.metadataTables }", ADAPTER)
        self.assertNotIn("wal_checkpoint", SERVICE)
        self.assertNotIn("liveDatabase().copyTo", SERVICE)
        self.assertLess(SERVICE.index(".capture(snapshot)"), SERVICE.index("BackupArchive.write("))
        self.assertLess(SERVICE.index("BackupArchive.extract("), SERVICE.index(".prepareRestore("))
        self.assertLess(SERVICE.index(".prepareRestore("), SERVICE.index("database.close()"))
        self.assertIn("move(contentDatabase, live)", SERVICE)
        self.assertIn("BackupDatabaseSnapshot.delete(snapshot)", SERVICE)
        self.assertIn("counts = snapshot?.counts", SERVICE)
        self.assertIn("copy.execSQL(BackupContentSql.COMPACT)", ADAPTER)
        self.assertIn("settings.forgetSyncDevice()", SERVICE)
        self.assertIn("copyToFinal(folder, document, name) { operation.ensureActive() }", SERVICE)
        self.assertNotIn("source.execSQL", ADAPTER)


def bound_process():
    """One CPU and a 192 MiB process-memory ceiling for this explicitly bounded offline run."""
    if os.name == "nt":
        from ctypes import wintypes as w
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = w.HANDLE
        kernel.CreateJobObjectW.restype = w.HANDLE
        kernel.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
        kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
        kernel.GetProcessAffinityMask.argtypes = [w.HANDLE, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
        kernel.SetProcessAffinityMask.argtypes = [w.HANDLE, ctypes.c_size_t]
        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_longlong), ("job_time", ctypes.c_longlong), ("flags", w.DWORD),
                        ("min_ws", ctypes.c_size_t), ("max_ws", ctypes.c_size_t), ("active", w.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]
        class Extended(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", ctypes.c_ulonglong * 6), ("process_memory", ctypes.c_size_t),
                        ("job_memory", ctypes.c_size_t), ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]
        process = kernel.GetCurrentProcess()
        available, system = ctypes.c_size_t(), ctypes.c_size_t()
        assert kernel.GetProcessAffinityMask(process, ctypes.byref(available), ctypes.byref(system))
        assert kernel.SetProcessAffinityMask(process, available.value & -available.value)
        limits = Extended()
        limits.basic.flags = 0x100  # JOB_OBJECT_LIMIT_PROCESS_MEMORY
        limits.process_memory = 192 * 1024 * 1024
        bound_process.job = kernel.CreateJobObjectW(None, None)
        assert bound_process.job
        assert kernel.SetInformationJobObject(bound_process.job, 9, ctypes.byref(limits), ctypes.sizeof(limits))
        assert kernel.AssignProcessToJobObject(bound_process.job, process)
    else:
        import resource
        os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})
        resource.setrlimit(resource.RLIMIT_AS, (192 * 1024 * 1024, 192 * 1024 * 1024))


if __name__ == "__main__":
    bound_process()
    unittest.main(verbosity=2)
