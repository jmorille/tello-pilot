#!/usr/bin/env python3
"""Turns a Kover/JaCoCo XML report into a self-contained SVG coverage badge.

Usage: coverage_badge.py <report.xml> <badge.svg>

Reads the report-level LINE counter (total of every merged module), writes a flat
shields-like badge (no external service: the repository is private) and prints the
percentage, also exported as `percent` to $GITHUB_OUTPUT when it is set.
"""
import os
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


def line_coverage(report_path: str) -> float:
    root = ET.parse(report_path).getroot()
    for counter in root.findall("counter"):  # direct children only: the report total
        if counter.get("type") == "LINE":
            missed, covered = int(counter.get("missed")), int(counter.get("covered"))
            total = missed + covered
            return 100.0 * covered / total if total else 0.0
    raise SystemExit(f"no report-level LINE counter in {report_path}")


def color(percent: float) -> str:
    for threshold, value in ((90, "#4c1"), (80, "#97ca00"), (70, "#a4a61d"), (60, "#dfb317"), (40, "#fe7d37")):
        if percent >= threshold:
            return value
    return "#e05d44"


def text_width(text: str) -> int:
    # Approximation of Verdana 11px (shields.io metrics), good enough for digits and short labels.
    return sum(4 if c in " .,:;|!il1" else 9 if c in "%mwMW" else 7 for c in text) + 10


def badge(label: str, value: str, fill: str) -> str:
    lw, vw = text_width(label), text_width(value)
    w = lw + vw
    label, value = escape(label), escape(value)
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="20" role="img" aria-label="{label}: {value}">
<title>{label}: {value}</title>
<linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
<clipPath id="r"><rect width="{w}" height="20" rx="3" fill="#fff"/></clipPath>
<g clip-path="url(#r)"><rect width="{lw}" height="20" fill="#555"/><rect x="{lw}" width="{vw}" height="20" fill="{fill}"/><rect width="{w}" height="20" fill="url(#s)"/></g>
<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
<text x="{lw / 2}" y="15" fill="#010101" fill-opacity=".3">{label}</text><text x="{lw / 2}" y="14">{label}</text>
<text x="{lw + vw / 2}" y="15" fill="#010101" fill-opacity=".3">{value}</text><text x="{lw + vw / 2}" y="14">{value}</text>
</g>
</svg>
"""


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    percent = line_coverage(sys.argv[1])
    value = f"{percent:.1f}%"
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        f.write(badge("coverage", value, color(percent)))
    print(f"Line coverage: {value}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
            out.write(f"percent={percent:.1f}\n")


if __name__ == "__main__":
    main()
