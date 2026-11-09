"""Shared pieces of kdl_locate.py and kdl_check.py: which log entries are searched, how their
text and time are matched, and the tolerance each entry gets.

The export is read with make_documentation.load_export (sha256 and hash chain checks) and
make_documentation.current (highest revision, tombstones dropped, device order).
"""
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_documentation as md  # noqa: E402

# Model.kt Tolerance: the app writes these into every manifest as tolerances_s. Used only when a
# manifest has none (an export from before the field existed); the Summary sheet says so.
DEFAULT_TOLERANCES_S = {"exact": 120, "before_after": 300, "approximate": 900}

# Raw events that carry text the tester typed or received (Model.kt AutoAction).
TEXT_EVENTS = ("typed_text", "notification")

# Epochs a stored time is read in. Each converts a number to Unix milliseconds.
EPOCHS = (
    ("Unix seconds", lambda v: v * 1000),
    ("Unix milliseconds", lambda v: v),
    ("Unix microseconds", lambda v: v / 1000),
    ("Unix nanoseconds", lambda v: v / 1_000_000),
    ("Cocoa seconds (2001)", lambda v: (v + 978_307_200) * 1000),
    ("WebKit microseconds (1601)", lambda v: v / 1000 - 11_644_473_600_000),
    ("FILETIME 100 ns (1601)", lambda v: v / 10_000 - 11_644_473_600_000),
)
# A converted value outside 2000-01-01 .. 2100-01-01 is not a candidate.
PLAUSIBLE_MS = (946_684_800_000, 4_102_444_800_000)

MIN_NEEDLE = 3        # shorter text is not searched
MIN_CONTAINED = 6     # shorter single-word text is matched whole only, and flagged weak

PHONE_RE = re.compile(r"^\+?[\d\s().\-]{7,}$")


def load_exports(folders):
    """make_documentation.load_export for each folder, keyed by device label."""
    out = {}
    for f in folders:
        e = md.load_export(f)
        label = e["manifest"].get("device_label") or os.path.basename(os.path.normpath(f))
        if label in out:
            raise SystemExit(f"two exports carry the device label {label!r}; give one export per phone")
        out[label] = e
    return out


def tolerances(manifest):
    """(tolerance dict, note). The manifest's own values when present."""
    t = manifest.get("tolerances_s")
    if isinstance(t, dict) and all(isinstance(t.get(k), (int, float)) for k in DEFAULT_TOLERANCES_S):
        return {k: t[k] for k in DEFAULT_TOLERANCES_S}, "from the manifest"
    return dict(DEFAULT_TOLERANCES_S), "manifest has no tolerances_s; the app's defaults (Model.kt Tolerance) used"


def window_ms(entry, tol):
    """(start_ms, end_ms) a stored time may fall in to match this entry.

    Symmetric around the tap: the before/after relation is the tester's claim, so the signed
    delta is reported rather than used to cut the window. A windowed entry
    (window_s) spans the window plus the exact tolerance on both sides."""
    t = entry["utc_ms"]
    w = entry.get("window_s")
    if w:
        pad = tol["exact"] * 1000
        return t - pad, t + int(w * 1000) + pad
    q = entry.get("time_quality") or "exact"
    s = tol["before_after"] if q in ("before", "after") else tol.get(q, tol["exact"])
    return t - s * 1000, t + s * 1000


def searched_entries(export, package):
    """(entries, deletions, skipped) for one device and package.

    entries: current `action` rows of the package that are not deletions, and current
    typed_text / notification events of the package that no current row refers to and whose
    details Android did not withhold. deletions: current `deleted_*` actions of the package.
    skipped: (row, reason) for current rows of other kinds or with no package."""
    cur = md.current(export["rows"])
    referred = {r["refers_to"] for r in cur if r.get("refers_to")}
    by_id = {}
    for r in export["rows"]:
        k = r["entry_id"]
        if k not in by_id or (r["revision_n"], r["seq"]) > (by_id[k]["revision_n"], by_id[k]["seq"]):
            by_id[k] = r
    entries, deletions, skipped = [], [], []
    for r in cur:
        pkg = r.get("app_package")
        if r["kind"] == "action":
            if pkg != package:
                skipped.append((r, "no app package" if not pkg else "another app"))
            elif r["action"].startswith("deleted_"):
                deletions.append(r)
            else:
                entries.append(r)
        elif r["kind"] == "auto_event" and r["action"] in TEXT_EVENTS and pkg == package:
            if r["entry_id"] in referred:
                skipped.append((r, "a labelled action refers to this event"))
            elif r.get("detail") == md.WITHHELD:
                skipped.append((r, "details withheld by Android"))
            elif not any(n.searchable for n in needles(r)):
                skipped.append((r, "raw event with no text to search"))
            else:
                entries.append(r)
    return entries, deletions, skipped, by_id


# ---- text matching -------------------------------------------------------------------------

def norm(s):
    return " ".join(unicodedata.normalize("NFKC", s).casefold().split())


def fold(s):
    """norm() with accents removed: 'Reykjavík' and 'Reykjavik' fold to the same text."""
    d = unicodedata.normalize("NFKD", s)
    return norm("".join(c for c in d if not unicodedata.combining(c)))


class Needle:
    """One value from a log entry to look for."""

    def __init__(self, field, value):
        self.field = field
        self.value = value.strip()
        self.n = norm(self.value)
        self.f = fold(self.value)
        self.searchable = len(self.value) >= MIN_NEEDLE
        self.whole_only = len(self.value) < MIN_CONTAINED and len(self.value.split()) < 2
        self.digits = None
        if field == "counterpart" and PHONE_RE.match(self.value):
            d = re.sub(r"\D", "", self.value)
            if len(d) >= 7:
                self.digits = d[-10:]
        self.utf8 = self.value.encode("utf-8")

    def match_text(self, text):
        """Match kind for a text value, or None. Kinds, strongest first."""
        if text == self.value:
            return "exact"
        if self.whole_only:
            if norm(text) == self.n:
                return "normalised, whole value (weak: short)"
            if fold(text) == self.f:
                return "accent-folded, whole value (weak: short)"
            return self._phone(text)
        if self.value in text:
            return "contained"
        n = norm(text)
        if self.n in n:
            return "normalised" if n != self.n else "normalised, whole value"
        if self.f in fold(text):
            return "accent-folded"
        return self._phone(text)

    def _phone(self, text):
        if self.digits and PHONE_RE.match(text.strip()):
            d = re.sub(r"\D", "", text)
            if len(d) >= 7 and (d.endswith(self.digits) or self.digits.endswith(d)):
                return "phone digits"
        return None

    def match_number(self, value):
        """A counterpart phone number stored as an integer."""
        if self.digits and isinstance(value, int) and not isinstance(value, bool):
            d = str(abs(value))
            if len(d) >= 7 and (d.endswith(self.digits) or self.digits.endswith(d)):
                return "phone digits (integer)"
        return None


def needles(entry, fields=("content", "counterpart", "conversation_id")):
    out = []
    for f in fields:
        v = entry.get(f)
        if isinstance(v, str) and v.strip():
            out.append(Needle(f, v))
    return out


# ---- time matching -------------------------------------------------------------------------

def as_number(value):
    """int/float, or an all-digit text of 9 to 19 digits; else None."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return value
    if isinstance(value, str):
        s = value.strip()
        if s.isdigit() and 9 <= len(s) <= 19:
            return int(s)
    return None


def time_readings(value):
    """[(epoch name, unix ms)] for every epoch that reads the value as a plausible time."""
    v = as_number(value)
    if v is None or v <= 0:
        return []
    out = []
    for name, conv in EPOCHS:
        try:
            ms = conv(v)
        except OverflowError:
            continue
        if PLAUSIBLE_MS[0] <= ms <= PLAUSIBLE_MS[1]:
            out.append((name, ms))
    return out


def time_hits(value, start_ms, end_ms, tap_ms):
    """[(epoch, delta seconds from the tap)] for readings inside the window."""
    return [(name, round((ms - tap_ms) / 1000, 3)) for name, ms in time_readings(value) if start_ms <= ms <= end_ms]


SINCE_BOOT = "milliseconds since boot (the entry's elapsed_ms; same boot assumed)"
MIN_SINCE_BOOT_MS = 60_000  # smaller numbers are too common to read as a clock


def entry_time_hits(value, entry, start_ms, end_ms):
    """time_hits plus a reading as milliseconds since boot, against the entry's elapsed_ms.

    KDL logs SystemClock.elapsedRealtime() with every entry, so a store that keeps the same
    clock lines up with it. Nothing in the store says which boot a value belongs to, so the
    label says the same boot is assumed."""
    out = time_hits(value, start_ms, end_ms, entry["utc_ms"])
    el = entry.get("elapsed_ms")
    v = as_number(value)
    if el is not None and isinstance(v, int) and v >= MIN_SINCE_BOOT_MS:
        lo, hi = el + (start_ms - entry["utc_ms"]), el + (end_ms - entry["utc_ms"])
        if lo <= v <= hi:
            out.append((SINCE_BOOT, round((v - el) / 1000, 3)))
    return out


def entry_label(r):
    d, t, off = md.local_time(r)
    return f"{d} {t} {off}"
