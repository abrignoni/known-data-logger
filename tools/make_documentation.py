#!/usr/bin/env python3
"""Build known-data documentation (.xlsx) from one or more known-data logger exports.

Usage:
    python3 tools/make_documentation.py EXPORT_DIR [EXPORT_DIR ...] -o documentation.xlsx

Each EXPORT_DIR is a kdl_<scenario>_<device>_<time> folder with manifest.json and
actions.jsonl. Give the exports of every phone in one scenario to get one document.

The script checks the sha256 of actions.jsonl against the manifest and re-hashes every
screenshot. A mismatch is reported on the Scenario sheet; it does not stop the run.

Requires openpyxl.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timedelta, timezone

try:
    from openpyxl import Workbook
    from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
    from openpyxl.styles import Font
    from openpyxl.utils import get_column_letter
except ImportError:
    sys.exit("openpyxl is required: pip install openpyxl")

ACTION_COLUMNS = [
    "Device", "Date", "Time", "UTC offset", "Action", "Content", "Counterpart", "Direction",
    "Conversation", "Time quality", "Window (s)", "Source", "Reviewed", "Screenshot", "Entry id",
]


def load_export(folder):
    """Reads one export. Returns a dict with the manifest, all rows and check results."""
    with open(os.path.join(folder, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    with open(os.path.join(folder, "actions.jsonl"), "rb") as f:
        raw = f.read()
    rows = [json.loads(line) for line in raw.decode("utf-8").splitlines() if line.strip()]
    checks = []
    want = manifest.get("files", {}).get("actions.jsonl", {}).get("sha256")
    got = hashlib.sha256(raw).hexdigest()
    checks.append(("actions.jsonl sha256", "match" if want == got else f"MISMATCH (manifest {want}, file {got})"))
    shots = {}
    for s in manifest.get("screenshots", []):
        # Only the base name is used, so a manifest cannot point outside screens/.
        path = os.path.join(folder, "screens", os.path.basename(s["file"])) if s.get("file") else None
        status = s.get("status")
        if path and os.path.exists(path):
            with open(path, "rb") as f:
                h = hashlib.sha256(f.read()).hexdigest()
            if h != s.get("sha256_at_capture"):
                checks.append((f"screenshot {s['file']}", "MISMATCH with hash taken at capture"))
        elif path:
            checks.append((f"screenshot {s['file']}", "listed in manifest but missing from screens/"))
            path = None
        shots[s["entry_id"]] = {"path": path, "status": status}
    ok = sum(1 for s in shots.values() if s["path"])
    checks.append(("screenshots", f"{ok} file(s), {len(shots)} listed"))
    return {"folder": folder, "manifest": manifest, "rows": rows, "checks": checks, "shots": shots}


def current(rows):
    """Highest revision of each entry; drops entries whose latest revision is a tombstone."""
    best = {}
    for r in rows:
        k = r["entry_id"]
        if k not in best or (r["revision_n"], r["seq"]) > (best[k]["revision_n"], best[k]["seq"]):
            best[k] = r
    out = [r for r in best.values() if r["kind"] != "tombstone"]
    return sorted(out, key=lambda r: (r["boot_count"], r["elapsed_ms"], r["seq"]))


def pending_review(cur):
    """Same rule as the app: unaccepted auto labels, unlabelled marks, stamped-only entries."""
    resolved = {r["refers_to"] for r in cur if r.get("refers_to")}
    out = []
    for r in cur:
        if r["kind"] == "action" and r.get("source") == "auto_rule" and not r.get("reviewed", True):
            out.append((r, "auto label not accepted"))
        elif r["kind"] == "auto_event" and r["action"] == "mark" and r["entry_id"] not in resolved:
            out.append((r, "mark without a label"))
        elif r["kind"] == "action" and not r["action"].strip():
            out.append((r, "stamped, no action written"))
    return out


def local_time(r):
    off = timedelta(minutes=r["tz_offset_min"])
    t = datetime.fromtimestamp(r["utc_ms"] / 1000, tz=timezone.utc).astimezone(timezone(off))
    sign = "+" if r["tz_offset_min"] >= 0 else "-"
    m = abs(r["tz_offset_min"])
    return t.strftime("%Y-%m-%d"), t.strftime("%H:%M:%S"), f"UTC{sign}{m // 60:02d}:{m % 60:02d}"


def text_cell(ws, row, col, value):
    """Writes a value as plain text, so content such as '=1+1' is never read as a formula.
    Control characters Excel cannot hold are shown as [U+XXXX] instead of failing the build."""
    if isinstance(value, str):
        value = ILLEGAL_CHARACTERS_RE.sub(lambda m: f"[U+{ord(m.group()):04X}]", value)
    c = ws.cell(row=row, column=col, value=value)
    if isinstance(value, str):
        c.data_type = "s"
    return c


def sheet_name(name, used):
    base = re.sub(r"[\[\]:*?/\\]", "_", name)[:31] or "Sheet"
    n, i = base, 2
    while n.lower() in used:
        suffix = f" ({i})"
        n = base[: 31 - len(suffix)] + suffix
        i += 1
    used.add(n.lower())
    return n


def write_table(ws, headers, rows):
    for j, h in enumerate(headers, 1):
        ws.cell(row=1, column=j, value=h).font = Font(bold=True)
    for i, values in enumerate(rows, 2):
        for j, v in enumerate(values, 1):
            text_cell(ws, i, j, v)
    ws.freeze_panes = "A2"
    for j, h in enumerate(headers, 1):
        width = max([len(str(h))] + [len(str(v[j - 1])) for v in rows if v[j - 1] is not None] or [10])
        ws.column_dimensions[get_column_letter(j)].width = min(max(width + 2, 8), 60)


def action_values(r, device, shot, out_dir):
    d, t, off = local_time(r)
    link = None
    if shot and shot["path"]:
        link = os.path.relpath(shot["path"], out_dir)
    elif shot:
        link = shot["status"]
    return [device, d, t, off, r["action"], r.get("content"), r.get("counterpart"), r.get("direction"),
            r.get("conversation_id"), r.get("time_quality"), r.get("window_s"), r.get("source"),
            "yes" if r.get("reviewed", True) else "no", link, r["entry_id"]]


def build(exports, out_path):
    out_dir = os.path.dirname(os.path.abspath(out_path))
    wb = Workbook()
    used = set()
    ws = wb.active
    ws.title = sheet_name("Scenario", used)

    scenario_ids = sorted({e["manifest"].get("scenario_id", "") for e in exports})
    lines = [("Scenario", ", ".join(scenario_ids)), ("Generated (UTC)", datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S"))]
    if len(scenario_ids) > 1:
        lines.append(("Warning", "exports come from different scenario ids"))
    for e in exports:
        m = e["manifest"]
        p = (m.get("profiles") or [{}])[-1]
        dev = m.get("device_label", "")
        lines += [("", ""), ("Device", dev), ("Export folder", os.path.basename(e["folder"])),
                  ("Make and model", f"{p.get('manufacturer', '')} {p.get('model', '')}".strip()),
                  ("Android", f"{p.get('release', '')} (SDK {p.get('sdk_int', '')}), build {p.get('incremental', '')}"),
                  ("Security patch", p.get("security_patch")), ("Fingerprint", p.get("fingerprint")),
                  ("Kernel", p.get("kernel")), ("Baseband", p.get("baseband")), ("Carrier", p.get("carrier")),
                  ("Logger", f"{m.get('logger', {}).get('package')} {m.get('logger', {}).get('version_name')}"),
                  ("Profile snapshots", len(m.get("profiles") or []))]
        for name, result in e["checks"]:
            lines.append((f"Check: {name}", result))
        for r in current(e["rows"]):
            if r["kind"] == "clock_change" or r["action"] in ("boot_detected", "capture_session_start", "capture_session_stop",
                                                              "capture_service_disconnected", "capture_service_connected",
                                                              "export_failed"):
                d, t, off = local_time(r)
                lines.append((f"{d} {t} {off}", f"{r['action']}: {r.get('detail') or ''}".strip(": ")))
    for i, (k, v) in enumerate(lines, 1):
        ws.cell(row=i, column=1, value=k).font = Font(bold=bool(k) and not k[:1].isdigit())
        text_cell(ws, i, 2, v)
    ws.column_dimensions["A"].width = 28
    ws.column_dimensions["B"].width = 90

    # Apps under test
    ws = wb.create_sheet(sheet_name("Apps", used))
    app_rows = []
    labels = {}
    for e in exports:
        for a in e["manifest"].get("apps_under_test", []):
            labels.setdefault(a["package"], a.get("label") or a["package"])
            ins = a.get("first_install_ms")
            app_rows.append([e["manifest"].get("device_label"), a.get("label"), a["package"], a.get("version_name_at_add"),
                             a.get("version_name_at_export"),
                             datetime.fromtimestamp(ins / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC") if ins else None,
                             a.get("account")])
    write_table(ws, ["Device", "App", "Package", "Version when added", "Version at export", "First install", "Account"], app_rows)

    # Actions per app. Rows are grouped by device and kept in each device's own order
    # (boot_count, elapsed_ms, seq), which survives clock changes; devices follow argument order.
    per_app = {}
    review_rows, event_rows = [], []
    for di, e in enumerate(exports):
        dev = e["manifest"].get("device_label", "")
        cur = current(e["rows"])  # already in device order
        for r, why in pending_review(cur):
            d, t, off = local_time(r)
            review_rows.append([dev, d, t, off, r["action"] or "(none)", r.get("source"), why, r.get("content"), r["entry_id"]])
        for pos, r in enumerate(cur):
            # A label made from a mark or captured event shows that event's screenshot.
            shot = e["shots"].get(r["entry_id"]) or (e["shots"].get(r["refers_to"]) if r.get("refers_to") else None)
            order = (di, pos)
            if r["kind"] == "action":
                per_app.setdefault(r.get("app_package") or "", []).append((order, action_values(r, dev, shot, out_dir)))
            elif r["kind"] == "auto_event":
                d, t, off = local_time(r)
                link = (os.path.relpath(shot["path"], out_dir) if shot and shot["path"] else (shot["status"] if shot else None))
                event_rows.append((order, [dev, d, t, off, labels.get(r.get("app_package"), r.get("app_package")),
                                           r["action"], r.get("content"), r.get("detail"), link, r["entry_id"]]))
    for pkg in sorted(per_app, key=lambda p: (p == "", labels.get(p, p).lower())):
        title = labels.get(pkg, pkg) if pkg else "Device and system"
        ws = wb.create_sheet(sheet_name(title, used))
        rows = [v for _, v in sorted(per_app[pkg], key=lambda x: x[0])]
        write_table(ws, ACTION_COLUMNS, rows)
        col = ACTION_COLUMNS.index("Screenshot") + 1
        for i, v in enumerate(rows, 2):
            target = v[col - 1]
            if target and target.endswith(".png"):
                c = ws.cell(row=i, column=col)
                c.hyperlink = target
                c.font = Font(color="0563C1", underline="single")

    ws = wb.create_sheet(sheet_name("Needs review", used))
    write_table(ws, ["Device", "Date", "Time", "UTC offset", "Action", "Source", "Why", "Content", "Entry id"], review_rows)
    ws = wb.create_sheet(sheet_name("Captured events", used))
    write_table(ws, ["Device", "Date", "Time", "UTC offset", "App", "Event", "Content", "Detail", "Screenshot", "Entry id"],
                [v for _, v in sorted(event_rows, key=lambda x: x[0])])
    for i, v in enumerate([v for _, v in sorted(event_rows, key=lambda x: x[0])], 2):
        if v[8] and v[8].endswith(".png"):
            c = ws.cell(row=i, column=9)
            c.hyperlink = v[8]
            c.font = Font(color="0563C1", underline="single")
    wb.save(out_path)
    return {"sheets": wb.sheetnames, "review": len(review_rows), "events": len(event_rows),
            "actions": sum(len(v) for v in per_app.values())}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("exports", nargs="+", help="export folder(s)")
    ap.add_argument("-o", "--output", required=True, help="output .xlsx path")
    a = ap.parse_args(argv)
    exports = [load_export(f) for f in a.exports]
    info = build(exports, a.output)
    print(f"Wrote {a.output}: {info['actions']} actions, {info['review']} to review, {info['events']} captured events")
    print("Sheets: " + ", ".join(info["sheets"]))
    for e in exports:
        for name, result in e["checks"]:
            if "MISMATCH" in result or "missing" in result:
                print(f"WARNING {os.path.basename(e['folder'])}: {name}: {result}")


if __name__ == "__main__":
    main()
