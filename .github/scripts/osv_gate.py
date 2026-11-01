#!/usr/bin/env python3
"""Summarise OSV-Scanner JSON output and fail on high/critical vulnerabilities.

Usage: osv_gate.py <osv-results.json> <min-cvss-score>
"""
import json
import os
import sys


def main() -> int:
    path, threshold = sys.argv[1], float(sys.argv[2])
    if not os.path.exists(path):
        print("::error::OSV-Scanner produced no result file")
        return 1
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    rows = []
    for result in data.get("results", []):
        source = result.get("source", {}).get("path", "?")
        for pkg in result.get("packages", []):
            info = pkg.get("package", {})
            for group in pkg.get("groups", []):
                try:
                    score = float(group.get("max_severity") or 0)
                except ValueError:
                    score = 0.0
                rows.append((score, "%s:%s" % (info.get("name"), info.get("version")),
                             ", ".join(group.get("ids", [])), os.path.relpath(source)))
    rows.sort(reverse=True)
    lines = ["## OSV-Scanner: %d vulnerability group(s)" % len(rows), ""]
    if rows:
        lines += ["| CVSS | package | advisories | lockfile |", "|---|---|---|---|"]
        lines += ["| %s | `%s` | %s | `%s` |" % (s or "?", p, i, f) for s, p, i, f in rows]
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    blocking = [r for r in rows if r[0] >= threshold]
    for s, p, i, _ in blocking:
        print("::error title=Vulnerable dependency %s::%s (CVSS %.1f)" % (p, i, s))
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
