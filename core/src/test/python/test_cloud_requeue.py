"""Execute production Room DAO SQL against the generated schema; no Android runtime needed.

Run: python -B core/src/test/python/test_cloud_requeue.py
This verifies SQL state interleavings, not Room/device scheduling or WorkManager execution.
"""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = (ROOT / "core/src/main/kotlin/dev/pampa/pampanotes/core/db/Daos.kt").read_text(encoding="utf-8")
SCHEMA = json.loads((ROOT / "core/schemas/dev.pampa.pampanotes.core.db.PampaDatabase/15.json").read_text(encoding="utf-8"))["database"]
JOBS = next(entity for entity in SCHEMA["entities"] if entity["tableName"] == "jobs")


def query(name):
    stop = SOURCE.index("suspend fun " + name + "(")
    start = SOURCE.rfind("@Query(", 0, stop)
    return "".join(json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', SOURCE[start:stop]))


class CloudRequeueSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute(JOBS["createSql"].replace("${TABLE_NAME}", "jobs"))
        row = {field["columnName"]: (None if not field.get("notNull", False) else
                0 if field["affinity"] in ("INTEGER", "REAL") else "") for field in JOBS["fields"]}
        row.update(id="job", sessionId="session", type="TRANSCRIBE", provider="mistral",
                   state="TRANSCRIBING", model="voxtral-mini-transcribe-3", optionsJson='{"fixture":"keep"}',
                   createdAt=1, updatedAt=1)
        self.db.execute("INSERT INTO jobs (" + ",".join("`" + key + "`" for key in row) + ") VALUES (" +
                        ",".join("?" for _ in row) + ")", list(row.values()))
        self.params = dict(id="job", now=2, updatedAt=3, phase="quota", sessionId="session", provider="mistral")

    def tearDown(self):
        self.db.close()

    def row(self):
        return self.db.execute("SELECT * FROM jobs WHERE id='job'").fetchone()

    def test_cancel_before_quota_exit_finishes_without_restart(self):
        self.assertEqual(1, self.db.execute(query("requestCancelIfRunning"), self.params).rowcount)
        self.assertEqual(1, self.db.execute(query("requeue"), self.params).rowcount)
        row = self.row()
        self.assertEqual("CANCELLED", row["state"])
        self.assertEqual(3, row["finishedAt"])
        self.assertIsNone(row["phase"])
        self.assertIsNone(self.db.execute(query("activeForSession"), self.params).fetchone())
        self.assertIsNone(self.db.execute(query("nextQueued"), self.params).fetchone())
        self.assertEqual("voxtral-mini-transcribe-3", row["model"])
        self.assertEqual('{"fixture":"keep"}', row["optionsJson"])

    def test_cancel_after_quota_exit_finishes_queued_job(self):
        self.db.execute(query("requeue"), self.params)
        self.assertEqual("QUEUED", self.row()["state"])
        self.assertEqual(1, self.db.execute(query("cancelIfQueued"), self.params).rowcount)
        self.assertEqual("CANCELLED", self.row()["state"])
        self.assertIsNone(self.db.execute(query("activeForSession"), self.params).fetchone())

    def test_quota_wait_preserves_pinned_choice_and_remains_queued(self):
        self.db.execute(query("requeue"), self.params)
        row = self.row()
        self.assertEqual(("QUEUED", "quota", "mistral", "voxtral-mini-transcribe-3"),
                         tuple(row[key] for key in ("state", "phase", "provider", "model")))
        self.assertIsNone(row["finishedAt"])
        self.assertIsNotNone(self.db.execute(query("nextQueued"), self.params).fetchone())

    def test_terminal_or_missing_jobs_cannot_be_requeued(self):
        for state in ("DONE", "CANCELLED"):
            self.db.execute("UPDATE jobs SET state=?, phase='terminal', finishedAt=7", (state,))
            self.assertEqual(0, self.db.execute(query("requeue"), self.params).rowcount)
            self.assertEqual((state, "terminal", 7), tuple(self.row()[key] for key in ("state", "phase", "finishedAt")))
        self.db.execute("DELETE FROM jobs")
        self.assertEqual(0, self.db.execute(query("requeue"), self.params).rowcount)


if __name__ == "__main__":
    unittest.main(verbosity=2)
