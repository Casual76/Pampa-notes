"""Offline fixtures only. PYTHONDONTWRITEBYTECODE=1 python -m unittest test_forced_alignment -v."""
from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
import archive
import whisperx_server as server
import test_companion as fixtures

AUDIO = b"synthetic audio bytes - never decoded by fixture tests"
SHA = hashlib.sha256(AUDIO).hexdigest()
DICTIONARY = {c: i for i, c in enumerate("abcdefghijklmnopqrstuvwxyzèé|")}


def request_data(text="hello world", segments=None):
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    return {"protocol_version": 1, "source": {"raw_id": "raw-1", "raw_version": 123456,
            "raw_sha256": digest, "parts": [{"id": "part-1", "sha256": SHA}]},
            "part_id": "part-1", "text": text, "text_sha256": digest,
            "language": "en", "segments": segments or []}


def fake_result(text):
    # Deliberately fake CTC evidence, never an accuracy test.
    chars = [{"char": c, "start": i * .02, "end": (i + 1) * .02, "score": .95}
             for i, c in enumerate(text)]
    return {"segments": [{"text": "UNTRUSTED MODEL TEXT", "chars": chars,
                           "words": [{"word": "untrusted", "start": 0, "end": 9}]}]}


class AlignmentPureTest(fixtures.StateMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.temp = tempfile.TemporaryDirectory(prefix="pampa-align-tests-")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "synthetic.audio"
        self.path.write_bytes(AUDIO)
        server.STATE.update(owner="owner@example.com", device="cpu")
        self.caller = server.Caller("owner", "token", bearer="fixture-owner")
        self.progress = server.JobProgress()
        self.account = server.alignment_account(self.caller)
        self.align = mock.Mock(side_effect=lambda transcript, *a, **k: fake_result(transcript[0]["text"]))
        self.engine = types.SimpleNamespace(align=self.align, transcribe=mock.Mock(side_effect=AssertionError("ASR forbidden")))
        for patcher in (
            mock.patch.dict(sys.modules, {"whisperx": self.engine}),
            mock.patch.object(server, "align_model_for", return_value=(object(), {"language": "en", "dictionary": DICTIONARY})),
            mock.patch.object(server, "probe_duration", return_value=20.0),
            mock.patch.object(server, "load_audio", return_value=np.zeros(20 * 16000, dtype=np.float32)),
            mock.patch.object(server, "_transcribe", side_effect=AssertionError("ASR forbidden")),
            mock.patch.object(server, "ensure_model", side_effect=AssertionError("Whisper model forbidden")),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_alignment(self, data=None):
        data = server.parse_alignment(json.dumps(data or request_data()))
        return server._force_align(self.path, data, self.caller, self.account, self.progress)

    def test_exact_text_punctuation_whitespace_and_language(self):
        text = "  Héllo,\tworld!\n\nè  café…  "
        data = request_data(text)
        before = copy.deepcopy(data)
        answer = self.run_alignment(data)
        self.assertEqual(data, before)
        for key in ("text", "language", "text_sha256", "source", "part_id"):
            self.assertEqual(answer[key], data[key])
        self.assertEqual(answer["status"], "aligned")
        self.assertEqual((answer["offset_unit"], answer["time_unit"]), ("utf16", "ms"))
        self.engine.transcribe.assert_not_called()
        server._transcribe.assert_not_called()
        server.ensure_model.assert_not_called()
        self.assertEqual(self.align.call_args.args[0][0]["text"], text)
        self.assertEqual(self.align.call_args.kwargs["interpolate_method"], "ignore")
        self.assertTrue(self.align.call_args.kwargs["return_char_alignments"])

    def test_utf16_surrogate_pair_and_combining_marks_preserved(self):
        text = "😀 hi e\u0301"
        answer = self.run_alignment(request_data(text))
        self.assertEqual(answer["text"], text)
        self.assertEqual([(t["text_start"], t["text_end"]) for t in answer["timings"]], [(0, 2), (3, 5), (6, 8)])
        self.assertEqual(answer["timings"][0]["status"], "missing")
        self.assertEqual(answer["timings"][1]["status"], "measured")
        self.assertEqual(answer["timings"][2]["reason"], "unknown_character")

    def test_surrogate_split_invalid_and_lone_surrogate_invalid(self):
        data = request_data("😀 hi", [{"text_start": 1, "text_end": 5, "start_ms": 0, "end_ms": 1000}])
        with self.assertRaises(server.HTTPException) as ctx:
            server.parse_alignment(json.dumps(data))
        self.assertEqual(ctx.exception.status_code, 400)
        data["text"] = "\ud800"
        with self.assertRaises(server.HTTPException):
            server.parse_alignment(json.dumps(data))

    def test_hash_and_protocol_rejected(self):
        for field, value in (("text_sha256", "0" * 64), ("protocol_version", 2), ("protocol_version", True), ("language", ""), ("part_id", "different")):
            with self.subTest(field=field, value=value):
                data = request_data()
                data[field] = value
                with self.assertRaises(server.HTTPException):
                    server.parse_alignment(json.dumps(data))
        for field, value in (("raw_id", ""), ("raw_version", 0), ("raw_sha256", "bad")):
            data = request_data()
            data["source"][field] = value
            with self.assertRaises(server.HTTPException):
                server.parse_alignment(json.dumps(data))

    def test_stale_audio_hash_rejected_before_alignment(self):
        self.path.write_bytes(b"changed original audio")
        with self.assertRaises(server.HTTPException) as ctx:
            self.run_alignment()
        self.assertEqual((ctx.exception.status_code, ctx.exception.detail), (409, "sha_mismatch"))
        self.align.assert_not_called()

    def test_audio_changed_during_alignment_rejected(self):
        def changed(transcript, *a, **k):
            self.path.write_bytes(b"changed while processing")
            return fake_result(transcript[0]["text"])
        self.align.side_effect = changed
        with self.assertRaises(server.HTTPException) as ctx:
            self.run_alignment()
        self.assertEqual(ctx.exception.detail, "sha_mismatch")

    def test_long_untimed_audio_defers_without_loading_model_or_decoding(self):
        server.probe_duration.return_value = 3600
        answer = self.run_alignment()
        self.assertEqual(answer["status"], "deferred")
        server.align_model_for.assert_not_called()
        server.load_audio.assert_not_called()
        self.assertTrue(all(t["start_ms"] is None and t["status"] == "missing" for t in answer["timings"]))

    def test_long_untimed_text_defers(self):
        answer = self.run_alignment(request_data("a" * 401))
        self.assertEqual(answer["status"], "deferred")
        self.align.assert_not_called()

    def test_coarse_windows_bound_actual_matrix_and_preserve_gaps(self):
        data = request_data("hello world", [
            {"text_start": 0, "text_end": 5, "start_ms": 1000, "end_ms": 21000},
            {"text_start": 6, "text_end": 11, "start_ms": 60000, "end_ms": 80000}])
        server.probe_duration.return_value = 3600
        answer = self.run_alignment(data)
        self.assertEqual(answer["status"], "aligned")
        self.assertEqual(self.align.call_count, 2)
        self.assertEqual([call.kwargs["duration_s"] for call in server.load_audio.call_args_list], [20.0, 20.0])
        self.assertGreaterEqual(answer["timings"][1]["start_ms"], 60000)

    def test_30second_400unit_limits_inclusive_and_excess_deferred(self):
        server.probe_duration.return_value = 30
        server.load_audio.return_value = np.zeros(30 * 16000, dtype=np.float32)
        self.assertEqual(self.run_alignment(request_data("a" * 400))["status"], "aligned")
        self.align.reset_mock()
        for units, duration in ((401, 30000), (400, 30001)):
            data = request_data("a" * units, [{"text_start": 0, "text_end": units, "start_ms": 0, "end_ms": duration}])
            server.probe_duration.return_value = 31
            answer = self.run_alignment(data)
            self.assertEqual(answer["status"], "deferred")
            self.assertEqual(answer["timings"][0]["reason"], "window_limit")
        self.align.assert_not_called()

    def test_1000window_limit(self):
        segments = [{"text_start": 2*i, "text_end": 2*i+1, "start_ms": i*1000, "end_ms": (i+1)*1000} for i in range(1000)]
        data = request_data("a " * 1001, segments)
        self.assertEqual(len(server.parse_alignment(json.dumps(data))["segments"]), 1000)
        data["segments"].append({"text_start": 2000, "text_end": 2001, "start_ms": 1000000, "end_ms": 1001000})
        with self.assertRaises(server.HTTPException) as ctx:
            server.parse_alignment(json.dumps(data))
        self.assertEqual(ctx.exception.detail, "too_many_windows")

    def test_window_outside_audio_rejected(self):
        data = request_data(segments=[{"text_start": 0, "text_end": 11, "start_ms": 19000, "end_ms": 21000}])
        with self.assertRaises(server.HTTPException) as ctx:
            self.run_alignment(data)
        self.assertEqual(ctx.exception.detail, "audio_bounds_mismatch")
        self.align.assert_not_called()

    def test_overlapping_reversed_windows_rejected(self):
        for start, a in ((1, 4), (0, 6)):
            data = request_data(segments=[{"text_start": 0, "text_end": 5, "start_ms": 0, "end_ms": 5000},
                                          {"text_start": a, "text_end": 11, "start_ms": start, "end_ms": 10000}])
            with self.assertRaises(server.HTTPException):
                server.parse_alignment(json.dumps(data))

    def test_raw_character_guard_ignores_interpolated_words(self):
        result = fake_result("hello 12")
        result["segments"][0]["words"] = [{"word": "hello", "start": 0, "end": 1}, {"word": "12", "start": 1, "end": 2}]
        tokens = server.alignment_overlay("hello 12", result, DICTIONARY, 20000)
        self.assertEqual(tokens[0]["status"], "measured")
        self.assertEqual(tokens[1]["status"], "missing")
        self.assertIsNone(tokens[1]["start_ms"])

    def test_missing_low_confidence_nonfinite_outside_or_nonmonotonic_times(self):
        for update in ({"start": None}, {"score": .1}, {"end": float("nan")}, {"end": 30}, {"start": -.1}, {"end": 0}):
            result = fake_result("hello world")
            result["segments"][0]["chars"][0].update(update)
            self.assertEqual(server.alignment_overlay("hello world", result, DICTIONARY, 20000)[0]["status"], "missing")
        result = fake_result("hello world")
        result["segments"][0]["chars"][6].update(start=0, end=.01)
        tokens = server.alignment_overlay("hello world", result, DICTIONARY, 20000)
        self.assertEqual([t["status"] for t in tokens], ["measured", "missing"])

    def test_character_mapping_drift_never_guessed(self):
        for result in (fake_result("HELLO"), {"segments": [{"chars": []}]}, fake_result("hellohello")):
            tokens = server.alignment_overlay("hello", result, DICTIONARY, 20000)
            self.assertEqual(tokens[0]["reason"], "character_mapping_mismatch")

    def test_cancellation_and_account_change_stop_without_fallback(self):
        self.progress.cancel()
        with self.assertRaises(server.JobCancelled):
            self.run_alignment()
        self.progress = server.JobProgress()
        server.STATE["owner"] = "different@example.com"
        with self.assertRaises(server.HTTPException) as ctx:
            self.run_alignment()
        self.assertEqual(ctx.exception.detail, "account_changed")
        self.align.assert_not_called()

    def test_account_change_in_model_callback_rejects_result(self):
        def changed(transcript, *a, **k):
            server.STATE["owner"] = "other@example.com"
            return fake_result(transcript[0]["text"])
        self.align.side_effect = changed
        with self.assertRaises(server.HTTPException) as ctx:
            self.run_alignment()
        self.assertEqual(ctx.exception.detail, "account_changed")

    def test_queued_cancellation_never_enters_acoustic_worker(self):
        async def exercise():
            gate = server.PriorityGate()
            work = server.SharedWork(None, server.JobProgress("alignment-queued"))
            client = server.JobProgress("client-queued")
            client.follow(work.progress)
            request = types.SimpleNamespace(is_disconnected=mock.AsyncMock(return_value=False))
            with mock.patch.object(server, "GATE", gate), mock.patch.object(server, "_force_align") as acoustic:
                async with gate.slot(0):
                    work.task = asyncio.create_task(server._run_alignment_work(work, self.path, request_data(), self.caller, self.account))
                    client.cancel()
                    with self.assertRaises(server.JobCancelled):
                        await server._await_work(work, request, client)
                    with self.assertRaises(server.JobCancelled):
                        await work.task
                acoustic.assert_not_called()
                self.assertEqual(gate.waiting, 0)
                self.assertEqual(work.progress.state, "failed")
        asyncio.run(exercise())

    def test_model_failure_and_unsupported_language_are_explicit_missing(self):
        self.align.side_effect = RuntimeError("synthetic failure")
        answer = self.run_alignment()
        self.assertEqual(answer["status"], "deferred")
        self.assertTrue(all(t["reason"] == "alignment_failed" for t in answer["timings"]))
        self.align.reset_mock()
        data = request_data()
        data["language"] = "xx"
        answer = self.run_alignment(data)
        self.assertEqual(answer["language"], "xx")
        self.assertTrue(all(t["reason"] == "language_unsupported" for t in answer["timings"]))
        self.align.assert_not_called()

    def test_alignment_only_model_can_be_unloaded_and_expires_when_idle(self):
        async def exercise():
            with mock.patch.dict(server.STATE, {"model": None, "align": {"en": object()}, "busy": False,
                                                "idle_seconds": 60, "last_used": 0}), \
                    mock.patch.object(server, "GATE", server.PriorityGate()), \
                    mock.patch.object(server, "unload_model") as unload:
                self.assertTrue(await server.unload_now("fixture"))
                unload.assert_called_once()
                unload.reset_mock()
                watcher = asyncio.create_task(server.idle_watch())
                with mock.patch.object(server.asyncio, "sleep", side_effect=[None, asyncio.CancelledError()]):
                    with self.assertRaises(asyncio.CancelledError):
                        await watcher
                unload.assert_called_once()
        asyncio.run(exercise())


class AlignmentHttpTest(fixtures.StateMixin, unittest.TestCase):
    call = fixtures.ServerTest.call

    @classmethod
    def setUpClass(cls):
        fixtures.ServerTest.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        fixtures.ServerTest.tearDownClass.__func__(cls)

    def setUp(self):
        super().setUp()
        server.JOBS.clear()
        server.STATE.update(token="fixture-secret", owner=fixtures.FakeWorker.OWNER,
                            index_url=self.worker.url, accept_anonymous=False)

    def post(self, data=None, audio=AUDIO, bearer="pt_good", job_id="align-job-123"):
        data = data or request_data()
        boundary = "pampa-alignment-fixture"
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="alignment"\r\n\r\n' + json.dumps(data)).encode()
        if audio is not None:
            body += (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.wav"\r\nContent-Type: audio/wav\r\n\r\n').encode() + audio
        body += f'\r\n--{boundary}--\r\n'.encode()
        return self.call("POST", "/v1/audio/alignments", bearer, body,
                         {"Content-Type": f"multipart/form-data; boundary={boundary}", "X-Pampa-Job": job_id})

    def test_capability_advertised_with_old_features(self):
        with mock.patch.object(server, "gpu_status", return_value=None), mock.patch.object(server, "vram_gb", return_value=0):
            status, _, body = self.call("GET", "/health")
        self.assertEqual(status, 200)
        features = json.loads(body)["features"]
        self.assertIn("forced_alignment_v1", features)
        self.assertIn("by_ref", features)
        self.assertIn("job_options", features)

    def test_auth_same_account_and_guest_denied(self):
        for token, expected in (("", 401), ("pt_wrong_account", 401), ("pg_friend", 403)):
            self.assertEqual(self.post(bearer=token)[0], expected)
        server.STATE["accept_anonymous"] = True
        self.assertEqual(self.post(bearer="")[0], 403)
        with mock.patch.object(server, "_force_align", return_value={"status": "deferred"}):
            self.assertEqual(self.post(bearer="fixture-secret")[0], 200)

    def test_cached_old_owner_ticket_cannot_start_for_new_owner(self):
        self.assertTrue(server.verify_ticket("pt_good"))
        server.STATE["owner"] = "new-owner@example.com"
        self.assertEqual(self.post()[0], 401)

    def test_auth_rejected_before_body_is_read(self):
        async def exercise(caller):
            sent = []
            async def receive():
                self.fail("unauthorized body read")
            async def send(item):
                sent.append(item)
            scope = {"type": "http", "path": "/v1/audio/alignments", "method": "POST", "headers": []}
            with mock.patch.object(server, "identify", return_value=caller):
                await server.AuthGate(mock.AsyncMock())(scope, receive, send)
            self.assertEqual(sent[0]["status"], 403)
        for caller in (server.Caller("guest", "guest"), server.Caller("owner", "anonymous")):
            asyncio.run(exercise(caller))

    def test_upload_and_owned_reference_hash_validation(self):
        # Exercise real validation and hashing; defer before any model load.
        with mock.patch.object(server, "probe_duration", return_value=3600), mock.patch.object(server, "align_model_for") as loader:
            status, _, body = self.post()
            self.assertEqual(status, 200)
            answer = json.loads(body)
            self.assertEqual(answer["text"], request_data()["text"])
            self.assertEqual(answer["source"], request_data()["source"])
            self.assertEqual(answer["audio_sha256"], SHA)
            self.assertEqual(answer["status"], "deferred")
            self.assertEqual(self.post(audio=b"other audio", job_id="wrong-hash-123")[0], 409)
            self.assertEqual(self.post(audio=None, job_id="missing-blob-123")[0], 404)
            archive.ARCHIVE.store(SHA, "fixture.wav", "audio/wav", [AUDIO])
            self.assertEqual(self.post(audio=None, job_id="archive-ref-123")[0], 200)
            record = archive.ARCHIVE.get(SHA)
            record["path"].write_bytes(b"archive corrupted")
            self.assertEqual(self.post(audio=None, job_id="archive-bad-123")[0], 409)
            loader.assert_not_called()

    def test_job_source_status_guest_cancel_and_stale_id(self):
        with mock.patch.object(server, "probe_duration", return_value=3600):
            self.assertEqual(self.post()[0], 200)
        status, _, body = self.call("GET", "/v1/jobs/align-job-123", "pt_far")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["source"], request_data()["source"])
        self.assertEqual(self.call("DELETE", "/v1/jobs/align-job-123", "pg_friend")[0], 404)
        self.assertEqual(self.call("GET", "/v1/jobs/align-job-123/partial", "pg_friend")[0], 404)
        changed = request_data()
        changed["source"]["raw_version"] += 1
        self.assertEqual(self.post(changed)[0], 409)
        # Existing transcription registration cannot replace an alignment job with the same ID.
        job = server.JOBS.get("align-job-123")
        self.assertIsNone(server.JOBS.open("align-job-123", server.Caller("owner", "ticket", bearer="pt_good")))
        self.assertIs(server.JOBS.get("align-job-123"), job)
        server.STATE["owner"] = "other@example.com"
        self.assertEqual(self.call("GET", "/v1/jobs/align-job-123", "fixture-secret")[0], 404)

    def test_cancellation_during_job_and_fresh_request_after_restart(self):
        started, release = threading.Event(), threading.Event()
        paths = []
        def working(path, data, caller, account, progress):
            paths.append(path)
            started.set()
            release.wait(5)
            server.alignment_fence(caller, account, progress)
            return {"status": "deferred"}
        result = []
        with mock.patch.object(server, "_force_align", side_effect=working):
            sender = threading.Thread(target=lambda: result.append(self.post()))
            sender.start()
            try:
                self.assertTrue(started.wait(3))
                self.assertEqual(self.call("DELETE", "/v1/jobs/align-job-123", "pt_far")[0], 200)
            finally:
                release.set()
                sender.join(5)
        self.assertEqual(result[0][0], 499)
        self.assertFalse(paths[0].exists())
        status, _, body = self.call("GET", "/v1/jobs/align-job-123", "pt_good")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["state"], "failed")
        server.JOBS.clear()  # Restart has a fresh registry/instance, never a phantom successful job.
        self.assertEqual(self.call("GET", "/v1/jobs/align-job-123", "pt_good")[0], 404)
        with mock.patch.object(server, "probe_duration", return_value=3600):
            self.assertEqual(self.post(job_id="fresh-after-restart")[0], 200)

    def test_invalid_payload_missing_header_and_restart_rejection(self):
        self.assertEqual(self.post(job_id="")[0], 400)
        data = request_data()
        data["text"] += " changed without hash"
        self.assertEqual(self.post(data)[0], 400)
        with mock.patch.dict(server.STATE, {"restarting": True}):
            status, headers, body = self.post(job_id="restarting-job-123")
        self.assertEqual(status, 503)
        self.assertEqual({k.lower(): v for k, v in headers.items()}.get("retry-after"), "20")


if __name__ == "__main__":
    unittest.main()
