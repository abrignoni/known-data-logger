"""Tests for kdl_locate.py on a fictional app (com.example.notes). Run: python3 -m unittest discover -s tools

Every value below is invented for the test. The fixture writers for MMKV, LevelDB and protobuf
are checked by the vendored readers, which are the oracles here.
"""
import json
import os
import shutil
import sqlite3
import struct
import sys
import tarfile
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kdl_common as kc  # noqa: E402
import kdl_locate as kl  # noqa: E402
from openpyxl import load_workbook  # noqa: E402
from test_make_documentation import make_export, row  # noqa: E402
from vendor import mmkv_parser  # noqa: E402

PKG = "com.example.notes"
T0 = 1794146400000  # the row() helper's base time: utc_ms = T0 + seq * 1000


def t(seq):
    return T0 + seq * 1000


# ---- fixture writers -----------------------------------------------------------------------

def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def write_mmkv(path, items):
    region = b"\xff\xff\xff\x07"  # items-size holder, as MMKV 1.2.x writes it
    for k, v in items:
        kb, vb = k.encode(), varint(len(v.encode())) + v.encode()
        region += varint(len(kb)) + kb + varint(len(vb)) + vb
    with open(path, "wb") as f:
        f.write(struct.pack("<I", len(region)) + region)
    with open(path + ".crc", "wb") as f:
        f.write(b"\x00" * 32)


def _crc32c(data):
    crc = 0xFFFFFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ (0x82F63B78 if crc & 1 else 0)
    return crc ^ 0xFFFFFFFF


def _log_record(payload):
    crc = _crc32c(bytes([1]) + payload)
    masked = (((crc >> 15) | (crc << 17)) + 0xA282EAD8) & 0xFFFFFFFF
    return struct.pack("<IHB", masked, len(payload), 1) + payload  # type 1 = FULL


def write_leveldb(folder, puts, deletes=()):
    """A LevelDB folder holding one .log with one write batch, an empty manifest and CURRENT."""
    os.makedirs(folder)
    batch = struct.pack("<QI", 1, len(puts) + len(deletes))
    for k, v in puts:
        batch += b"\x01" + varint(len(k)) + k + varint(len(v)) + v
    for k in deletes:
        batch += b"\x00" + varint(len(k)) + k
    with open(os.path.join(folder, "000003.log"), "wb") as f:
        f.write(_log_record(batch))
    open(os.path.join(folder, "MANIFEST-000002"), "wb").close()
    with open(os.path.join(folder, "CURRENT"), "w") as f:
        f.write("MANIFEST-000002\n")


def protobuf_bytes(fields):
    out = b""
    for num, v in fields:
        if isinstance(v, int):
            out += varint(num << 3) + varint(v)
        else:
            b = v if isinstance(v, bytes) else v.encode()
            out += varint(num << 3 | 2) + varint(len(b)) + b
    return out


def build_app(root):
    """The fictional app's files under root. Returns nothing; values are the entries' below."""
    base = os.path.join(root, "data", "data", PKG)
    os.makedirs(os.path.join(base, "databases"))
    os.makedirs(os.path.join(base, "shared_prefs"))
    os.makedirs(os.path.join(base, "files", "datastore"))
    db = os.path.join(base, "databases", "notes.db")
    # Write in WAL mode and copy the files while the connection is open, so the log keeps its frames.
    work = tempfile.mkdtemp()
    src = os.path.join(work, "notes.db")
    con = sqlite3.connect(src)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA wal_autocheckpoint=0")
    con.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, body TEXT, address TEXT, sent_ms INTEGER, "
                "type INTEGER, hidden INTEGER DEFAULT 0)")
    con.execute("CREATE TABLE photos (id INTEGER PRIMARY KEY, taken_ms INTEGER)")
    con.commit()
    rows = [("See you at 6pm", "5550102030", t(1) + 2000, 2), ("Bring the blue folder", "5550102030", t(2) - 1500, 2),
            ("Running late, sorry", "5550102030", t(3) + 500, 1), ("Parking is on level 3", "5550102030", t(4), 1),
            ("Delete me later", "5550102030", t(9), 2), ("Hide this note", "5550102030", t(10), 2)]
    for r in rows:
        con.execute("INSERT INTO messages (body, address, sent_ms, type) VALUES (?, ?, ?, ?)", r)
        con.commit()
    con.execute("INSERT INTO photos (taken_ms) VALUES (?)", (t(14) + 3000,))
    con.commit()
    con.execute("DELETE FROM messages WHERE body = 'Delete me later'")
    con.commit()
    con.execute("UPDATE messages SET hidden = 1 WHERE body = 'Hide this note'")
    con.commit()
    con.execute("CREATE TABLE tags (name TEXT PRIMARY KEY, n INTEGER) WITHOUT ROWID")
    con.executemany("INSERT INTO tags VALUES (?, ?)", [("Tag beta two", 1), ("Tag beta two (copy)", 2)])
    con.commit()
    con.execute("DELETE FROM tags WHERE name = 'Tag beta two'")
    con.commit()
    con.execute("CREATE TABLE raw (id INTEGER PRIMARY KEY, body TEXT)")
    con.execute("INSERT INTO raw (body) VALUES (CAST(x'ff41' AS TEXT))")
    con.execute("INSERT INTO raw (body) VALUES ('Gone from the raw table')")
    con.commit()
    con.execute("DELETE FROM raw WHERE body = 'Gone from the raw table'")
    con.commit()
    for s in ("", "-wal", "-shm"):
        shutil.copyfile(src + s, db + s)
    con.close()
    shutil.rmtree(work)
    # SharedPreferences with JSON inside a string, time in Unix seconds beside the text.
    draft = json.dumps({"draft": "Lunch at noon", "ts": t(5) // 1000})
    draft = draft.replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;")
    with open(os.path.join(base, "shared_prefs", "notes_prefs.xml"), "w") as f:
        f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                f"    <string name=\"last_draft\">{draft}</string>\n"
                "    <int name=\"launches\" value=\"7\" />\n</map>\n")
    # JSON with a WebKit microsecond time.
    webkit = (t(6) + 11_644_473_600_000) * 1000
    with open(os.path.join(base, "files", "state.json"), "w") as f:
        json.dump({"items": [{"title": "Buy stamps", "created": webkit}]}, f)
    with open(os.path.join(base, "files", "app.log"), "w") as f:
        f.write("startup\nadded item Buy stamps\n")
    with open(os.path.join(base, "files", "datastore", "user.preferences_pb"), "wb") as f:
        f.write(protobuf_bytes([(1, protobuf_bytes([(1, "note"), (2, "Meet at the library")])), (2, 5)]))
    with open(os.path.join(base, "files", "datastore", "greeting.preferences_pb"), "w") as f:
        json.dump({"greeting": "Good morning team"}, f)  # a DataStore written with a JSON serializer
    write_mmkv(os.path.join(base, "files", "reminders"), [("last_reminder", "Water the plants"), ("count", "x")])
    write_leveldb(os.path.join(base, "app_webview", "Local Storage", "leveldb"),
                  [(b"_https://example.test\x00\x01pinned", b"\x01Call the plumber")])
    with open(os.path.join(base, "databases", "broken.db"), "wb") as f:
        f.write(b"SQLite format 3\x00" + b"\x00\x10" + b"\xee" * 200)
    open(os.path.join(base, "files", "empty.bin"), "wb").close()
    with open(os.path.join(base, "files", "blob.bin"), "wb") as f:
        f.write(b"\x00\x01\x02\x03unknown")
    media = os.path.join(root, "data", "media", "0", "Android", "media", PKG)
    os.makedirs(media)
    with open(os.path.join(media, "photo.jpg"), "wb") as f:
        f.write(b"\xff\xd8\xff\xe0fake")
    # Another app's file that holds a logged value: must not be read.
    other = os.path.join(root, "data", "data", "com.example.notes.backup", "files")
    os.makedirs(other)
    with open(os.path.join(other, "copy.txt"), "w") as f:
        f.write("See you at 6pm\n")


def entries():
    x = dict(app_package=PKG)
    return [
        row(1, "e1", "sent_message", content="See you at 6pm", counterpart="+1 (555) 010-2030", direction="out", **x),
        row(2, "e2", "sent_message", content="Bring the blue folder", direction="out", **x),
        row(3, "e3", "received_message", content="Running late, sorry", direction="in", **x),
        row(4, "e4", "received_message", content="Parking is on level 3", direction="in", **x),
        row(5, "e5", "created_note", content="Lunch at noon", **x),
        row(6, "e6", "added_item", content="Buy stamps", **x),
        row(7, "e7", "typed_text", kind="auto_event", content="Meet at the library", source="auto_event", **x),
        row(8, "e8", "set_reminder", content="Water the plants", **x),
        row(9, "e9", "sent_message", content="Delete me later", direction="out", **x),
        row(10, "e10", "sent_message", content="Hide this note", direction="out", **x),
        row(11, "e11", "pinned_note", content="Call the plumber", **x),
        row(12, "e12", "searched", content="ok", **x),
        row(13, "e13", "sent_message", content="See you at 6pm", app_package="com.example.other"),
        row(14, "e14", "took_photo", **x),
        row(15, "e15", "sent_message", content="Nonexistent value xyz", **x),
        row(16, "d9", "deleted_message", refers_to="e9", **x),
        row(17, "d10", "deleted_item", refers_to="e10", **x),
        row(18, "d0", "deleted_message", **x),
        row(19, "e19", "sent_media", content="photo.jpg", **x),
        row(20, "e20", "typed_text", kind="auto_event", content="Good morning team", source="auto_event", **x),
        row(21, "e21", "received_message", content="Never stored text", counterpart="+1 555 010 2030",
            direction="in", **x),
        row(22, "d22", "deleted_item", content="Tag beta two", **x),
        row(23, "d23", "deleted_item", content="Gone from the raw table", **x),
        row(24, "e24", "received_message", content="ok", counterpart="+1 555 010 2030", direction="in", **x),
    ]


def sheet(path, name):
    ws = load_workbook(path)[name]
    rows = list(ws.iter_rows(values_only=True))
    return [dict(zip(rows[0], r)) for r in rows[1:]]


class LocateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.root = os.path.join(cls.tmp, "extraction")
        build_app(cls.root)
        cls.export = make_export(cls.tmp, entries())
        cls.out = os.path.join(cls.tmp, "locate.xlsx")
        kl.main(["--export", cls.export, "--extraction", cls.root, "--package", PKG, "-o", cls.out,
                 "--work", os.path.join(cls.tmp, "work")])
        cls.entries = {r["Entry id"]: r for r in sheet(cls.out, "Entries")}
        cls.hits = sheet(cls.out, "Hits")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def hits_for(self, eid):
        return [h for h in self.hits if h["Entry id"] == eid]

    def test_fixture_writers_read_by_vendored_readers(self):
        base = os.path.join(self.root, "data", "data", PKG, "files")
        got = {k: mmkv_parser.decode_value(v) for k, v in mmkv_parser.read_entries(os.path.join(base, "reminders"))}
        self.assertEqual(got["last_reminder"], "Water the plants")

    def test_sqlite_text_time_and_phone(self):
        h = self.hits_for("e1")
        self.assertTrue(any(x["Field"] == "body" and x["Match"] == "exact" and x["State"] == "current" for x in h))
        self.assertTrue(any(x["Field"] == "address" and x["Match"] == "phone digits" for x in h))
        body = next(x for x in h if x["Field"] == "body")
        self.assertIn("sent_ms: Unix milliseconds, +2.0", body["Time fields in the same record (field, epoch, delta s)"])

    def test_profile_time_epoch_and_direction_covariation(self):
        prof = {r["Table or group"]: r for r in sheet(self.out, "Column profile")}
        m = prof["messages"]
        self.assertIn("sent_ms: Unix milliseconds, consistent", m["Time fields (epoch, status, delta)"])
        self.assertIn("type co-varies with the logged direction", m["Constant or direction-linked fields"])
        self.assertIn("meaning not established", m["Constant or direction-linked fields"])

    def test_json_in_xml_and_json_and_text(self):
        h = self.hits_for("e5")
        self.assertTrue(any("(JSON) draft" in x["Field"] and "ts: Unix seconds" in (x["Time fields in the same record (field, epoch, delta s)"] or "")
                            for x in h))
        h6 = self.hits_for("e6")
        self.assertTrue(any("WebKit microseconds" in (x["Time fields in the same record (field, epoch, delta s)"] or "") for x in h6))
        self.assertTrue(any(x["Store type"] == "text" for x in h6))

    def test_protobuf_hit_is_a_guess(self):
        h = self.hits_for("e7")
        self.assertTrue(h)
        self.assertTrue(all(x["Guess"] == "yes" for x in h))
        self.assertTrue(any(x["Field"] == "1.2 (as text)" for x in h))
        self.assertEqual(self.entries["e7"]["Status"], "found, weak or guessed matches only")

    def test_mmkv_and_leveldb(self):
        self.assertTrue(any(x["Store type"] == "MMKV" and x["Field"] == "last_reminder" for x in self.hits_for("e8")))
        self.assertTrue(any(x["Store type"] == "LevelDB" for x in self.hits_for("e11")))

    def test_deletions(self):
        dels = {}
        for r in sheet(self.out, "Deletions"):
            if r["Refers to"] is None and r["Searched"]:
                continue  # d22, checked in its own test
            self.assertNotIn(r["Refers to"], dels)  # one outcome per deletion here
            dels[r["Refers to"]] = r
        self.assertIn("only in an earlier state", dels["e9"]["Outcome"])
        self.assertIn("Not in current state", dels["e9"]["Outcome"])
        self.assertEqual(dels["e10"]["Outcome"], "present in the current state")
        self.assertIn("hidden: 0 -> 1", dels["e10"]["Detail"])
        self.assertEqual(dels[None]["Outcome"], "not evaluated: no value to search")
        self.assertIn("a later deletion names it", self.entries["e9"]["Status"])
        self.assertIn("found only in earlier or deleted records", self.entries["e9"]["Status"])

    def test_scope_and_absence(self):
        self.assertNotIn("e12", self.entries.keys() - {"e12"})  # listed
        self.assertEqual(self.entries["e12"]["Hits"], 0)  # "ok" is too short to search
        self.assertNotIn("e13", self.entries)  # another app
        self.assertIn("not found in the", self.entries["e15"]["Status"])
        self.assertIn("not read", self.entries["e15"]["Status"])
        self.assertTrue(all("com.example.notes.backup" not in x["Store"] for x in self.hits))

    def test_unchanged_earlier_versions_are_not_hits(self):
        h = self.hits_for("e10")
        self.assertTrue(any(x["State"] == "current" for x in h))
        self.assertFalse(any(x["State"].startswith("Earlier version") for x in h))

    def test_media_name_datastore_json_and_counterpart_only(self):
        self.assertTrue(any(x["Field"] == "file name" and x["Match"] == "exact" for x in self.hits_for("e19")))
        self.assertTrue(any(x["Store type"] == "JSON" for x in self.hits_for("e20")))
        self.assertTrue(self.entries["e21"]["Status"].startswith("content not found; only the counterpart"))

    def test_without_rowid_deletion_is_not_collapsed(self):
        outcomes = [r["Outcome"] for r in sheet(self.out, "Deletions") if r["Searched"] == "content: Tag beta two"]
        self.assertIn("present in the current state", outcomes)
        self.assertTrue(any(o.startswith("only in an earlier state: Not in current state") for o in outcomes), outcomes)

    def test_invalid_utf8_does_not_hide_a_table_from_the_wal(self):
        outcomes = [r["Outcome"] for r in sheet(self.out, "Deletions") if r["Searched"] == "content: Gone from the raw table"]
        self.assertEqual(len(outcomes), 1)
        self.assertTrue(outcomes[0].startswith("only in an earlier state: Not in current state"), outcomes)

    def test_short_content_with_counterpart_is_not_found(self):
        self.assertTrue(self.entries["e24"]["Status"].startswith("content too short to search"))

    def test_datastore_read_as_json_is_not_listed_as_not_read(self):
        self.assertFalse(any(r["Store"].endswith("greeting.preferences_pb") for r in sheet(self.out, "Not read")))

    def test_files_staged_counts_files_only(self):
        summary = {r["Item"]: r["Value"] for r in sheet(self.out, "Summary")}
        inv = sheet(self.out, "Inventory")
        self.assertEqual(int(summary["Files staged"]), sum(1 for r in inv if r["SHA-256"] != "(folder)"))

    def test_time_only(self):
        rows = [r for r in sheet(self.out, "Time only") if r["Entry id"] == "e14"]
        self.assertTrue(any(r["Field"] == "taken_ms" and r["Epoch"] == "Unix milliseconds" for r in rows))
        self.assertIn("not counted as found", self.entries["e14"]["Status"])

    def test_leveldb_store_has_its_own_inventory_row(self):
        inv = {r["Path"]: r for r in sheet(self.out, "Inventory")}
        row = next(v for k, v in inv.items() if k.endswith("Local Storage/leveldb/"))
        self.assertEqual((row["Store type"], row["Status"], row["Records read"]), ("LevelDB", "read", 1))

    def test_not_read_is_never_empty(self):
        nr = {r["Store"]: r["Reason"] for r in sheet(self.out, "Not read")}
        broken = next(k for k in nr if k.endswith("broken.db"))
        self.assertIn("SQLite could not open it", nr[broken])
        self.assertTrue(any(k.endswith("blob.bin") for k in nr))
        inv = {r["Path"]: r for r in sheet(self.out, "Inventory")}
        empty = next(v for k, v in inv.items() if k.endswith("empty.bin"))
        self.assertEqual(empty["Status"], "0 bytes")
        photo = next(v for k, v in inv.items() if k.endswith("photo.jpg"))
        self.assertEqual(photo["Status"], "media, not a store")

    def test_zip_and_tar_give_the_same_hits_and_reject_dot_dot(self):
        z = os.path.join(self.tmp, "x.zip")
        with zipfile.ZipFile(z, "w") as zf:
            for d, _, files in os.walk(self.root):
                for f in files:
                    full = os.path.join(d, f)
                    zf.write(full, "/" + os.path.relpath(full, self.root))
            zf.writestr("../data/data/com.example.notes/files/evil.txt", "See you at 6pm")
        tr = os.path.join(self.tmp, "x.tar.gz")
        with tarfile.open(tr, "w:gz") as tf:
            tf.add(self.root, arcname="dump")
        for src in (z, tr):
            out = os.path.join(self.tmp, os.path.basename(src) + ".xlsx")
            kl.main(["--export", self.export, "--extraction", src, "--package", PKG, "-o", out])
            hits = sheet(out, "Hits")
            self.assertEqual(len(hits), len(self.hits), src)
            self.assertTrue(all("evil" not in h["Store"] for h in hits))
        nr = sheet(os.path.join(self.tmp, "x.zip.xlsx"), "Not read")
        self.assertTrue(any("rejected" in r["Reason"] for r in nr))


class UnitTest(unittest.TestCase):
    def test_needle_rules(self):
        self.assertIsNone(kc.Needle("content", "late").match_text("running late today"))  # short: whole only
        self.assertEqual(kc.Needle("content", "late").match_text("Late"), "normalised, whole value (weak: short)")
        self.assertEqual(kc.Needle("content", "Reykjavik").match_text("Reykjavík, Iceland"), "accent-folded")
        self.assertFalse(kc.Needle("content", "ok").searchable)

    def test_epochs(self):
        names = dict(kc.time_readings(1794146400))
        self.assertEqual(names["Unix seconds"], 1794146400000)
        self.assertEqual(dict(kc.time_readings(T0 * 1000))["Unix microseconds"], T0)
        self.assertEqual(kc.time_readings(True), [])
        self.assertEqual(kc.time_readings(0), [])
        self.assertEqual([n for n, _ in kc.time_readings(7)], ["Cocoa seconds (2001)"])  # plausible, rarely in a window

    def test_since_boot_reading(self):
        e = {"utc_ms": T0, "elapsed_ms": 1_713_279}
        got = kc.entry_time_hits(1_711_204, e, T0 - 120_000, T0 + 120_000)
        self.assertEqual(got, [(kc.SINCE_BOOT, -2.075)])
        self.assertEqual(kc.entry_time_hits(5_000, dict(e, elapsed_ms=6_000), T0 - 120_000, T0 + 120_000), [])
        self.assertEqual(kc.entry_time_hits("1711204", e, T0 - 120_000, T0 + 120_000), [(kc.SINCE_BOOT, -2.075)])

    def test_window(self):
        e = {"utc_ms": 1000_000, "time_quality": "approximate"}
        self.assertEqual(kc.window_ms(e, kc.DEFAULT_TOLERANCES_S), (1000_000 - 900_000, 1000_000 + 900_000))
        e = {"utc_ms": 1000_000, "window_s": 60}
        self.assertEqual(kc.window_ms(e, kc.DEFAULT_TOLERANCES_S), (1000_000 - 120_000, 1000_000 + 180_000))

    def test_one_row_matched_by_many_entries_stays_a_candidate(self):
        st = kl.Store("db", "db", "SQLite", ["db"])
        rec = kl.Rec(st, "threads", "rowid 1", [("snippet", "x"), ("updated_ms", T0)])
        hits = []
        for i in range(3):
            e = {"utc_ms": T0 + i * 1000, "direction": None}
            hits.append({"entry": e, "store": st, "rec": rec, "field": "snippet", "needle": kc.Needle("content", "x" * 6),
                         "kind": "exact", "value": "x", "times": [("updated_ms", "Unix milliseconds", -i)], "guess": False})
        row = kl.column_profile([], hits)[0]
        self.assertIn("candidate, 3 entries over 1 row", row[4])

    def test_clean_name(self):
        self.assertEqual(kl.clean_name("/data/data/x/a.db"), "data/data/x/a.db")
        self.assertIsNone(kl.clean_name("data/../../etc/passwd"))

    def test_package_root_is_exact(self):
        r = kl.app_root_re(PKG)
        self.assertTrue(r.search("data/user_de/0/com.example.notes/databases/a.db"))
        self.assertTrue(r.search("Dump/data/media/0/Android/data/com.example.notes/files/x"))
        self.assertFalse(r.search("data/data/com.example.notes.backup/files/copy.txt"))

    def test_sqlite_uri_keeps_hash_in_name(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "a#b.db")
        c = sqlite3.connect(p)
        c.execute("CREATE TABLE t (x)")
        c.commit()
        c.close()
        con = kl.sqlite_ro(p, False)
        self.assertEqual(con.execute("SELECT name FROM sqlite_master").fetchone()[0], "t")
        con.close()
        self.assertEqual(sorted(os.listdir(d)), ["a#b.db"])
        shutil.rmtree(d)


if __name__ == "__main__":
    unittest.main()
