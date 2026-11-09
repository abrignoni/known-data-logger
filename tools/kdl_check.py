#!/usr/bin/env python3
"""Grade a finished LEAPP artifact against the known data in KDL exports.

Usage:
    python3 tools/kdl_check.py --export EXPORT_DIR [--export ...] --leapp-output [LABEL=]OUTPUT_DIR [...]
        [--artifact KEY ...] [--package PKG] -o check.xlsx

OUTPUT_DIR is a LEAPP output folder holding _lava_data.lava and _lava_artifacts.db. LABEL is the
device label of the phone that output was made from; it may be left out when there is one
export and one output.

Which entries an artifact is graded on: each labelled action whose expected_artifacts names
it (core:artifact_key), plus, for every --artifact given, every labelled action of --package.
Captured raw events are never graded: nothing promised a parser would show them.

How the LEAPP output is read (ALEAPP scripts/lavafuncs.py; the other cores share the layout):
_lava_data.lava lists artifacts by category, each with artifact_key (the artifact function's
name), tablename, column_map (column -> header), object_columns (column types) and data_views.
lava_db_name names the SQLite file. A 'datetime' column holds UTC epoch seconds: an integer in
ALEAPP, a float in cores that store total_seconds(); a value that was not a date is kept as text.
"""
import argparse
import collections
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kdl_common as kc  # noqa: E402
import make_documentation as md  # noqa: E402
from openpyxl import Workbook  # noqa: E402

MANIFEST_NAMES = ("_lava_data.lava", "_lava_data.json")
KIND_RANK = ["exact", "contained", "normalised, whole value", "normalised", "accent-folded",
             "normalised, whole value (weak: short)", "accent-folded, whole value (weak: short)",
             "phone digits", "phone digits (integer)"]


def rank(kind):
    return KIND_RANK.index(kind) if kind in KIND_RANK else len(KIND_RANK)


class LeappOutput:
    """One LEAPP output folder, read-only."""

    def __init__(self, folder):
        self.folder = folder
        path = next((os.path.join(folder, n) for n in MANIFEST_NAMES if os.path.isfile(os.path.join(folder, n))), None)
        if not path:
            raise SystemExit(f"{folder}: no _lava_data.lava")
        with open(path, encoding="utf-8") as f:
            self.manifest = json.load(f)
        info = self.manifest.get("parser_info") or {}
        self.core = (info.get("leapp_name") or "").lower()
        self.version = info.get("leapp_version")
        db = os.path.join(folder, self.manifest.get("lava_db_name") or "_lava_artifacts.db")
        self.con = sqlite3.connect(Path(db).as_uri() + "?mode=ro&immutable=1", uri=True)
        self.artifacts = {}
        for category, items in (self.manifest.get("artifacts") or {}).items():
            for a in items:
                a = dict(a, category=category)
                self.artifacts[a["artifact_key"]] = a

    def find(self, spec):
        """spec 'core:key' or 'key'. Returns the artifact dict or None."""
        core, _, key = spec.rpartition(":")
        if core and core.lower() != self.core:
            return None
        return self.artifacts.get(key)

    def rows(self, art):
        """[(rowid, {column: value})] and the column types."""
        types = {c["name"]: c["type"] for c in art.get("object_columns") or []}
        q = '"' + art["tablename"].replace('"', '""') + '"'
        try:
            cur = self.con.execute(f"SELECT rowid, * FROM {q}")
        except sqlite3.Error as ex:
            return None, types, str(ex)
        cols = [d[0] for d in cur.description][1:]
        return [(r[0], dict(zip(cols, r[1:]))) for r in cur], types, None


def datetime_ms(value):
    """A LAVA datetime cell as Unix ms, or None."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return value * 1000
    if isinstance(value, str):
        try:
            d = datetime.fromisoformat(value.strip())
        except ValueError:
            return None
        if d.tzinfo is None:
            d = d.replace(tzinfo=timezone.utc)  # as lavafuncs stores a naive ISO string
        return d.timestamp() * 1000
    return None


def bucket(status):
    """Coverage column for a status. Only a plain 'found' counts as found."""
    if status in ("found", "ambiguous", "mismatched", "missing", "not gradable"):
        return status
    return "found with a caveat" if status.startswith("found") else "ambiguous"


def offset_note(delta_s):
    """A delta within 2 s of a non-zero multiple of 15 minutes reads like a time zone slip."""
    q = round(delta_s / 900)
    if q and abs(delta_s - q * 900) <= 2:
        m = abs(q) * 15
        return f"off by {'-' if q < 0 else '+'}{m // 60} h {m % 60:02d} min, a time zone or local-time conversion?"
    return None


def grade(entry, art, rows, types, tol):
    """(status, row ids, detail, best row id, time delta s)."""
    views = (art.get("data_views") or {}).get("conversation") or {}
    dt_cols = [c for c, t in types.items() if t == "datetime"]
    if views.get("timeColumn") in dt_cols:  # the artifact's own event time first
        dt_cols.remove(views["timeColumn"])
        dt_cols.insert(0, views["timeColumn"])
    start, end = kc.window_ms(entry, tol)
    nds = [n for n in kc.needles(entry, ("content", "counterpart")) if n.searchable]
    cands = []
    for rid, row in rows:
        best = {}
        for col, v in row.items():
            if types.get(col) in ("datetime", "date", "media"):
                continue
            for n in nds:
                k = n.match_text(v) if isinstance(v, str) else n.match_number(v)
                if k and (n.field not in best or rank(k) < rank(best[n.field][1])):
                    best[n.field] = (col, k)
        times = [(c, datetime_ms(row.get(c))) for c in dt_cols]
        times = [(c, ms) for c, ms in times if ms is not None]
        cands.append((rid, row, best, times))
    if not nds:
        if not dt_cols:
            return "not gradable", [], "no text to search and the artifact has no datetime column", None, None
        hits = [(rid, c, ms) for rid, row, _, times in cands for c, ms in times if start <= ms <= end]
        if not hits:
            return "missing", [], "no text to search and no datetime inside the tolerance", None, None
        ids = sorted({h[0] for h in hits})
        status = "found (time only)" if len(ids) == 1 else "ambiguous (time only)"
        return status, ids, f"{len(ids)} row(s) with a datetime inside the tolerance; nothing else compared", ids[0], \
            round((hits[0][2] - entry["utc_ms"]) / 1000, 3)
    text_rows = [c for c in cands if "content" in c[2] or ("content" not in {n.field for n in nds} and c[2])]
    if not text_rows:
        return "missing", [], f"no row holds {'; '.join(n.field + ': ' + n.value for n in nds)}", None, None
    if not dt_cols:
        ids = [c[0] for c in text_rows]
        status = "found, time not checked" if len(ids) == 1 else "ambiguous, time not checked"
        return status, ids, "the artifact has no datetime column", ids[0], None
    timed = []
    for rid, row, best, times in text_rows:
        inside = [(c, ms) for c, ms in times if start <= ms <= end]
        if inside:
            score = sum(rank(k) for _, k in best.values()) - len(best) * 100  # more fields first, then strength
            timed.append((score, rid, row, best, inside))
    if not timed:
        nearest = min(((abs(ms - entry["utc_ms"]), rid, c, ms) for rid, _, _, times in text_rows for c, ms in times),
                      default=None)
        if nearest is None:
            return "mismatched", [c[0] for c in text_rows], "text matches, but the row's datetime is empty", None, None
        _, rid, c, ms = nearest
        delta = round((ms - entry["utc_ms"]) / 1000, 3)
        detail = f"text matches; nearest {c} is {delta:+.1f} s from the tap, outside the tolerance"
        note = offset_note(delta)
        return "mismatched", [x[0] for x in text_rows], detail + (f" ({note})" if note else ""), rid, delta
    timed.sort(key=lambda x: x[0])
    top = [t for t in timed if t[0] == timed[0][0]]
    _, rid, row, best, inside = timed[0]
    delta = round((inside[0][1] - entry["utc_ms"]) / 1000, 3)
    detail = "; ".join(f"{f} in {c} ({k})" for f, (c, k) in best.items()) + f"; {inside[0][0]} {delta:+.1f} s"
    if len(top) > 1:
        return "ambiguous", [t[1] for t in top], detail + f"; {len(top)} rows equally good", rid, delta
    if len(timed) > 1:
        detail += f"; {len(timed) - 1} weaker candidate(s)"
    dcol, sent = views.get("directionColumn"), views.get("directionSentValue")
    if dcol and sent is not None and entry.get("direction") in ("in", "out") and dcol in row:
        if row[dcol] in (None, ""):
            return "found, direction not stored", [rid], detail + f"; {dcol} is empty", rid, delta
        is_sent = str(row[dcol]) == str(sent)
        if is_sent != (entry["direction"] == "out"):
            return "mismatched", [rid], detail + f"; {dcol} = {row[dcol]!r} contradicts the logged direction " \
                                                 f"{entry['direction']!r}", rid, delta
    # The content decides when it was logged; a strong counterpart beside it does not lift a weak content match.
    lead = best.get("content") or next(iter(best.values()))
    if "weak" in lead[1]:
        return "found, weak match", [rid], detail, rid, delta
    return "found", [rid], detail, rid, delta


def session_windows(export, tol):
    """[(start_ms, end_ms, how)] the capture sessions ran, padded by the exact tolerance."""
    cur = md.current(export["rows"])
    pad = tol["exact"] * 1000
    out, start = [], None
    for r in cur:
        if r["action"] == "capture_session_start":
            start = r["utc_ms"]
        elif r["action"] == "capture_session_stop" and start is not None:
            out.append((start - pad, r["utc_ms"] + pad, "capture session"))
            start = None
    if start is not None:
        out.append((start - pad, max(r["utc_ms"] for r in cur) + pad, "capture session with no stop row"))
    if not out and cur:
        acts = [r["utc_ms"] for r in cur if r["kind"] == "action"] or [r["utc_ms"] for r in cur]
        out.append((min(acts) - pad, max(acts) + pad, "first to last entry (no session rows: a guess)"))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--export", action="append", required=True, help="KDL export folder (repeatable)")
    ap.add_argument("--leapp-output", action="append", required=True, help="[LABEL=]LEAPP output folder (repeatable)")
    ap.add_argument("--artifact", action="append", default=[], help="artifact key (core:key or key) to grade (repeatable)")
    ap.add_argument("--package", help="app package whose labelled actions --artifact is graded on")
    ap.add_argument("-o", "--output", required=True, help="output .xlsx")
    a = ap.parse_args(argv)
    if a.artifact and not a.package:
        ap.error("--artifact needs --package")

    exports = kc.load_exports(a.export)
    outputs = {}
    for spec in a.leapp_output:
        label, sep, folder = spec.partition("=")
        if not sep:
            if len(exports) != 1 or len(a.leapp_output) != 1:
                ap.error("name each output's device: --leapp-output LABEL=DIR")
            label, folder = next(iter(exports)), spec
        if label not in exports:
            ap.error(f"no export carries the device label {label!r}: {sorted(exports)}")
        outputs[label] = LeappOutput(folder)

    entry_rows, coverage, unmatched_rows, notes = [], collections.OrderedDict(), [], []
    graded = {}  # (device, entry_id) -> (status, best row time ms)
    for dev, out in outputs.items():
        export = exports[dev]
        tol, tol_note = kc.tolerances(export["manifest"])
        cur = md.current(export["rows"])
        acts = [r for r in cur if r["kind"] == "action" and not r["action"].startswith("deleted_")]
        plan = collections.OrderedDict()  # artifact (core:key as found in this output, else as asked) -> entries
        for r in acts:
            specs = list(r.get("expected_artifacts") or [])
            if a.package and r.get("app_package") == a.package:
                specs += a.artifact
            for spec in specs:
                art = out.find(spec)
                canon = f"{out.core}:{art['artifact_key']}" if art else spec
                if r not in plan.setdefault(canon, []):
                    plan[canon].append(r)
        matched_rows = collections.defaultdict(set)
        for spec, ents in plan.items():
            art = out.find(spec)
            key = (dev, spec)
            core = spec.rpartition(":")[0].lower()
            if art is None and core and core != out.core:
                coverage[key] = [dev, spec, None, 0, 0, 0, 0, 0, 0, None,
                                 f"{len(ents)} entr{'y' if len(ents) == 1 else 'ies'} not gradable here: this output is "
                                 f"{out.core}, the entries expect {core}"]
                for e in ents:
                    entry_rows.append([dev, kc.entry_label(e), e["action"], e.get("content"), e.get("counterpart"),
                                       e.get("direction"), spec, "not gradable", f"this output is {out.core}", "", None,
                                       e["entry_id"]])
                continue
            if art is None:
                coverage[key] = [dev, spec, out.core, len(ents), 0, 0, 0, 0, len(ents), 0.0,
                                 f"not in this output ({out.core} {out.version}): every entry counted missing"]
                for e in ents:
                    entry_rows.append([dev, kc.entry_label(e), e["action"], e.get("content"), e.get("counterpart"),
                                       e.get("direction"), spec, "missing", "artifact not in the output", "", None, e["entry_id"]])
                continue
            rows, types, err = out.rows(art)
            if rows is None:
                coverage[key] = [dev, spec, out.core, len(ents), 0, 0, 0, 0, 0, None, f"table not read: {err}"]
                continue
            counts = collections.Counter()
            for e in ents:
                status, ids, detail, rid, delta = grade(e, art, rows, types, tol)
                counts[bucket(status)] += 1
                matched_rows[art["tablename"]].update(ids)
                graded[(dev, e["entry_id"])] = (status, delta)
                entry_rows.append([dev, kc.entry_label(e), e["action"], e.get("content"), e.get("counterpart"),
                                   e.get("direction"), f"{out.core}:{art['artifact_key']}", status, detail,
                                   ", ".join(str(i) for i in ids[:20]), delta, e["entry_id"]])
            n = len(ents) - counts["not gradable"]
            note = f"tolerances {tol_note}"
            if counts["not gradable"]:
                note += f"; {counts['not gradable']} entr{'y' if counts['not gradable'] == 1 else 'ies'} not gradable"
            coverage[key] = [dev, f"{out.core}:{art['artifact_key']}", art.get("name"), n, counts["found"],
                             counts["found with a caveat"], counts["ambiguous"], counts["mismatched"], counts["missing"],
                             round(counts["found"] / n, 3) if n else None, note]
            # Rows inside a capture session that no entry accounts for.
            wins = session_windows(export, tol)
            dt_cols = [c for c, t in types.items() if t == "datetime"]
            for rid, row in rows:
                if rid in matched_rows[art["tablename"]]:
                    continue
                for c in dt_cols:
                    ms = datetime_ms(row.get(c))
                    w = next((w for w in wins if ms is not None and w[0] <= ms <= w[1]), None)
                    if w:
                        unmatched_rows.append([dev, f"{out.core}:{art['artifact_key']}", rid, c,
                                               datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
                                               w[2], " | ".join(f"{art['column_map'].get(k, k)}: {v}" for k, v in row.items()
                                                                if v not in (None, ""))[:500]])
                        break

    # Pairing across phones: an outgoing entry on one phone, an incoming one with the same text on another.
    pairs = []
    if len(outputs) > 1:
        devs = list(outputs)
        cur = {d: [r for r in md.current(exports[d]["rows"]) if r["kind"] == "action" and r.get("content")] for d in devs}
        for da in devs:
            for db in devs:
                if da == db:
                    continue
                for ra in (r for r in cur[da] if r.get("direction") == "out"):
                    for rb in (r for r in cur[db] if r.get("direction") == "in" and kc.norm(r["content"]) == kc.norm(ra["content"])):
                        sa, ta = graded.get((da, ra["entry_id"]), ("not graded", None))
                        sb, tb = graded.get((db, rb["entry_id"]), ("not graded", None))
                        gap = round((rb["utc_ms"] - ra["utc_ms"]) / 1000, 1)
                        pairs.append([da, kc.entry_label(ra), db, kc.entry_label(rb), ra["content"], sa, sb, gap,
                                      "both found" if sa == "found" and sb == "found" else "check the Per entry sheet"])

    used = set()
    wb = Workbook()
    ws = wb.active
    ws.title = md.sheet_name("Summary", used)
    summary = [("Generated (UTC)", datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"))]
    for dev, out in outputs.items():
        e = exports[dev]
        summary += [(f"{dev}: export", os.path.basename(os.path.normpath(e["folder"]))),
                    (f"{dev}: export checks", "; ".join(f"{k}: {v}" for k, v in e["checks"])),
                    (f"{dev}: LEAPP output", f"{out.folder} ({out.core} {out.version})")]
    summary += [
        ("Statuses", "found: one row holds the text with a datetime inside the tolerance. Found with a caveat: "
                     "found (time only), found, time not checked (no datetime column), found, direction not stored, "
                     "found, weak match (a short value matched whole). ambiguous: several rows equally good; "
                     "mismatched: the text is there but the time is outside the tolerance or the direction column "
                     "contradicts the log; missing: no row holds the text; not gradable: no text and no datetime "
                     "column, or an output from another core."),
        ("Rows not matched", "Rows with a datetime inside a capture session that no graded entry accounts for. "
                             "Some are expected (data the app made on its own); each needs a look, not a verdict."),
        ("Time", "LAVA datetime cells are read as UTC epoch seconds. Whole-second storage can move a time by up to 1 s."),
    ]
    md.write_table(ws, ["Item", "Value"], summary + [(n, "") for n in notes])
    md.write_table(wb.create_sheet(md.sheet_name("Per entry", used)),
                   ["Device", "Entry time", "Action", "Content", "Counterpart", "Direction", "Artifact", "Status",
                    "Detail", "Row ids", "Time delta s", "Entry id"], entry_rows)
    md.write_table(wb.create_sheet(md.sheet_name("Coverage", used)),
                   ["Device", "Artifact", "Name", "Entries graded", "Found", "Found with a caveat", "Ambiguous",
                    "Mismatched", "Missing",
                    "Coverage (found / graded)", "Note"], list(coverage.values()))
    md.write_table(wb.create_sheet(md.sheet_name("Rows not matched", used)),
                   ["Device", "Artifact", "Row id", "Datetime column", "Time", "Window", "Row"], unmatched_rows)
    md.write_table(wb.create_sheet(md.sheet_name("Pairs", used)),
                   ["Sender device", "Sent at", "Receiver device", "Received at", "Content", "Sender status",
                    "Receiver status", "Seconds between the two taps", "Result"], pairs)
    wb.save(a.output)
    for out in outputs.values():
        out.con.close()
    found = sum(1 for r in entry_rows if r[7] == "found")
    print(f"Wrote {a.output}: {len(entry_rows)} graded, {found} found, {len(unmatched_rows)} rows not matched, "
          f"{len(pairs)} pair(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
