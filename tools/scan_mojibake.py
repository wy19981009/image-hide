# -*- coding: utf-8 -*-
"""Scan for double-encoded UTF-8 Chinese mojibake (bytes like C3 A5 C2 ..)."""
from __future__ import annotations

import os
import re
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {
    ".git", "build", ".gradle", "node_modules", ".idea",
    "caches", "intermediates", "kspCaches", "tmp", ".cxx",
}
EXTS = {".kt", ".xml", ".properties", ".md", ".txt", ".kts", ".pro", ".json"}

# Double-encoded UTF-8: original Chinese byte E5.. was misread as Latin-1 then
# re-saved as UTF-8 -> C3 A5 / C3 A9 / C2 8x patterns.
DOUBLE = re.compile(rb"(?:\xC3[\xA0-\xBF]\xC2[\x80-\xBF]){2,}")
# lone C1 controls often leftover from mojibake
CTRL = re.compile(rb"[\xC2][\x80-\x9F]")


def main() -> None:
    hits: dict[str, list[str]] = defaultdict(list)
    for dirpath, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            ext = os.path.splitext(name)[1].lower()
            if ext not in EXTS and name not in {"gradle.properties", "local.properties"}:
                continue
            if name in {"scan_mojibake.py", "_check_line.py", "_line_dump.txt"}:
                continue
            path = os.path.join(dirpath, name)
            try:
                raw = open(path, "rb").read()
            except OSError:
                continue
            for m in DOUBLE.finditer(raw):
                start = max(0, m.start() - 40)
                end = min(len(raw), m.end() + 40)
                ctx = raw[start:end]
                hits[path].append(
                    f"@{m.start()}: {ctx!r}"
                )
            # also flag C2 80-9F if near quotes (string content)
            if path not in hits:
                for m in CTRL.finditer(raw):
                    # only report if in a likely string (nearby quote)
                    window = raw[max(0, m.start() - 30) : m.end() + 30]
                    if b'"' in window or b"'" in window:
                        hits[path].append(f"ctrl@{m.start()}: {window!r}")
                        break

    print(f"files_with_hits={len(hits)}")
    for path, items in sorted(hits.items()):
        rel = os.path.relpath(path, ROOT)
        print(f"\n== {rel}")
        for s in items[:20]:
            print(" ", s)
        if len(items) > 20:
            print(f"  ... +{len(items) - 20} more")


if __name__ == "__main__":
    main()
