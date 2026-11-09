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

    def test_sheet_names_are_valid_and_unique(self):
        used = set()
        self.assertEqual("a_b", md.sheet_name("a/b", used))
        self.assertEqual("a_b (2)", md.sheet_name("a:b", used))
        self.assertLessEqual(len(md.sheet_name("x" * 50, used)), 31)


if __name__ == "__main__":
    unittest.main()
