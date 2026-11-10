"""Tests for make_documentation.py on a synthetic export. Run: python3 -m unittest discover -s tools"""
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import make_documentation as md  # noqa: E402
from openpyxl import load_workbook  # noqa: E402


def row(seq, eid, action, kind="action", rev=0, **kw):
    r = {"schema": 2, "seq": seq, "entry_id": eid, "revision_n": rev, "kind": kind, "scenario_id": "sc",
         "device_label": "phoneA", "profile_id": 1, "utc_ms": 1794146400000 + seq * 1000, "tz_id": "America/New_York",
         "tz_offset_min": -300, "elapsed_ms": 1000 + seq, "boot_count": 2, "boot_wall_ms": 0, "network_time_ms": None,
         "gnss_time_ms": None, "time_quality": "exact", "window_s": None, "edited_utc_ms": 0,
         "app_package": "com.example.chat", "app_version": "1", "account": None, "action": action, "content": None,
         "counterpart": None, "direction": None, "conversation_id": None, "refers_to": None, "expected_artifacts": [],
         "detail": None, "source": "manual", "reviewed": True, "screenshot": None}
    r.update(kw)
    return r


def make_export(tmp, rows, shot_bytes=b"png", tamper=False):
    d = os.path.join(tmp, "kdl_sc_phoneA_x")
    os.makedirs(os.path.join(d, "screens"))
    data = "".join(json.dumps(r) + "\n" for r in rows).encode()
    with open(os.path.join(d, "actions.jsonl"), "wb") as f:
        f.write(data)
    with open(os.path.join(d, "screens", "s1.png"), "wb") as f:
        f.write(shot_bytes + (b"x" if tamper else b""))
    manifest = {"scenario_id": "sc", "device_label": "phoneA", "profiles": [{"model": "Test"}],
                "apps_under_test": [{"package": "com.example.chat", "label": "Chat"}],
                "files": {"actions.jsonl": {"sha256": hashlib.sha256(data).hexdigest()}},
                "screenshots": [{"entry_id": "a", "file": "s1.png", "status": "ok",
                                 "sha256_at_capture": hashlib.sha256(shot_bytes).hexdigest()}]}
    with open(os.path.join(d, "manifest.json"), "w") as f:
        json.dump(manifest, f)
    return d


class DocTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.rows = [
            row(1, "a", "sent_message", content="=HYPERLINK(\"http://x\")", source="auto_rule", reviewed=False),
            # A revision keeps the original action time, as the app does.
            row(2, "a", "sent_message", rev=1, content="=HYPERLINK(\"http://x\")", source="auto_rule", reviewed=True,
                utc_ms=1794146401000, elapsed_ms=1001),
            row(3, "b", "deleted_message"),
            row(4, "b", "deleted_message", kind="tombstone", rev=1),
            row(5, "m", "mark", kind="auto_event", source="mark"),
            row(6, "c", "took_photo", app_package=None),
        ]

    def test_sheets_current_rows_and_text_cells(self):
        d = make_export(self.tmp, self.rows)
        out = os.path.join(self.tmp, "doc.xlsx")
        info = md.build([md.load_export(d)], out)
        wb = load_workbook(out)
        self.assertIn("Chat", wb.sheetnames)
        self.assertIn("Device and system", wb.sheetnames)
        chat = list(wb["Chat"].iter_rows(min_row=2, values_only=True))
        self.assertEqual(1, len(chat))                       # tombstoned entry gone, one revision shown
        self.assertEqual("yes", chat[0][12])                 # accepted revision
        self.assertTrue(chat[0][5].startswith("=HYPERLINK"))  # kept as text
        self.assertEqual("s", wb["Chat"].cell(row=2, column=6).data_type)
        self.assertEqual("2026-11-08", chat[0][1])
        self.assertEqual("09:00:01", chat[0][2])
        self.assertEqual("UTC-05:00", chat[0][3])
        review = list(wb["Needs review"].iter_rows(min_row=2, values_only=True))
        self.assertEqual(["mark without a label"], [r[6] for r in review])
        self.assertEqual(2, info["actions"])

    def test_hash_checks(self):
        d = make_export(self.tmp, self.rows, tamper=True)
        e = md.load_export(d)
        self.assertTrue(any("MISMATCH" in v for _, v in e["checks"]))
        with open(os.path.join(d, "actions.jsonl"), "ab") as f:
            f.write(b"\n")
        e = md.load_export(d)
        self.assertTrue(any(k == "actions.jsonl sha256" and "MISMATCH" in v for k, v in e["checks"]))

    def test_order_follows_device_clock_not_wall_clock(self):
        rows = [row(1, "x", "first", utc_ms=1794146500000, elapsed_ms=10),   # clock set forward before it
                row(2, "y", "second", utc_ms=1794146400000, elapsed_ms=20)]
        d = make_export(self.tmp, rows)
        out = os.path.join(self.tmp, "o.xlsx")
        md.build([md.load_export(d)], out)
        chat = list(load_workbook(out)["Chat"].iter_rows(min_row=2, values_only=True))
        self.assertEqual(["first", "second"], [r[4] for r in chat])

    def test_mark_label_gets_mark_screenshot_and_control_chars_survive(self):
        rows = [row(1, "a", "mark", kind="auto_event", source="mark"),
                row(2, "lab", "sent_media", refers_to="a", source="mark", content="bell\x07here")]
        d = make_export(self.tmp, rows)
        out = os.path.join(self.tmp, "o.xlsx")
        md.build([md.load_export(d)], out)
        wb = load_workbook(out)
        chat = list(wb["Chat"].iter_rows(min_row=2, values_only=True))
        self.assertTrue(chat[0][13].endswith("s1.png"))
        self.assertEqual("bell[U+0007]here", chat[0][5])
        ev = list(wb["Captured events"].iter_rows(min_row=2, values_only=True))
        self.assertTrue(ev[0][8].endswith("s1.png"))

    def test_capture_pauses_are_paired_and_summarized(self):
        sys_row = dict(kind="system_event", source="system", app_package=None)
        rows = [row(1, "c1", "capture_service_connected", elapsed_ms=100, **sys_row),
                row(10, "s1", "capture_session_start", elapsed_ms=500, **sys_row),
                row(2, "d1", "capture_service_disconnected", elapsed_ms=1000, **sys_row),
                row(3, "c2", "capture_service_connected", elapsed_ms=2500, **sys_row),
                row(11, "s2", "capture_session_stop", elapsed_ms=2800, **sys_row),
                row(4, "d2", "capture_service_disconnected", elapsed_ms=3000, **sys_row),
                row(5, "c3", "capture_service_connected", elapsed_ms=3500, **sys_row),
                row(6, "c4", "capture_service_connected", elapsed_ms=4000, **sys_row),   # killed, no disconnect
                row(7, "d3", "capture_service_disconnected", elapsed_ms=5000, **sys_row),
                row(8, "c5", "capture_service_connected", elapsed_ms=50, boot_count=3, **sys_row),  # after reboot
                row(9, "d4", "capture_service_disconnected", elapsed_ms=90, boot_count=3, **sys_row)]
        pauses, unexplained = md.capture_pauses(md.current(rows))
        self.assertEqual([1.5, 0.5, None, None], [p[2] for p in pauses])
        self.assertIsNone(pauses[3][1])
        self.assertEqual(1, unexplained)
        self.assertEqual([True, False, False, False], [p[0]["_in_session"] for p in pauses])
        d = make_export(self.tmp, rows)
        out = os.path.join(self.tmp, "p.xlsx")
        md.build([md.load_export(d)], out)
        wb = load_workbook(out)
        summary = [r[1] for r in wb["Scenario"].iter_rows(values_only=True) if r[0] == "Capture pauses"][0]
        self.assertTrue(summary.startswith("1 pause(s) during a capture session (1.5 s in total, longest 1.5 s); "
                                           "4 in all (2.0 s, longest 1.5 s)"), summary)
        self.assertIn("1 not reconnected", summary)
        self.assertIn("1 across a reboot", summary)
        self.assertIn("1 reconnect(s) with no recorded disconnect", summary)
        scen = [str(r[1] or "") for r in wb["Scenario"].iter_rows(values_only=True)]
        self.assertFalse(any(v.startswith("capture_service") for v in scen))
        self.assertEqual(4, wb["Capture pauses"].max_row - 1)

    def test_chain_vector_matches_kotlin(self):
        r = {"account": None, "action": "sent_message", "app_package": "com.example.chat", "app_version": "1.2",
             "boot_count": 3, "content": "H\u00e9 \"q\" \\ line\nnext \U0001F600 bell\u0007 del\u007f", "conversation_id": None,
             "counterpart": "phone B", "detail": None, "device_label": "phoneA", "direction": "out",
             "edited_utc_ms": 1700000000500, "elapsed_ms": 123456, "entry_id": "e1", "expected_artifacts": ["aleapp:x", "y"],
             "gnss_time_ms": None, "kind": "action", "network_time_ms": 1700000000000, "prev_hash": "abc", "profile_id": 1,
             "refers_to": None, "revision_n": 0, "reviewed": True, "scenario_id": "sc", "seq": 7, "source": "manual",
             "time_quality": "exact", "tz_id": "America/New_York", "tz_offset_min": -300, "utc_ms": 1700000000123,
             "window_s": 5.894}
        # Same vector as ChainTest.canonicalMatchesPython in the app.
        self.assertEqual("23843a77b64530aba2b30d3ddaac1b19013cfcf924101dd6e799fef6ae99ac44",
                         hashlib.sha256(md.chain_canonical(r).encode("ascii")).hexdigest())

    def _chained(self, rows):
        prev = ""
        for r in sorted(rows, key=lambda x: x["seq"]):
            r["prev_hash"] = prev
            r["row_hash"] = hashlib.sha256(md.chain_canonical(r).encode("ascii")).hexdigest()
            prev = r["row_hash"]
        return rows

    def test_verify_chain(self):
        rows = self._chained([row(i, f"e{i}", "a", content=f"c{i}") for i in range(1, 6)])
        self.assertTrue(md.verify_chain(rows)[0].startswith("intact, 5 rows"))
        edited = [dict(r) for r in rows]; edited[2]["content"] = "changed"
        self.assertEqual(3, md.verify_chain(edited)[1])
        removed = [dict(r) for r in rows if r["seq"] != 2]
        self.assertEqual(3, md.verify_chain(removed)[1])
        self.assertTrue(md.verify_chain([row(1, "x", "a")])[0].startswith("not present"))

    def test_chain_check_catches_rows_cut_from_the_end(self):
        rows = self._chained([row(i, f"e{i}", "a") for i in range(1, 4)])
        d = make_export(self.tmp, rows[:2])
        mpath = os.path.join(d, "manifest.json")
        m = json.load(open(mpath)); m["hash_chain"] = {"rows": 3, "last_row_hash": rows[2]["row_hash"]}
        json.dump(m, open(mpath, "w"))
        checks = dict(md.load_export(d)["checks"])
        self.assertIn("MISMATCH with manifest", checks["hash chain"])

    def test_withheld_notification_needs_review(self):
        rows = [row(1, "n", "notification", kind="auto_event", source="auto_event", detail=md.WITHHELD)]
        self.assertEqual(["notification details withheld while locked"],
                         [w for _, w in md.pending_review(md.current(rows))])
        rows.append(row(2, "l", "received_message", refers_to="n"))
        self.assertEqual([], md.pending_review(md.current(rows)))

    def test_system_rows_reach_the_scenario_sheet(self):
        rows = self.rows + [
            row(7, "v", "app_version_changed_since_last_seen", kind="system_event", source="system",
                detail="version 1 last seen, 2 now"),
            row(8, "w", "app_data_cleared", kind="system_event", source="system", detail="version 2 to 2"),
            row(9, "p", "profile_snapshot", kind="system_event", app_package=None, detail="profile id 2"),
            row(10, "x", "export", kind="export", app_package=None, detail="folder kdl_sc_phoneA_x"),
            row(11, "o", "app_version_changed_since_added", kind="system_event", source="system", detail="old name"),
        ]
        d = make_export(self.tmp, rows)
        out = os.path.join(self.tmp, "doc.xlsx")
        md.build([md.load_export(d)], out)
        listed = [str(v) for _, v in load_workbook(out)["Scenario"].iter_rows(values_only=True) if v]
        for action in ("app_version_changed_since_last_seen", "app_data_cleared", "profile_snapshot", "export",
                       "app_version_changed_since_added"):
            self.assertTrue(any(v.startswith(action + " ") or v.startswith(action + ":") for v in listed), action)

    def _run_main(self, rows, *extra):
        import contextlib
        import io
        tmp = tempfile.mkdtemp(dir=self.tmp)  # one export folder per call
        d = make_export(tmp, rows)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = md.main([d, "-o", os.path.join(tmp, "doc.xlsx"), *extra])
        return code, buf.getvalue()

    def test_missing_noted_hash_warns_and_fails(self):
        rows = self._chained([row(i, f"e{i}", "a") for i in range(1, 4)])
        code, out = self._run_main(rows, "--expect-hash", rows[2]["row_hash"])
        self.assertEqual(0, code)
        self.assertNotIn("WARNING", out)
        # The noted row was cut off the end; the rest of the export is consistent.
        cut = rows[:2]
        code, out = self._run_main(cut, "--expect-hash", rows[2]["row_hash"])
        self.assertEqual(1, code)
        self.assertIn("MISSING", out)
        self.assertIn("WARNING", out)

    def test_broken_chain_exits_with_one(self):
        rows = self._chained([row(i, f"e{i}", "a", content=f"c{i}") for i in range(1, 4)])
        rows[1]["content"] = "changed"
        code, out = self._run_main(rows)
        self.assertEqual(1, code)
        self.assertIn("BROKEN at seq 2", out)

    def test_device_lines_follow_the_platform(self):
        d = make_export(self.tmp, self.rows)
        mpath = os.path.join(d, "manifest.json")
        out = os.path.join(self.tmp, "doc.xlsx")

        def scenario(profile):
            m = json.load(open(mpath)); m["profiles"] = [profile]
            json.dump(m, open(mpath, "w"))
            md.build([md.load_export(d)], out)
            return {r[0]: r[1] for r in load_workbook(out)["Scenario"].iter_rows(values_only=True) if r[0]}

        ios = scenario({"manufacturer": "Apple", "model": "iPhone14,5", "release": "26.6", "incremental": "23G71",
                        "kernel": "25.6.0", "sdk_int": None, "fingerprint": None})
        self.assertEqual("26.6, build 23G71", ios["iOS"])
        self.assertNotIn("Android", ios)
        self.assertNotIn("Fingerprint", ios)
        android = scenario({"manufacturer": "Google", "model": "Pixel 7a", "release": "17", "sdk_int": 37, "incremental": "1"})
        self.assertEqual("17 (SDK 37), build 1", android["Android"])
        self.assertNotIn("iOS", android)

    def test_sheet_names_are_valid_and_unique(self):
        used = set()
        self.assertEqual("a_b", md.sheet_name("a/b", used))
        self.assertEqual("a_b (2)", md.sheet_name("a:b", used))
        self.assertLessEqual(len(md.sheet_name("x" * 50, used)), 31)


if __name__ == "__main__":
    unittest.main()
