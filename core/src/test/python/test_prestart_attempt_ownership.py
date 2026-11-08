"""Production SQL + schema15 interleavings only; not Room/WorkManager runtime.
Run with python -B; entirely in-process and no files written by these checks.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = (ROOT / "core/src/main/kotlin/dev/pampa/pampanotes/core/db/Daos.kt").read_text(encoding="utf-8")
SCHEMA = json.loads((ROOT / "core/schemas/dev.pampa.pampanotes.core.db.PampaDatabase/15.json").read_text(encoding="utf-8"))["database"]
JOBS = next(e for e in SCHEMA["entities"] if e["tableName"] == "jobs")


def query(name):
    stop = SOURCE.index("suspend fun " + name + "(")
    start = SOURCE.rfind("@Query(", 0, stop)
    return "".join(json.loads('"' + v + '"') for v in re.findall(r'"((?:\\.|[^"\\])*)"', SOURCE[start:stop]))


class PreStartAttemptOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute(JOBS["createSql"].replace("${TABLE_NAME}", "jobs"))
        row = {f["columnName"]: (None if not f.get("notNull", False) else 0 if f["affinity"] in ("INTEGER", "REAL") else "") for f in JOBS["fields"]}
        row.update(id="job", sessionId="session", type="TRANSCRIBE", provider="openrouter", state="QUEUED", model="chosen/model",
                   optionsJson='{"cloudChunkMinutes":10}', createdAt=1, updatedAt=1)
        self.db.execute("INSERT INTO jobs (" + ",".join(row) + ") VALUES (" + ",".join("?" for _ in row) + ")", list(row.values()))
        self.p = dict(id="job", now=2, code="companion_only", message="fixture", expectedProvider="openrouter", expectedModel="chosen/model",
                      expectedOptions=row["optionsJson"], expectedAttempts=0, expectedQueued=True, phase="quota", model="chosen/model", state="PREPARING",
                      nextOptions=row["optionsJson"], provider="custom", options=row["optionsJson"], **{"from": "openrouter", "to": "custom"})

    def tearDown(self):
        self.db.close()

    def row(self):
        return dict(self.db.execute("SELECT * FROM jobs WHERE id='job'").fetchone())

    def test_privacy_migration_fences_every_old_error_disposition_and_keeps_correct_queue(self):
        self.assertEqual(1, self.db.execute(query("moveTranscription"), self.p).rowcount)
        snapshot = self.row()
        for op in ("fail", "requeueOwned", "cancelOwned", "startOwned"):
            self.assertEqual(0, self.db.execute(query(op), self.p).rowcount, op)
            self.assertEqual(snapshot, self.row())
        self.assertEqual(("custom", "QUEUED", None), tuple(snapshot[k] for k in ("provider", "state", "model")))
        self.assertIsNotNone(self.db.execute(query("nextQueued"), self.p).fetchone())

    def test_same_provider_model_options_and_attempt_changes_reject_old_dispositions(self):
        original = self.row()
        for column, value in (("model", "other/model"), ("optionsJson", None), ("attempts", 1), ("state", "PREPARING")):
            with self.subTest(column=column):
                self.db.execute("UPDATE jobs SET model=:model, optionsJson=:optionsJson, attempts=:attempts, state=:state", original)
                self.db.execute("UPDATE jobs SET " + column + "=?", (value,))
                snapshot = self.row()
                for op in ("fail", "requeueOwned", "cancelOwned", "startOwned"):
                    self.assertEqual(0, self.db.execute(query(op), self.p).rowcount, op)
                    self.assertEqual(snapshot, self.row())

    def test_current_owner_can_fail_before_start_without_losing_model_or_options(self):
        self.assertEqual(1, self.db.execute(query("fail"), self.p).rowcount)
        row = self.row()
        self.assertEqual(("FAILED", "chosen/model", '{"cloudChunkMinutes":10}', 0), tuple(row[k] for k in ("state", "model", "optionsJson", "attempts")))

    def test_started_attempt_failure_and_progress_states_preserve_attempt_counter(self):
        self.assertEqual(1, self.db.execute(query("startOwned"), self.p).rowcount)
        self.assertEqual(0, self.db.execute(query("fail"), self.p).rowcount)
        self.p.update(expectedAttempts=1, expectedQueued=False)
        self.db.execute("UPDATE jobs SET state='TRANSCRIBING'")
        self.assertEqual(1, self.db.execute(query("fail"), self.p).rowcount)
        self.assertEqual(("FAILED", 1, "chosen/model"), tuple(self.row()[k] for k in ("state", "attempts", "model")))

    def test_cancel_wins_before_or_after_owned_quota_and_failure(self):
        for queued in (True, False):
            for first in (True, False):
                with self.subTest(queued=queued, cancel_first=first):
                    self.db.execute("UPDATE jobs SET state=?, finishedAt=NULL", ("QUEUED" if queued else "PREPARING",))
                    self.p["expectedQueued"] = queued
                    for op in (("requestCancel", "requeueOwned") if first else ("requeueOwned", "requestCancel")):
                        # A queued cancel is already terminal. Running cancel is finalized by requeue.
                        expected = 0 if queued and first and op == "requeueOwned" else 1
                        self.assertEqual(expected, self.db.execute(query(op), self.p).rowcount)
                    self.assertEqual("CANCELLED", self.row()["state"])
                    self.assertEqual(0, self.db.execute(query("fail"), self.p).rowcount)
                    self.assertIsNotNone(self.row()["finishedAt"])

    def test_null_and_empty_options_remain_distinct(self):
        self.db.execute("UPDATE jobs SET model=NULL, optionsJson=NULL")
        self.p.update(expectedModel=None, expectedOptions="")
        self.assertEqual(0, self.db.execute(query("fail"), self.p).rowcount)
        self.p["expectedOptions"] = None
        self.assertEqual(1, self.db.execute(query("fail"), self.p).rowcount)


if __name__ == "__main__":
    unittest.main(verbosity=2)
