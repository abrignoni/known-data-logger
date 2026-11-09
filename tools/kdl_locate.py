#!/usr/bin/env python3
"""Find where an app stored the known data a KDL export describes.

Usage:
    python3 tools/kdl_locate.py --export EXPORT_DIR --extraction PATH --package PKG -o locate.xlsx
        [--device LABEL] [--work DIR] [--keep-staged]

EXPORT_DIR is a kdl_<scenario>_<device>_<time> folder. PATH is an extraction of the same phone:
a folder, a zip or a tar (.tar, .tar.gz). Give --device when several exports are passed.

The tool stages every file the app owns (data/data/PKG, data/user*/N/PKG, Android/data|media|obb/PKG,
and media MediaStore lists as owned by PKG), opens each store with its own reader, and looks for
each logged value and time. It writes an .xlsx for a person building a parser. It does not write
LEAPP artifacts, and a value it does not find may still be on the phone: see the Not read sheet.

Readers: SQLite with sqlite3 plus tools/vendor/sqlite_wal.py for earlier WAL states; XML and JSON
with the standard library; MMKV with tools/vendor/mmkv_parser.py; LevelDB and IndexedDB with
tools/vendor/ccl; protobuf with google.protobuf's unknown-field reader (schema-less, every decode
a guess). Requires openpyxl; protobuf is optional.
"""
import argparse
import collections
import hashlib
import json
import os
import re
import shutil
import sqlite3
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kdl_common as kc  # noqa: E402
import make_documentation as md  # noqa: E402
from openpyxl import Workbook  # noqa: E402
from vendor import mmkv_parser, sqlite_wal  # noqa: E402

try:
    from google.protobuf import empty_pb2
    from google.protobuf.message import DecodeError
    from google.protobuf.unknown_fields import UnknownFieldSet
    PROTOBUF = True
except ImportError:  # pragma: no cover - depends on the environment
    PROTOBUF = False

try:
    from vendor.ccl import ccl_chromium_indexeddb, ccl_leveldb
    CCL_ERROR = None
except Exception as ex:  # pragma: no cover - depends on the environment
    CCL_ERROR = f"{type(ex).__name__}: {ex}"

MAX_TEXT_BYTES = 64 * 1024 * 1024
MAX_EXCERPT = 300
MAX_TIME_ONLY = 50

SQLITE_MAGIC = b"SQLite format 3\x00"
MAGIC = [  # (prefix, offset, label, is media)
    (b"\x89PNG", 0, "PNG image", True), (b"\xff\xd8\xff", 0, "JPEG image", True), (b"GIF8", 0, "GIF image", True),
    (b"ftyp", 4, "ISO media (MP4/HEIF/3GP)", True), (b"OggS", 0, "Ogg media", True), (b"ID3", 0, "MP3 audio", True),
    (b"RIFF", 0, "RIFF (WAV/WEBP/AVI)", True), (b"ABX\x00", 0, "Android binary XML", False),
    (b"PK\x03\x04", 0, "zip archive", False), (b"%PDF", 0, "PDF", False), (b"\x1f\x8b", 0, "gzip", False),
    (b"dex\n", 0, "Dalvik executable", False), (b"\x7fELF", 0, "ELF binary", False),
]
SIDE = ("-wal", "-journal", "-shm")


# ---- extraction access ---------------------------------------------------------------------

def clean_name(name):
    """Member name as a relative POSIX path, or None when it must not be joined to a folder."""
    n = name.replace("\\", "/")
    parts = [p for p in n.split("/") if p not in ("", ".")]
    if not parts or ".." in parts:
        return None
    return "/".join(parts)


class Extraction:
    """Read-only access to a folder, zip or tar. Members are visited in sorted name order."""

    def __init__(self, path):
        self.path = path
        self.rejected = []
        if os.path.isdir(path):
            self.kind = "folder"
        elif zipfile.is_zipfile(path):
            self.kind = "zip"
        elif tarfile.is_tarfile(path):
            self.kind = "tar"
        else:
            raise SystemExit(f"{path}: not a folder, zip or tar")

    def stage(self, wanted, dest):
        """Copies every regular-file member whose clean name wanted() accepts into dest.
        Returns {clean name: staged path}. One pass over the extraction."""
        out = {}

        def target(name):
            p = os.path.join(dest, *name.split("/"))
            assert os.path.abspath(p).startswith(os.path.abspath(dest) + os.sep)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            return p

        if self.kind == "folder":
            root = os.path.abspath(self.path)
            for d, dirs, files in os.walk(root):
                dirs.sort()
                for f in sorted(files):
                    full = os.path.join(d, f)
                    name = os.path.relpath(full, root).replace(os.sep, "/")
                    if os.path.islink(full) or not os.path.isfile(full) or not wanted(name):
                        continue
                    p = target(name)
                    shutil.copyfile(full, p)
                    out[name] = p
        elif self.kind == "zip":
            with zipfile.ZipFile(self.path) as z:
                for info in sorted(z.infolist(), key=lambda i: i.filename):
                    if info.is_dir():
                        continue
                    name = clean_name(info.filename)
                    if name is None:
                        self.rejected.append(info.filename)
                        continue
                    if wanted(name):
                        p = target(name)
                        with z.open(info) as src, open(p, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        out[name] = p
        else:
            with tarfile.open(self.path, "r:*") as t:
                for m in t:  # one sequential pass, so a compressed tar is decompressed once
                    if not m.isfile():
                        continue
                    name = clean_name(m.name)
                    if name is None:
                        self.rejected.append(m.name)
                        continue
                    if wanted(name):
                        p = target(name)
                        with t.extractfile(m) as src, open(p, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                        out[name] = p
        return out


def app_root_re(package):
    p = re.escape(package)
    return re.compile(
        r"(?:^|/)(data/data/" + p + r"|data/user(?:_de)?/\d+/" + p + r"|Android/(?:data|media|obb)/" + p + r")/")


MEDIASTORE_RE = re.compile(
    r"(?:^|/)data/(?:data|user/\d+)/com\.(?:google\.)?android\.providers\.media\.module/databases/external\.db(?:-wal|-journal)?$")


def sqlite_ro(path, apply_wal):
    """Read-only connection to a staged copy. immutable=1 when the WAL is not to be applied,
    which also leaves a hot journal unapplied. Path.as_uri() keeps '#' and '?' in names intact."""
    uri = Path(path).as_uri() + ("?mode=ro" if apply_wal else "?mode=ro&immutable=1")
    return sqlite3.connect(uri, uri=True)


def has_side(path, suffix):
    p = path + suffix
    return os.path.isfile(p) and os.path.getsize(p) > 0


def mediastore_owned(staged, package):
    """(paths owned by package per MediaStore, note). Columns are checked before use."""
    dbs = sorted(p for n, p in staged.items() if n.endswith("external.db"))
    if not dbs:
        return [], "MediaStore external.db not in the extraction: media written by the app not listed"
    found, notes = [], []
    for db in dbs:
        try:
            con = sqlite_ro(db, has_side(db, "-wal"))
            cols = {r[1] for r in con.execute("PRAGMA table_info(files)")}
            if not {"_data", "owner_package_name"} <= cols:
                notes.append(f"{db}: table files has no _data/owner_package_name column; media not listed")
                continue
            found += [r[0] for r in con.execute(
                "SELECT _data FROM files WHERE owner_package_name = ? AND _data IS NOT NULL ORDER BY _data", (package,))]
            con.close()
        except sqlite3.Error as ex:
            notes.append(f"MediaStore not read: {ex}")
    return found, "; ".join(notes) or f"MediaStore lists {len(found)} file(s) owned by the app"


# ---- records -------------------------------------------------------------------------------

class Rec:
    """One row, key, line or object read from a store. fields: [(name, value)]."""
    __slots__ = ("store", "container", "where", "fields", "state", "guess")

    def __init__(self, store, container, where, fields, state="current", guess=False):
        self.store, self.container, self.where, self.fields, self.state, self.guess = (
            store, container, where, fields, state, guess)


def excerpt(v):
    if isinstance(v, bytes):
        try:
            v = v.decode("utf-8")
        except UnicodeDecodeError:
            return f"<{len(v)} bytes> " + v[:48].hex()
    s = str(v)
    return s if len(s) <= MAX_EXCERPT else s[:MAX_EXCERPT] + "..."


class Store:
    def __init__(self, name, path, kind, members):
        self.name, self.path, self.kind, self.members = name, path, kind, members
        self.status = "read"
        self.reason = ""
        self.records = []
        self.notes = []
        self.deletion_context = ""
        self.tables = {}

    def fail(self, reason):
        self.status, self.reason = "not read", reason
        self.records = []


def read_sqlite(st):
    wal = has_side(st.path, "-wal")
    journal = has_side(st.path, "-journal")
    try:
        con = sqlite_ro(st.path, wal)
        con.text_factory = lambda b: b.decode("utf-8", "replace")
        objects = con.execute("SELECT type, name, sql FROM sqlite_master WHERE type IN ('table')").fetchall()
        st.notes.append(f"journal mode on disk: {'WAL' if wal else 'rollback journal or none'}")
        free = con.execute("PRAGMA freelist_count").fetchone()[0]
        pages = con.execute("PRAGMA page_count").fetchone()[0]
    except sqlite3.Error as ex:
        st.fail(f"SQLite could not open it: {ex}")
        if "con" in locals():
            con.close()
        return
    virtual = [n for _, n, s in objects if s and s.upper().startswith("CREATE VIRTUAL TABLE")]
    unread = [f"{free} freelist page(s) of {pages}", "freeblocks and unallocated space inside pages"]
    if journal:
        unread.append(f"hot rollback journal ({os.path.getsize(st.path + '-journal')} bytes) not applied")
    st.deletion_context = "not read: " + ", ".join(unread)
    queries = []
    for _, table, sql in objects:
        if table.startswith("sqlite_"):
            continue
        shadow = next((v for v in virtual if table.startswith(v + "_")), None)
        label = table + (f" (shadow table of virtual table {v})" if (v := shadow) else "")
        cols, rows, rowid = _select_all(con, table)
        if cols is None:
            st.notes.append(f"table {table} not read: {rows}")
            continue
        st.tables[table] = {"columns": cols, "rows": len(rows), "label": label}
        for r in rows:
            st.records.append(Rec(st, label, row_key(r, rowid), list(zip(cols, r[1:] if rowid else r))))
        queries.append((table, label, cols, rowid))
    con.close()
    if wal:
        _earlier_rows(st, queries)


def row_key(values, rowid):
    """'rowid N', or for a table without a usable rowid a key made of the row's own values,
    so two different rows never share a key."""
    if rowid:
        return f"rowid {values[0]}"
    return f"row {hashlib.sha1(repr(tuple(values)).encode()).hexdigest()[:10]} (no rowid; keyed by its values)"


def _select_all(con, table):
    q = '"' + table.replace('"', '""') + '"'
    try:
        cur = con.execute(f"SELECT rowid AS kdl_rowid, * FROM {q}")
        cols = [d[0] for d in cur.description][1:]
        # A column named rowid, oid or _rowid_ hides the real rowid behind that name.
        if not {c.lower() for c in cols} & {"rowid", "oid", "_rowid_"}:
            return cols, cur.fetchall(), True
    except sqlite3.Error:
        pass
    try:
        cur = con.execute(f"SELECT * FROM {q}")
        return [d[0] for d in cur.description], cur.fetchall(), False
    except sqlite3.Error as ex:
        return None, str(ex), False


def _earlier_rows(st, queries):
    """Rows the other states of the write-ahead log hold and the current state does not.

    The same comparison as sqlite_wal.rows_not_in_current (rows compare by every value; with a
    rowid, a row whose rowid the current state still holds is an earlier version), run for every
    table inside one pass over the states, so the database and its log are copied once per state
    rather than once per table and state."""
    include = (sqlite_wal.KIND_COMMITTED, sqlite_wal.KIND_EARLIER, sqlite_wal.KIND_UNCOMMITTED)
    current, keys, found, broken = {}, {}, {t: {} for t, _, _, _ in queries}, {}
    try:
        with sqlite_wal.WalStates(st.path, include=include) as w:
            first = True
            for state in w.states():
                # Decode text as the current-state reader does, so a value that is not valid UTF-8
                # cannot stop a table from being compared.
                state.connection.text_factory = lambda b: b.decode("utf-8", "replace")
                for table, _, _, rowid in queries:
                    if table in broken:
                        continue
                    q = '"' + table.replace('"', '""') + '"'
                    try:
                        rows = state.connection.execute(
                            f"SELECT rowid AS kdl_rowid, * FROM {q}" if rowid else f"SELECT * FROM {q}").fetchall()
                    except sqlite3.Error as ex:
                        if first:
                            broken[table] = f"{type(ex).__name__}: {ex}"
                        continue  # a state where the table or a column does not exist yet
                    if first:
                        current[table] = set(rows)
                        keys[table] = {r[0] for r in rows} if rowid else set()
                        continue
                    for r in rows:
                        if r in current[table]:
                            continue
                        seen = found[table].setdefault(r, {"committed": None, "earlier": None, "uncommitted": False})
                        if state.kind == sqlite_wal.KIND_UNCOMMITTED:
                            seen["uncommitted"] = True
                            continue
                        slot = "committed" if state.kind == sqlite_wal.KIND_COMMITTED else "earlier"
                        if seen[slot] is None:
                            seen[slot] = [state.number, state.number]
                        else:
                            seen[slot][0] = state.number
                first = False
            st.notes.append(w.summary())
    except Exception as ex:  # noqa: BLE001 - reported, never hidden
        st.notes.append(f"earlier write-ahead log states not read: {type(ex).__name__}: {ex}")
        return
    for table, label, cols, rowid in queries:
        if table in broken:
            st.notes.append(f"earlier write-ahead log states of {table} not read: the current state of the log "
                            f"gave {broken[table]}")
        for r, seen in found[table].items():
            committed, earlier = seen["committed"], seen["earlier"]
            if committed is None and earlier is None:
                status = sqlite_wal.UNCOMMITTED_CHANGE
            elif rowid and r[0] in keys.get(table, ()):
                status = sqlite_wal.EARLIER_VERSION
            else:
                status = sqlite_wal.NOT_IN_CURRENT
            parts = []
            if committed:
                parts.append(f"committed states {committed[0]}-{committed[1]}")
            if earlier:
                parts.append(f"previous log generation commits {earlier[0]}-{earlier[1]}")
            if seen["uncommitted"]:
                parts.append("uncommitted frames")
            st.records.append(Rec(st, label, row_key(r, rowid), list(zip(cols, r[1:] if rowid else r)),
                                  state=f"{status} ({', '.join(parts)})"))


def _strings_from_json_text(name, text, out):
    t = text.strip()
    if t[:1] in "{[" and len(t) > 1:
        try:
            _flatten(json.loads(t), name, out)
            return True
        except ValueError:
            return False
    return False


def _flatten(obj, path, out):
    if isinstance(obj, dict):
        for k, v in obj.items():
            _flatten(v, f"{path}.{k}" if path else str(k), out)
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            _flatten(v, f"{path}[{i}]", out)
    else:
        out.append((path, obj))


def read_xml(st):
    try:
        root = ET.parse(st.path).getroot()
    except ET.ParseError as ex:
        st.fail(f"XML parser refused it: {ex}")
        return
    if root.tag == "map":  # Android SharedPreferences
        st.kind = "XML (SharedPreferences)"
        for el in root:
            key = el.get("name", "")
            if el.tag == "set":
                fields = [(f"{key}[{i}]", s.text or "") for i, s in enumerate(el)]
            elif el.tag == "string":
                fields = [(key, el.text or "")]
                inner = []
                if _strings_from_json_text("", el.text or "", inner):
                    fields += [(f"{key} (JSON) {p}", v) for p, v in inner]
            else:
                v = el.get("value")
                if el.tag in ("int", "long"):
                    try:
                        v = int(v)
                    except (TypeError, ValueError):
                        pass
                elif el.tag == "float":
                    try:
                        v = float(v)
                    except (TypeError, ValueError):
                        pass
                fields = [(f"{key} ({el.tag})", v)]
            st.records.append(Rec(st, "", f"key {key}", fields))
        return
    for el in root.iter():
        fields = [(f"{el.tag}@{k}", v) for k, v in el.attrib.items()]
        if el.text and el.text.strip():
            fields.append((el.tag, el.text.strip()))
        if fields:
            st.records.append(Rec(st, "", f"element {el.tag}", fields))


def read_json(st, text):
    leaves = []
    _flatten(json.loads(text), "", leaves)
    groups = collections.OrderedDict()
    for path, v in leaves:
        parent = re.sub(r"(\.[^.\[]+|\[\d+\])$", "", path)
        groups.setdefault(parent, []).append((path, v))
    for parent, fields in groups.items():
        st.records.append(Rec(st, "", f"object {parent or '(root)'}", fields))


def read_text(st, text):
    for i, line in enumerate(text.splitlines(), 1):
        if line.strip():
            st.records.append(Rec(st, "", f"line {i}", [("line", line)]))


def read_mmkv(st):
    try:
        entries = mmkv_parser.read_entries(st.path)
    except mmkv_parser.MMKVError as ex:
        st.fail(f"MMKV reader refused it (possibly encrypted, no key): {ex}")
        return
    if not entries:
        st.fail("MMKV reader returned no entries: an empty, cleared or encrypted store; not read as empty")
        return
    seen = collections.Counter(k for k, _ in entries)
    count = collections.Counter()
    for k, container in entries:
        count[k] += 1
        state = "current" if count[k] == seen[k] else "superseded write (later entry for this key exists)"
        key = k.decode("utf-8", "replace") if isinstance(k, bytes) else str(k)
        st.records.append(Rec(st, "", f"key {key} (write {count[k]} of {seen[k]})",
                              [(key, mmkv_parser.decode_value(container))], state=state))


def read_leveldb(st):
    if CCL_ERROR:
        st.fail(f"LevelDB reader not importable: {CCL_ERROR}")
        return
    try:
        with ccl_leveldb.RawLevelDb(st.path) as db:
            raw = list(db.iterate_records_raw())
            damage = sum(len(getattr(f, "damage", None) or []) for f in db._files)
    except Exception as ex:  # noqa: BLE001
        st.fail(f"LevelDB reader failed: {type(ex).__name__}: {ex}")
        return
    # The newest write of a key is its current value; older writes stay in the files until compaction.
    newest = {}
    for r in raw:
        if r.user_key not in newest or r.seq > newest[r.user_key]:
            newest[r.user_key] = r.seq
    for r in raw:
        if r.seq != newest[r.user_key]:
            state = "LevelDB earlier write of this key (superseded)"
        elif r.state == ccl_leveldb.KeyState.Live:
            state = "current"
        else:
            state = f"LevelDB {r.state.name} record (newest write of this key)"
        st.records.append(Rec(st, os.path.basename(str(r.origin_file)), f"seq {r.seq}",
                              [("key", r.user_key), ("value", r.value)], state=state))
    if damage:
        st.notes.append(f"{damage} damaged range(s) the LevelDB reader skipped: not read")


def read_indexeddb(st):
    if CCL_ERROR:
        st.fail(f"IndexedDB reader not importable: {CCL_ERROR}")
        return
    skipped = []
    try:
        db = ccl_chromium_indexeddb.IndexedDb(st.path)
    except Exception as ex:  # noqa: BLE001
        st.fail(f"IndexedDB reader failed: {type(ex).__name__}: {ex}")
        return
    try:
        for d in db.global_metadata.db_ids:
            top = db.get_database_metadata(d.dbid_no, ccl_chromium_indexeddb.DatabaseMetadataType.MaximumObjectStoreId) or 0
            for sid in range(1, top + 1):
                name = db.get_object_store_metadata(d.dbid_no, sid, ccl_chromium_indexeddb.ObjectStoreMetadataType.StoreName)
                if name is None:
                    continue
                for r in db.iterate_records(d.dbid_no, sid, bad_deserializer_data_handler=lambda k, b: skipped.append(k)):
                    fields = []
                    _flatten(r.value, "value", fields)
                    fields.insert(0, ("key", str(r.key.value)))
                    st.records.append(Rec(st, f"{d.origin} {d.name} / {name}", f"seq {r.ldb_seq_no}", fields,
                                          state="current" if r.is_live else "IndexedDB deleted record"))
    except Exception as ex:  # noqa: BLE001
        st.fail(f"IndexedDB reader failed: {type(ex).__name__}: {ex}")
    finally:
        db.close()
    if skipped:
        st.notes.append(f"{len(skipped)} IndexedDB value(s) the deserializer could not read")


def protobuf_fields(data, path="", depth=0):
    """Schema-less walk. Returns [(field path, value)] or raises DecodeError."""
    m = empty_pb2.Empty()
    m.ParseFromString(data)
    out = []
    for f in UnknownFieldSet(m):
        p = f"{path}.{f.field_number}" if path else str(f.field_number)
        if f.wire_type == 2:
            nested = None
            if depth < 8 and f.data:
                try:
                    nested = protobuf_fields(f.data, p, depth + 1)
                except DecodeError:
                    nested = None
            try:
                out.append((p + " (as text)", f.data.decode("utf-8")))
            except UnicodeDecodeError:
                if not nested:
                    out.append((p + " (bytes)", f.data))
            if nested:
                out += nested
        elif f.wire_type == 3:
            out.append((p + " (group)", "<group>"))
        else:
            out.append((p, f.data))
    return out


def read_protobuf(st, data):
    if not PROTOBUF:
        st.fail("protobuf package not installed")
        return
    try:
        fields = protobuf_fields(data)
    except DecodeError as ex:
        st.fail(f"not a protobuf message the wire-format reader accepts: {ex}")
        return
    st.kind = "protobuf (schema-less, guess)"
    st.records.append(Rec(st, "", "message", fields, guess=True))


def magic_label(head):
    for prefix, off, label, media in MAGIC:
        if head[off:off + len(prefix)] == prefix:
            return label, media
    return None, False


def classify_and_read(name, path, st):
    """Fills st for a single-file store."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        head = f.read(16)
    if size == 0:
        st.kind, st.status, st.reason = "empty file", "0 bytes", "nothing to read"
        return
    if head.startswith(SQLITE_MAGIC):
        st.kind = "SQLite"
        read_sqlite(st)
        return
    label, media = magic_label(head)
    if label:
        st.kind = label
        if media:
            st.status, st.reason = "media, not a store", "file name and path searched; content not read"
            st.records.append(Rec(st, "", "file", [("file name", os.path.basename(name)), ("path", name)]))
        else:
            st.fail(f"{label}: no reader in this tool")
        return
    base = os.path.basename(name)
    if (base.endswith((".pb", "_pb", ".proto", ".protobuf", ".preferences_pb")) or "/datastore/" in name) and size <= MAX_TEXT_BYTES:
        with open(path, "rb") as f:
            read_protobuf(st, f.read())
        if st.status == "read":
            return
        # A store under datastore/ can be JSON or text (a serializer other than protobuf).
        st.notes.append(f"read as JSON or text instead of protobuf ({st.reason})")
        st.status, st.reason, st.kind = "read", "", ""
    if size <= MAX_TEXT_BYTES:
        with open(path, "rb") as f:
            raw = f.read()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            text = None
        if text is not None and "\x00" not in text:
            s = text.lstrip("﻿ \t\r\n")
            if s.startswith("<"):
                st.kind = "XML"
                read_xml(st)
                if st.status == "read":
                    return
                st.status, st.reason, st.notes = "read", "", st.notes + [f"read as text: {st.reason}"]
            if s[:1] in "{[":
                try:
                    st.kind = "JSON"
                    read_json(st, s)
                    return
                except ValueError:
                    st.records = []
            st.kind = "text"
            read_text(st, text)
            return
    st.kind = "unknown"
    if size > MAX_TEXT_BYTES:
        st.fail(f"{size} bytes, not SQLite or a known format, and over the {MAX_TEXT_BYTES} byte limit for reading as text")
        return
    st.fail(f"no reader for this format (first bytes {head[:8].hex()}, extension {os.path.splitext(base)[1] or 'none'})")


def build_stores(staged, package, media_names):
    """Groups staged files into stores and reads them. Returns (stores, inventory rows)."""
    root_re = app_root_re(package)
    names = sorted(n for n in staged if root_re.search(n) or n in media_names)
    nameset = set(names)
    member_of = {}
    stores = []
    # LevelDB folders: CURRENT + MANIFEST-*
    dirs = collections.defaultdict(set)
    for n in names:
        dirs[n.rsplit("/", 1)[0]].add(n.rsplit("/", 1)[1])
    for d in sorted(dirs):
        files = dirs[d]
        if "CURRENT" in files and any(f.startswith("MANIFEST-") for f in files):
            members = [f"{d}/{f}" for f in sorted(files)]
            path = os.path.dirname(staged[members[0]])
            kind = "IndexedDB" if d.endswith(".indexeddb.leveldb") else "LevelDB"
            st = Store(d, path, kind, members)
            for m in members:
                member_of[m] = d
            (read_indexeddb if kind == "IndexedDB" else read_leveldb)(st)
            stores.append(st)
    for n in names:
        if n in member_of:
            continue
        side = next((s for s in SIDE if n.endswith(s)), None)
        if side and n[: -len(side)] in nameset:
            member_of[n] = n[: -len(side)]
            continue
        if n.endswith(".crc") and n[:-4] in nameset:
            member_of[n] = n[:-4]
            continue
        members = [n] + [n + s for s in SIDE if n + s in nameset]
        if n + ".crc" in nameset:
            st = Store(n, staged[n], "MMKV", members + [n + ".crc"])
            read_mmkv(st)
        elif side:
            st = Store(n, staged[n], f"SQLite {side[1:]} without its database", members)
            st.fail("sidecar whose database is not in the extraction")
        else:
            st = Store(n, staged[n], "", members)
            classify_and_read(n, staged[n], st)
        if n in media_names:
            st.notes.append("MediaStore lists this file as written by the app")
        stores.append(st)
    inventory = []
    by_name = {s.name: s for s in stores}
    for n in names:
        p = staged[n]
        digest, size = hashlib.sha256(), 0
        with open(p, "rb") as f:
            head = f.read(8).hex()
            f.seek(0)
            for chunk in iter(lambda: f.read(1 << 20), b""):
                digest.update(chunk)
                size += len(chunk)
        owner = member_of.get(n)
        st = by_name.get(owner or n)
        inventory.append([n, size, digest.hexdigest(), head, os.path.splitext(n)[1],
                          st.kind if st else "", owner or "", st.status if st and not owner else "",
                          (st.reason if st and not owner else ""), len(st.records) if st and not owner else None])
    for st in stores:
        if st.kind in ("LevelDB", "IndexedDB"):  # a folder store: one row for the store itself
            size = sum(r[1] for r in inventory if r[6] == st.name)
            inventory.append([st.name + "/", size, "(folder)", "", "", st.kind, "", st.status, st.reason, len(st.records)])
    inventory.sort(key=lambda r: r[0])
    return stores, inventory


# ---- search --------------------------------------------------------------------------------

def search(entries, stores, tol):
    """Returns hits: list of dicts, one per (entry, record, field, needle).

    An earlier WAL version of a row is reported only where the matched field differs from the
    row's current value: an unchanged value in every earlier state says nothing new."""
    hits = []
    now = {}
    for st in stores:
        for rec in st.records:
            if rec.state == "current":
                now[(id(st), rec.container, rec.where)] = dict(rec.fields)
    for e in entries:
        nds = [n for n in kc.needles(e) if n.searchable]
        start, end = kc.window_ms(e, tol)
        for st in stores:
            for rec in st.records:
                for fname, v in rec.fields:
                    for nd in nds:
                        kind = None
                        if isinstance(v, str):
                            kind = nd.match_text(v)
                        elif isinstance(v, bytes):
                            try:
                                kind = nd.match_text(v.decode("utf-8"))
                                kind = kind and kind + " (BLOB as UTF-8)"
                            except UnicodeDecodeError:
                                if nd.utf8 in v and not nd.whole_only:
                                    kind = "UTF-8 bytes inside a binary value (structure not decoded)"
                        elif isinstance(v, int):
                            kind = nd.match_number(v)
                        if not kind:
                            continue
                        if rec.state.startswith(sqlite_wal.EARLIER_VERSION):
                            cur = now.get((id(st), rec.container, rec.where))
                            if cur is not None and cur.get(fname) == v:
                                continue
                        times = []
                        for tf, tv in rec.fields:
                            for epoch, delta in kc.entry_time_hits(tv, e, start, end):
                                times.append((tf, epoch, delta))
                        hits.append({"entry": e, "store": st, "rec": rec, "field": fname, "needle": nd, "kind": kind,
                                     "value": v, "times": times, "guess": rec.guess or "binary" in kind})
    return hits


def time_only(entries, stores, tol):
    """For entries with no searchable text: every field whose time falls in the window."""
    out = []
    for e in entries:
        if any(n.searchable for n in kc.needles(e)):
            continue
        start, end = kc.window_ms(e, tol)
        found = []
        for st in stores:
            for rec in st.records:
                for fname, v in rec.fields:
                    for epoch, delta in kc.entry_time_hits(v, e, start, end):
                        found.append((st, rec, fname, epoch, delta, v))
        out.append((e, found))
    return out


def protobuf_paths_for_blob_hits(hits):
    """For text found inside a binary value: the schema-less field path, when it decodes."""
    if not PROTOBUF:
        return
    for h in hits:
        if isinstance(h["value"], bytes) and "binary" in h["kind"]:
            try:
                paths = [p for p, v in protobuf_fields(h["value"]) if isinstance(v, str) and h["needle"].value in v]
            except DecodeError:
                paths = []
            if paths:
                h["kind"] += f"; protobuf guess: field {', '.join(paths)}"


# ---- profile and deletions -----------------------------------------------------------------

def field_pattern(name):
    return re.sub(r"\[\d+\]", "[*]", re.sub(r"_\d+$", "_N", name))


def is_primary(h):
    # Content logged but too short to search still means a counterpart hit is not this entry's row.
    has_content = any(n.field == "content" for n in kc.needles(h["entry"]))
    return h["needle"].field == "content" or not has_content


def column_profile(entries, hits):
    """Per (store, container): content fields, time fields with epochs, and constant or
    direction-linked columns across the current rows that carry a content hit.

    Times, constants and direction come from primary hits only: the content when the entry
    logged content, otherwise the counterpart. A counterpart turns up on rows of other
    messages to the same person, which would lend them this entry's time and direction."""
    by_container = collections.OrderedDict()
    for h in hits:
        if h["needle"].field == "conversation_id":
            continue
        key = (h["store"].name, h["rec"].container)
        by_container.setdefault(key, []).append(h)
    rows = []
    for (store, container), hs in by_container.items():
        ents = {id(h["entry"]) for h in hs}
        content = collections.defaultdict(set)
        for h in hs:
            content[field_pattern(h["field"])].add(id(h["entry"]))
        prim = [h for h in hs if is_primary(h)]
        times = collections.defaultdict(list)
        for h in prim:
            for tf, epoch, delta in h["times"]:
                times[(field_pattern(tf), epoch)].append((id(h["entry"]), delta, (h["rec"].container, h["rec"].where)))
        time_lines = []
        for (tf, epoch), vals in sorted(times.items(), key=lambda kv: -len({e for e, _, _ in kv[1]})):
            n = len({e for e, _, _ in vals})
            nrows = len({r for _, _, r in vals})
            other = {ep for (f2, ep) in times if f2 == tf and ep != epoch}
            # One row matched by several entries (a conversation's last-update time) is one reading.
            status = "consistent" if n >= 3 and nrows >= 3 and not other else "candidate"
            deltas = [d for _, d, _ in vals]
            n_prim = len({id(h["entry"]) for h in prim})
            every = "yes" if n == n_prim else f"no ({n} of {n_prim})"
            time_lines.append(f"{tf}: {epoch}, {status}, {n} entr{'y' if n == 1 else 'ies'} over {nrows} "
                              f"row{'' if nrows == 1 else 's'}, delta {min(deltas):+.1f}..{max(deltas):+.1f} s, "
                              f"holds for every entry with a hit here: {every}")
        # constant and direction-linked fields, on current rows only, one row per record
        recs = {}
        for h in prim:
            if h["rec"].state == "current":
                recs[id(h["rec"])] = (h["rec"], h["entry"].get("direction"))
        notes = []
        empty = 0
        if len(recs) >= 2:
            fields = collections.OrderedDict()
            for rec, d in recs.values():
                for f, v in rec.fields:
                    fields.setdefault(f, []).append((v, d))
            for f, vs in fields.items():
                if f in content:
                    continue
                vals = {excerpt(v) for v, _ in vs}
                if len(vals) == 1 and len(vs) == len(recs):
                    if vs[0][0] in (None, "", 0, -1, 0.0, b""):
                        empty += 1
                    else:
                        notes.append(f"{f} = {next(iter(vals))} on all {len(vs)} rows")
                    continue
                ins = {excerpt(v) for v, d in vs if d == "in"}
                outs = {excerpt(v) for v, d in vs if d == "out"}
                n_in = sum(1 for _, d in vs if d == "in")
                n_out = sum(1 for _, d in vs if d == "out")
                if n_in >= 2 and n_out >= 2 and len(ins) == 1 and len(outs) == 1 and ins != outs:
                    notes.append(f"{f} co-varies with the logged direction across {n_in + n_out} rows "
                                 f"(in: {next(iter(ins))} x{n_in}, out: {next(iter(outs))} x{n_out}); meaning not established")
        if len(notes) > 12:
            notes = notes[:12] + [f"{len(notes) - 12} more constant field(s)"]
        if empty:
            notes.append(f"{empty} field(s) empty, 0 or -1 on every row (not listed)")
        rows.append([store, container or "", len(ents),
                     "; ".join(f"{f} ({len(s)} entr{'y' if len(s) == 1 else 'ies'})" for f, s in content.items()),
                     "\n".join(time_lines) or "no field in these rows reads as a time inside the tolerance",
                     "\n".join(notes) or ("fewer than 2 current rows with a hit" if len(recs) < 2 else "none"),
                     "yes" if any(h["guess"] for h in hs) else "no"])
    return rows


def deletion_rows(deletions, by_id, stores, tol):
    out = []
    unread = [s for s in stores if s.status == "not read"]
    for d in deletions:
        target = by_id.get(d.get("refers_to")) if d.get("refers_to") else None
        if target is not None and target["kind"] == "action":
            src, source = target, f"the deleted entry {target['entry_id']} ({target['action']})"
        else:
            src, source = d, "the deletion's own fields"
        nds = [n for n in kc.needles(src, ("content", "counterpart")) if n.searchable]
        base = [kc.entry_label(d), d["action"], d.get("refers_to") or "", source,
                "; ".join(f"{n.field}: {n.value}" for n in nds)]
        if not nds:
            out.append(base + ["not evaluated: no value to search", "", "", ""])
            continue
        hits = search([dict(src, utc_ms=d["utc_ms"])], stores, tol)
        if not hits:
            ctx = "; ".join(f"{s.name}: {s.deletion_context}" for s in stores if s.deletion_context)
            out.append(base + ["not found in any read state", "",
                               ctx, f"{len(unread)} store(s) not read (Not read sheet). Not found is not proof it is gone."])
            continue
        current = [h for h in hits if h["rec"].state == "current"]
        earlier = [h for h in hits if h["rec"].state != "current"]
        for h in current:
            changed = _changed_columns(h, stores)
            out.append(base + ["present in the current state", where(h),
                               changed or "no earlier version of this row was read", ""])
        now = {(h["store"].name, h["rec"].container, h["rec"].where) for h in current}
        for h in earlier:
            if (h["store"].name, h["rec"].container, h["rec"].where) in now:
                continue  # an earlier version of a row still present: shown as changed columns above
            out.append(base + [f"only in an earlier state: {h['rec'].state}", where(h), "", ""])
    return out


def _changed_columns(h, stores):
    rec = h["rec"]
    older = [r for r in h["store"].records if r.container == rec.container and r.where == rec.where and r is not rec
             and r.state.startswith(sqlite_wal.EARLIER_VERSION)]
    out = []
    now = dict(rec.fields)
    for r in older:
        diffs = [f"{f}: {excerpt(v)} -> {excerpt(now.get(f))}" for f, v in r.fields if now.get(f) != v]
        if diffs:
            out.append(f"changed since {r.state}: " + ", ".join(diffs) + " (candidate deletion flag)")
    return "; ".join(out)


def where(h):
    st, rec = h["store"], h["rec"]
    return f"{st.name} | {rec.container + ' | ' if rec.container else ''}{rec.where} | {h['field']}"


# ---- workbook ------------------------------------------------------------------------------

def write_workbook(out, summary, entry_rows, hit_rows, profile_rows, time_rows, del_rows, inventory, not_read):
    wb = Workbook()
    used = set()
    ws = wb.active
    ws.title = md.sheet_name("Summary", used)
    md.write_table(ws, ["Item", "Value"], summary)
    sheets = [
        ("Entries", ["Time", "Kind", "Action", "Content", "Counterpart", "Direction", "Time quality", "Status", "Hits",
                     "Stores with hits", "Entry id"], entry_rows),
        ("Hits", ["Entry time", "Action", "Searched field", "Searched value", "Match", "Store", "Store type", "Table or group",
                  "Record", "Field", "Value", "State", "Time fields in the same record (field, epoch, delta s)",
                  "Guess", "Entry id"], hit_rows),
        ("Column profile", ["Store", "Table or group", "Entries with hits", "Content fields",
                            "Time fields (epoch, status, delta)", "Constant or direction-linked fields", "Includes guesses"],
         profile_rows),
        ("Time only", ["Entry time", "Action", "Store", "Table or group", "Record", "Field", "Value", "Epoch", "Delta s",
                       "Fields matched for this entry", "Entry id"], time_rows),
        ("Deletions", ["Deletion time", "Action", "Refers to", "Values from", "Searched", "Outcome", "Where",
                       "Detail", "Caveat"], del_rows),
        ("Inventory", ["Path", "Bytes", "SHA-256", "First bytes", "Extension", "Store type", "Part of store",
                       "Status", "Reason", "Records read"], inventory),
        ("Not read", ["Store", "Store type", "Reason"], not_read),
    ]
    for name, headers, rows in sheets:
        md.write_table(wb.create_sheet(md.sheet_name(name, used)), headers, rows)
    wb.save(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--export", action="append", required=True, help="KDL export folder (repeatable)")
    ap.add_argument("--extraction", required=True, help="extraction of the same phone: folder, zip or tar")
    ap.add_argument("--package", required=True, help="package name of the app under test")
    ap.add_argument("--device", help="device label of the phone the extraction came from")
    ap.add_argument("-o", "--output", required=True, help="output .xlsx")
    ap.add_argument("--work", help="folder for staged copies (default: a temporary folder)")
    ap.add_argument("--keep-staged", action="store_true", help="keep the staged copies")
    a = ap.parse_args(argv)

    exports = kc.load_exports(a.export)
    if a.device:
        if a.device not in exports:
            raise SystemExit(f"no export carries the device label {a.device!r}: {sorted(exports)}")
        dev = a.device
    elif len(exports) == 1:
        dev = next(iter(exports))
    else:
        raise SystemExit(f"several exports given; name the extraction's phone with --device ({sorted(exports)})")
    export = exports[dev]
    tol, tol_note = kc.tolerances(export["manifest"])
    entries, deletions, skipped, by_id = kc.searched_entries(export, a.package)

    work = a.work or tempfile.mkdtemp(prefix="kdl-locate-")
    os.makedirs(work, exist_ok=True)
    stage_dir = tempfile.mkdtemp(prefix="staged-", dir=work)
    try:
        ex = Extraction(a.extraction)
        root_re = app_root_re(a.package)
        staged = ex.stage(lambda n: bool(root_re.search(n) or MEDIASTORE_RE.search(n)), stage_dir)
        media_paths, media_note = mediastore_owned(staged, a.package)
        media_names = set()
        if media_paths:
            rests = {re.sub(r"^/(?:storage/emulated|data/media)/\d+/|^/sdcard/", "", p) for p in media_paths}
            by_base = collections.defaultdict(set)
            for r in rests:
                by_base[r.rsplit("/", 1)[-1]].add(r)

            def is_media(n):
                return any(n.endswith("/" + r) for r in by_base.get(n.rsplit("/", 1)[-1], ()))
            more = ex.stage(lambda n: n not in staged and is_media(n), stage_dir)
            staged.update(more)
            media_names = {n for n in staged if is_media(n)}
            missing = [r for r in sorted(rests) if not any(n.endswith("/" + r) for n in media_names)]
            if missing:
                media_note += f"; {len(missing)} listed file(s) not in the extraction"
        stores, inventory = build_stores(staged, a.package, media_names)
        hits = search(entries, stores, tol)
        protobuf_paths_for_blob_hits(hits)
        tonly = time_only(entries, stores, tol)
        dels = deletion_rows(deletions, by_id, stores, tol)
    finally:
        if not a.keep_staged:
            shutil.rmtree(stage_dir, ignore_errors=True)

    not_read = [[s.name, s.kind, s.reason] for s in stores if s.status == "not read"]
    for st in stores:
        for n in st.notes:
            if "not read" in n and st.status == "read":
                not_read.append([st.name, st.kind, "partly read: " + n])
    for r in ex.rejected:
        not_read.append([r, "member name", "rejected: '..' in the member name, never joined to the staging folder"])
    n_unread = sum(1 for s in stores if s.status == "not read")
    n_partial = len(not_read) - n_unread - len(ex.rejected)

    hits_by_entry = collections.defaultdict(list)
    for h in hits:
        hits_by_entry[id(h["entry"])].append(h)
    entry_rows = []
    for e in entries:
        hs = hits_by_entry[id(e)]
        nds = kc.needles(e)
        if not any(n.searchable for n in nds):
            n_time = next((len(f) for en, f in tonly if en is e), 0)
            status = f"no text to search; {n_time} time-only field(s) (Time only sheet), not counted as found"
        elif hs:
            # With content logged, only a content hit counts: a counterpart turns up in contact
            # and participant tables whether or not the message itself was stored.
            has_content = any(n.field == "content" for n in nds)
            main = [h for h in hs if h["needle"].field == "content"] if has_content else hs
            strong = [h for h in main if not h["guess"] and "weak" not in h["kind"]]
            if has_content and not any(n.searchable for n in nds if n.field == "content"):
                status = (f"content too short to search; only the counterpart or thread label matched "
                          f"({n_unread} store(s) not read)")
            elif not main:
                status = (f"content not found; only the counterpart or thread label matched "
                          f"({n_unread} store(s) not read)")
            elif any(h["rec"].state == "current" for h in strong):
                status = "found"
            elif strong:
                status = "found only in earlier or deleted records (State column on the Hits sheet)"
            else:
                status = "found, weak or guessed matches only"
        else:
            status = (f"not found in the {sum(1 for s in stores if s.status == 'read')} store(s) read; "
                      f"{n_unread} store(s) not read")
        named = [d for d in deletions if d["utc_ms"] >= e["utc_ms"] and (
            d.get("refers_to") == e["entry_id"]
            or any(n.n == kc.norm(d.get("content") or "") for n in nds if n.field == "content"))]
        if named:
            status += "; a later deletion names it (Deletions sheet)"
        entry_rows.append([kc.entry_label(e), e["kind"], e["action"], e.get("content"), e.get("counterpart"),
                           e.get("direction"), e.get("time_quality"), status, len(hs),
                           "; ".join(sorted({h["store"].name for h in hs})), e["entry_id"]])
    hit_rows = []
    for h in hits:
        e = h["entry"]
        tl = "; ".join(f"{f}: {ep}, {d:+.1f}" for f, ep, d in h["times"])
        hit_rows.append([kc.entry_label(e), e["action"], h["needle"].field, h["needle"].value, h["kind"], h["store"].name,
                         h["store"].kind, h["rec"].container, h["rec"].where, h["field"], excerpt(h["value"]),
                         h["rec"].state, tl, "yes" if h["guess"] else "no", e["entry_id"]])
    time_rows = []
    for e, found in tonly:
        for st, rec, f, ep, d, v in found[:MAX_TIME_ONLY]:
            time_rows.append([kc.entry_label(e), e["action"], st.name, rec.container, rec.where, f, excerpt(v), ep, d,
                              len(found), e["entry_id"]])
    profile = column_profile(entries, hits)

    e_checks = "; ".join(f"{k}: {v}" for k, v in export["checks"])
    summary = [
        ("Generated (UTC)", datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")),
        ("Export", os.path.basename(os.path.normpath(export["folder"]))),
        ("Export checks", e_checks),
        ("Device", dev), ("Package", a.package), ("Extraction", a.extraction),
        ("Tolerances (s)", f"{tol} ({tol_note})"),
        ("Entries searched", f"{len(entries)} ({sum(1 for e in entries if e['kind'] == 'action')} labelled actions, "
                             f"{sum(1 for e in entries if e['kind'] != 'action')} typed or notification events)"),
        ("Entries found", sum(1 for r in entry_rows if r[7] == "found")),
        ("Deletions", len(deletions)),
        ("Current rows not searched", f"{len(skipped)} (other apps, no app, or events a label refers to)"),
        ("Files staged", sum(1 for r in inventory if r[2] != "(folder)")),
        ("Stores read", sum(1 for s in stores if s.status == "read")),
        ("Stores not read", f"{n_unread} (Not read sheet, which also lists {n_partial} note(s) on stores read only in "
                            f"part and {len(ex.rejected)} rejected member name(s))"),
        ("Media", media_note),
        ("Scope", "Only the stores read are searched. SQLite freelist pages, freeblocks, unallocated space and "
                  "unapplied rollback journals are not read. A value not found may still be on the phone."),
        ("Matching", f"Text shorter than {kc.MIN_NEEDLE} characters is not searched; single words shorter than "
                     f"{kc.MIN_CONTAINED} are matched as the whole value only (weak). Times are read in "
                     f"{', '.join(n for n, _ in kc.EPOCHS)}, and as {kc.SINCE_BOOT}, within the entry's tolerance, and only in records that "
                     "also hold a text hit; a time field is 'consistent' only when 3 or more entries agree on one epoch."),
        ("Guesses", "Protobuf decodes are schema-less: field numbers and types are guesses."),
    ]
    for name, result in export["checks"]:
        if "MISMATCH" in result or "BROKEN" in result:
            summary.insert(3, ("WARNING", f"{name}: {result}"))
    write_workbook(a.output, summary, entry_rows, hit_rows, profile, time_rows, dels, inventory, not_read)
    print(f"Wrote {a.output}: {len(entries)} entries, {len(hits)} hits, {len(stores)} stores "
          f"({len(not_read)} not read), {len(deletions)} deletion(s)")
    if a.keep_staged:
        print(f"Staged copies kept in {stage_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
