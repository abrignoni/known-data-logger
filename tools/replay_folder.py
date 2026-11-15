#!/usr/bin/env python3
"""replay_folder.py LOOKS_FILE EXPORT_ACTIONS OUT_DIR

Builds one replay folder for ReplayTest from a recorded looks file (pulled from the phone with adb) and the
actions.jsonl of an export that holds the same session. The session is found by its start row, whose time
matches the looks file's name (looks-<UTC start>.jsonl) within 2 seconds. Only that session's rows, revision 0,
are kept. Then: KDL_REPLAY_DIR=<parent of OUT_DIR> ./gradlew testDebugUnitTest --tests '*ReplayTest*'
"""
import datetime, json, os, re, shutil, sys

looks, actions, out = sys.argv[1:4]
m = re.search(r"looks-(\d{8}T\d{6}Z)\.jsonl$", looks)
if not m:
    sys.exit("the looks file name must end looks-<yyyymmddThhmmssZ>.jsonl")
start = datetime.datetime.strptime(m.group(1), "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc).timestamp()
with open(actions, encoding="utf-8") as f:
    rows = [json.loads(l) for l in f if l.strip()]
starts = [i for i, r in enumerate(rows) if r["action"] == "capture_session_start" and abs(r["utc_ms"] / 1000 - start) < 2]
if len(starts) != 1:
    sys.exit(f"found {len(starts)} session starts at that time in {actions}")
i = starts[0]
j = next((k for k in range(i + 1, len(rows)) if rows[k]["action"] == "capture_session_start"), len(rows))
os.makedirs(out, exist_ok=True)
shutil.copy(looks, os.path.join(out, "looks.jsonl"))
with open(os.path.join(out, "actions.jsonl"), "w", encoding="utf-8") as f:
    for r in rows[i:j]:
        if r.get("revision_n", 0) == 0:
            f.write(json.dumps(r) + "\n")
print(f"{out}: {j - i} rows of that session")
