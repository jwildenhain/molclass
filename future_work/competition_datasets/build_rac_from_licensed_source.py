#!/usr/bin/env python3
"""Build IRAC/FRAC/HRAC MoA SDFs from a redistribution-authorized table."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from build_fda_safety import (
    SCRIPT_DIR,
    SourceRecord,
    analyze_sdf,
    atomic_json,
    clean_text,
    class_balance,
    materialize_benchmark,
    rejection,
    resolve_pubchem_names,
    resolve_records,
    utc_now,
    write_benchmark,
    write_rejections,
)


AUTHORITIES = {"IRAC", "FRAC", "HRAC"}
REQUIRED_COLUMNS = {"authority", "source_record_id", "active_ingredient", "moa_group"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Authorized CSV or TSV using the documented schema")
    parser.add_argument("--output-dir", type=Path, default=SCRIPT_DIR / "restricted" / "generated")
    parser.add_argument(
        "--redistribution-authorized",
        action="store_true",
        help="Permit output outside the ignored restricted/generated directory",
    )
    parser.add_argument("--analyze", action="store_true")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    ignored_root = (SCRIPT_DIR / "restricted" / "generated").resolve()
    if output_dir != ignored_root and not args.redistribution_authorized:
        parser.error("output outside restricted/generated requires --redistribution-authorized")

    table = pd.read_csv(args.input, sep=None, engine="python", dtype=object)
    table.columns = [clean_text(column).lower() for column in table.columns]
    missing = REQUIRED_COLUMNS - set(table.columns)
    if missing:
        parser.error(f"missing required columns: {', '.join(sorted(missing))}")

    records_by_authority = {authority: [] for authority in AUTHORITIES}
    rejected = []
    for index, row in table.iterrows():
        authority = clean_text(row.get("authority")).upper()
        source_id = clean_text(row.get("source_record_id")) or f"ROW-{index + 1:04d}"
        name = clean_text(row.get("active_ingredient"))
        group = clean_text(row.get("moa_group"))
        if authority not in AUTHORITIES or not name or not group:
            rejected.append(rejection(authority or "UNKNOWN", source_id, name, "invalid_required_value"))
            continue
        metadata = {}
        for source_column, property_suffix in (
            ("target_site", "TargetSite"),
            ("chemical_class", "ChemicalClass"),
            ("source_url", "SourceURL"),
        ):
            value = clean_text(row.get(source_column))
            if value:
                metadata[f"{authority}_{property_suffix}"] = value
        records_by_authority[authority].append(
            SourceRecord(
                authority,
                clean_text(row.get("source_version")) or "unspecified",
                source_id,
                name,
                {f"{authority}_Group": group},
                metadata,
                source_smiles=clean_text(row.get("smiles")) or None,
                source_pubchem_cid=clean_text(row.get("pubchem_cid")) or None,
            )
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    cache_path = output_dir / "data" / "pubchem_name_resolution.json"
    names = [
        record.name
        for authority_records in records_by_authority.values()
        for record in authority_records
        if not record.source_smiles
    ]
    cache = resolve_pubchem_names(names, cache_path)
    generated = {}
    for authority in sorted(AUTHORITIES):
        resolved, resolution_rejections = resolve_records(records_by_authority[authority], cache)
        rejected.extend(resolution_rejections)
        benchmark_name = f"{authority.lower()}_moa"
        target = f"{authority}_Group"
        rows, benchmark_rejections = materialize_benchmark(benchmark_name, resolved, target)
        rejected.extend(benchmark_rejections)
        if not rows:
            continue
        generated[benchmark_name] = {
            **write_benchmark(output_dir, benchmark_name, rows),
            "targetProperty": target,
            "classBalance": class_balance(rows, target),
        }
        if args.analyze:
            analyze_sdf(output_dir, benchmark_name)

    write_rejections(output_dir / "data" / "rac_unresolved.tab", rejected)
    atomic_json(
        output_dir / "rac.provenance.json",
        {
            "schemaVersion": 1,
            "generatedAt": utc_now(),
            "input": str(args.input.resolve()),
            "redistributionAuthorized": args.redistribution_authorized,
            "benchmarks": generated,
        },
    )
    print(json.dumps(generated, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
