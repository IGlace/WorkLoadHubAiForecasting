"""Clean the WorkloadHub PostgreSQL dump into the schema file the tools module ships: the dump
without psql directives, SET lines, OWNER statements and dump banners. Run it again whenever the
owner's schema changes and commit the output.

Usage: python3 server/tools/translate-schema.py path/to/schema.sql
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "forecast-core/src/main/resources/schema"


def clean_postgresql(src: str) -> str:
    kept = []
    for line in src.splitlines():
        if line.startswith(("\\restrict", "\\unrestrict", "SET ", "SELECT pg_catalog")):
            continue
        if re.match(r"^ALTER (TABLE|SCHEMA) .* OWNER TO postgres;$", line):
            continue
        if line.startswith(("-- TOC", "-- Dumped", "-- Started", "-- Completed")):
            continue
        kept.append(line)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(kept)).strip() + "\n"


def main() -> None:
    src = Path(sys.argv[1]).read_text(encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "workloadhub-postgresql.sql").write_text(clean_postgresql(src), encoding="utf-8")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
