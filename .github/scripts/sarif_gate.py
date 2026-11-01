#!/usr/bin/env python3
"""Summarise CodeQL SARIF results and fail when a finding reaches the security-severity threshold.

Used because code scanning (GitHub Advanced Security) is not available on this private repository:
results are not uploaded, so the gate runs in the workflow instead.
Usage: sarif_gate.py <sarif-dir> <min-security-severity>
"""
import glob
import json
import os
import sys


def main() -> int:
    sarif_dir, threshold = sys.argv[1], float(sys.argv[2])
    files = glob.glob(os.path.join(sarif_dir, "*.sarif"))
    if not files:
        print("::error::No SARIF file produced by CodeQL")
        return 1
    findings = []
    for path in files:
        with open(path, encoding="utf-8") as fh:
            sarif = json.load(fh)
        for run in sarif.get("runs", []):
            rules = {}
            tool = run.get("tool", {})
            for comp in [tool.get("driver", {})] + tool.get("extensions", []):
                for rule in comp.get("rules", []):
                    rules[rule.get("id")] = rule
            for res in run.get("results", []):
                rule = rules.get(res.get("ruleId"), {})
                sev = float(rule.get("properties", {}).get("security-severity", 0) or 0)
                loc = res.get("locations", [{}])[0].get("physicalLocation", {})
                where = "%s:%s" % (
                    loc.get("artifactLocation", {}).get("uri", "?"),
                    loc.get("region", {}).get("startLine", "?"),
                )
                findings.append((sev, res.get("ruleId"), res.get("level", "warning"), where,
                                 res.get("message", {}).get("text", "")))
    findings.sort(reverse=True)
    lines = ["## CodeQL findings: %d" % len(findings), ""]
    if findings:
        lines += ["| security-severity | rule | level | location |", "|---|---|---|---|"]
        lines += ["| %s | `%s` | %s | `%s` |" % (s or "-", r, l, w) for s, r, l, w, _ in findings]
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    blocking = [f for f in findings if f[0] >= threshold]
    for s, r, _, w, m in blocking:
        print("::error title=CodeQL %s (%.1f)::%s %s" % (r, s, w, m))
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
