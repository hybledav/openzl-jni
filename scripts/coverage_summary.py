#!/usr/bin/env python3

import argparse
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a coverage summary from a JaCoCo XML report.")
    parser.add_argument(
        "--report",
        required=True,
        type=Path,
        help="Path to jacoco.xml report.",
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="File to write the markdown summary to.",
    )
    parser.add_argument(
        "--append-to-summary",
        action="store_true",
        help="Append the summary to the GitHub step summary (uses GITHUB_STEP_SUMMARY env).",
    )
    return parser.parse_args()


def format_summary(report: Path) -> str:
    root = ET.parse(report).getroot()
    totals = {
        counter.get("type"): (int(counter.get("missed")), int(counter.get("covered")))
        for counter in root.findall("counter")
    }

    def line(name: str) -> tuple[float, int, int]:
        missed, covered = totals.get(name, (0, 0))
        total = missed + covered
        pct = 0.0 if total == 0 else covered * 100.0 / total
        return pct, covered, total

    sections = []
    for metric in ("LINE", "BRANCH", "METHOD"):
        pct, covered, total = line(metric)
        sections.append(f"- {metric.title()}: {pct:.2f}% ({covered}/{total})")

    return "## Coverage Summary\n" + "\n".join(sections) + "\n"


def main() -> None:
    args = parse_args()
    if not args.report.is_file():
        raise FileNotFoundError(f"JaCoCo report not found: {args.report}")

    summary = format_summary(args.report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(summary, encoding="utf-8")

    if args.append_to_summary:
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with open(summary_path, "a", encoding="utf-8") as handle:
                handle.write(summary)


if __name__ == "__main__":
    main()
