"""Tests for kdl_check.py against a fictional LEAPP output. Run: python3 -m unittest discover -s tools"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kdl_check as kk  # noqa: E402
from openpyxl import load_workbook  # noqa: E402
from test_make_documentation import make_export, row  # noqa: E402

PKG = "com.example.chat"
T0 = 1794146400000
ART = "aleapp:exampleChat"


def secs(seq, shift=0):
    return (T0 + seq * 1000) // 1000 + shift


def lava_output(folder, rows, leapp_name="ALEAPP"):
    """A LEAPP output folder in the layout lavafuncs writes: manifest plus one artifact table."""
    os.makedirs(folder)
    manifest = {
        "parser_info": {"leapp_name": leapp_name, "leapp_version": "test"},
        "lava_db_name": "_lava_artifacts.db",
        "artifacts": {"Chats": [{
            "artifact_key": "exampleChat", "name": "Example Chat", "tablename": "examplechat", "module": "exampleChat",
            "column_map": {"timestamp": "Timestamp", "message": "Message", "direction": "Direction", "peer": "Peer"},
            "object_columns": [{"name": "timestamp", "type": "datetime"}],
            "data_views": {"conversation": {"textColumn": "message", "timeColumn": "timestamp",
                                            "directionColumn": "direction", "directionSentValue": "Outgoing"}},
        }]},
    }
    with open(os.path.join(folder, "_lava_data.lava"), "w") as f:
        json.dump(manifest, f)
    con = sqlite3.connect(os.path.join(folder, "_lava_artifacts.db"))
    con.execute('CREATE TABLE "examplechat" ("timestamp" INTEGER, "message" TEXT, "direction" TEXT, "peer" TEXT)')
    con.executemany("INSERT INTO examplechat VALUES (?, ?, ?, ?)", rows)
    con.commit()
    con.close()


def export_for(tmp, label, rows):
    sub = os.path.join(tmp, label)
    os.makedirs(sub)
    d = make_export(sub, rows)
    p = os.path.join(d, "manifest.json")
    with open(p) as f:
        m = json.load(f)
    m["device_label"] = label
    with open(p, "w") as f:
        json.dump(m, f)
    return d


def sheet(path, name):
    rows = list(load_workbook(path)[name].iter_rows(values_only=True))
    return [dict(zip(rows[0], r)) for r in rows[1:]]


class CheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        x = dict(app_package=PKG)
        a_rows = [
            row(0, "s0", "capture_session_start", kind="system_event", app_package=None),
            row(1, "e1", "sent_message", content="See you at 6pm", direction="out", expected_artifacts=[ART], **x),
            row(2, "e2", "sent_message", content="Bring the blue folder", direction="out", expected_artifacts=[ART], **x),
            row(3, "e3", "received_message", content="Running late, sorry", direction="in", expected_artifacts=[ART], **x),
            row(4, "e4", "received_message", content="Parking is on level 3", direction="in", **x),
            row(5, "e5", "sent_message", content="Hello there friend", direction="out", **x),
            row(6, "e6", "typed_text", kind="auto_event", content="Typed but never sent", source="auto_event", **x),
            row(7, "e7", "sent_message", content="Not an artifact here", direction="out",
                expected_artifacts=["aleapp:missingArtifact"], app_package="com.example.other"),
            row(30, "s1", "capture_session_stop", kind="system_event", app_package=None),
        ]
        b_rows = [row(1, "b1", "received_message", content="See you at 6pm", direction="in",
                      expected_artifacts=[ART], **x)]
        cls.ea = export_for(cls.tmp, "phoneA", a_rows)
        cls.eb = export_for(cls.tmp, "phoneB", b_rows)
        cls.oa = os.path.join(cls.tmp, "outA")
        lava_output(cls.oa, [
            (secs(1, 1), "See you at 6pm", "Outgoing", "Sam"),
            (secs(2), "Bring the blue folder", "Outgoing", "Sam"),
            (secs(2), "Bring the blue folder", "Outgoing", "Sam"),
            (secs(3, 5 * 3600), "Running late, sorry", "Incoming", "Sam"),
            (secs(5), "Hello there friend", "Incoming", "Sam"),
            (secs(10), "Auto reply from the app", "Incoming", "Bot"),
            (secs(100000), "Long before or after", "Incoming", "Bot"),
        ])
        cls.ob = os.path.join(cls.tmp, "outB")
        lava_output(cls.ob, [(secs(1, 2), "See you at 6pm", "Incoming", "Alex")])
        cls.out = os.path.join(cls.tmp, "check.xlsx")
        kk.main(["--export", cls.ea, "--export", cls.eb, "--leapp-output", f"phoneA={cls.oa}",
                 "--leapp-output", f"phoneB={cls.ob}", "--artifact", "exampleChat", "--package", PKG, "-o", cls.out])
        cls.per = {(r["Device"], r["Entry id"]): r for r in sheet(cls.out, "Per entry")}

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def status(self, eid, dev="phoneA"):
        return self.per[(dev, eid)]["Status"]

    def test_statuses(self):
        self.assertEqual(self.status("e1"), "found")
        self.assertEqual(self.status("e2"), "ambiguous")
        self.assertEqual(self.status("e3"), "mismatched")
        self.assertIn("off by +5 h 00 min", self.per[("phoneA", "e3")]["Detail"])
        self.assertEqual(self.status("e4"), "missing")
        self.assertEqual(self.status("e5"), "mismatched")
        self.assertIn("contradicts the logged direction", self.per[("phoneA", "e5")]["Detail"])
        self.assertEqual(self.status("e7"), "missing")

    def test_raw_events_and_session_rows_are_not_graded(self):
        self.assertNotIn(("phoneA", "e6"), self.per)
        self.assertNotIn(("phoneA", "s0"), self.per)

    def test_coverage(self):
        cov = {(r["Device"], r["Artifact"]): r for r in sheet(self.out, "Coverage")}
        c = cov[("phoneA", ART)]
        self.assertEqual((c["Entries graded"], c["Found"], c["Ambiguous"], c["Mismatched"], c["Missing"]), (5, 1, 1, 2, 1))
        self.assertEqual(c["Coverage (found / graded)"], 0.2)
        self.assertIn("not in this output", cov[("phoneA", "aleapp:missingArtifact")]["Note"])

    def test_rows_not_matched_inside_the_session_only(self):
        rows = [r for r in sheet(self.out, "Rows not matched") if r["Device"] == "phoneA"]
        texts = " ".join(r["Row"] for r in rows)
        self.assertIn("Auto reply from the app", texts)
        self.assertNotIn("Long before or after", texts)
        self.assertNotIn("See you at 6pm", texts)

    def test_pairs(self):
        pairs = sheet(self.out, "Pairs")
        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0]["Result"], "both found")

    def test_other_core_is_not_this_artifact(self):
        o = os.path.join(self.tmp, "outI")
        lava_output(o, [(secs(1), "See you at 6pm", "Outgoing", "Sam")], leapp_name="iLEAPP")
        out = os.path.join(self.tmp, "i.xlsx")
        kk.main(["--export", self.ea, "--leapp-output", f"phoneA={o}", "-o", out])
        per = {r["Entry id"]: r for r in sheet(out, "Per entry")}
        self.assertEqual(per["e1"]["Status"], "not gradable")  # expected an aleapp artifact, this is iLEAPP output
        cov = sheet(out, "Coverage")
        self.assertEqual(cov[0]["Entries graded"], 0)
        self.assertIn("this output is ileapp", cov[0]["Note"])

    def test_grade_edge_cases(self):
        art = {"data_views": {"conversation": {"directionColumn": "direction", "directionSentValue": "Outgoing"}}}
        types = {"timestamp": "datetime"}
        tol = {"exact": 120, "before_after": 300, "approximate": 900}
        e = {"utc_ms": T0, "content": "See you at 6pm", "direction": "out"}
        rows = [(1, {"timestamp": T0 // 1000, "message": "See you at 6pm", "direction": None})]
        self.assertEqual(kk.grade(e, art, rows, types, tol)[0], "found, direction not stored")
        e2 = {"utc_ms": T0, "content": "Hola"}
        rows2 = [(1, {"timestamp": T0 // 1000, "message": "hola", "direction": "Outgoing"})]
        self.assertEqual(kk.grade(e2, art, rows2, types, tol)[0], "found, weak match")
        e4 = {"utc_ms": T0, "content": "Hola", "counterpart": "Sam Example"}
        rows4 = [(1, {"timestamp": T0 // 1000, "message": "hola", "peer": "Sam Example", "direction": "Incoming"})]
        self.assertEqual(kk.grade(e4, art, rows4, types, tol)[0], "found, weak match")
        e3 = {"utc_ms": T0}
        self.assertEqual(kk.grade(e3, art, [(1, {"message": "x"})], {}, tol)[0], "not gradable")
        self.assertEqual(kk.bucket("found, weak match"), "found with a caveat")

    def test_float_and_iso_datetimes(self):
        self.assertEqual(kk.datetime_ms(1794146401.5), 1794146401500)
        self.assertEqual(kk.datetime_ms("2026-11-08 14:00:01"), 1794146401000)
        self.assertIsNone(kk.datetime_ms("not a date"))
        self.assertIsNone(kk.offset_note(30))
        self.assertIn("-0 h 30 min", kk.offset_note(-1800.5))


if __name__ == "__main__":
    unittest.main()
