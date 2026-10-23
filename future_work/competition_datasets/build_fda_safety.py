#!/usr/bin/env python3
"""Build model-ready FDA safety benchmarks with audited PubChem resolution."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import tempfile
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from urllib.parse import quote

import pandas as pd
import requests
from rdkit import Chem
from rdkit.Chem import AllChem
from rdkit.Chem.MolStandardize import rdMolStandardize


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
PUBCHEM_PROPERTY_URL = (
    "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/{name}/property/"
    "Title,CanonicalSMILES,IsomericSMILES,InChI,InChIKey/JSON"
)
USER_AGENT = "MolClass benchmark builder/1.0 (public scientific data curation)"
STRUCTURE_POLICY = "RDKit Cleanup and FragmentParent; retain stereochemistry"

SOURCES = {
    "dilirank2": {
        "url": "https://www.fda.gov/media/113052/download?attachment=",
        "version": "2.0 (2025)",
        "filename": "dilirank2.xlsx",
        "landing_page": (
            "https://www.fda.gov/science-research/liver-toxicity-knowledge-base-ltkb/"
            "drug-induced-liver-injury-rank-dilirank-20-dataset"
        ),
    },
    "dilist": {
        "url": "https://www.fda.gov/media/160597/download?attachment=",
        "version": "2020 release",
        "filename": "dilist.xlsx",
        "landing_page": (
            "https://www.fda.gov/science-research/liver-toxicity-knowledge-base-ltkb/"
            "drug-induced-liver-injury-severity-and-toxicity-dilist-dataset"
        ),
    },
    "dictrank": {
        "url": "https://www.fda.gov/media/178811/download?attachment=",
        "version": "2023 release",
        "filename": "dictrank.xlsx",
        "landing_page": (
            "https://www.fda.gov/science-research/bioinformatics-tools/"
            "drug-induced-cardiotoxicity-rank-dictrank-dataset"
        ),
    },
    "diril": {
        "url": "https://www.fda.gov/media/178824/download?attachment=",
        "version": "2024 release",
        "filename": "diril.xlsx",
        "landing_page": (
            "https://www.fda.gov/science-research/bioinformatics-tools/"
            "drug-induced-renal-injury-list-diril-dataset"
        ),
    },
}


@dataclass
class SourceRecord:
    source_dataset: str
    source_version: str
    source_record_id: str
    name: str
    targets: Dict[str, Optional[str]]
    metadata: Dict[str, str] = field(default_factory=dict)
    source_smiles: Optional[str] = None
    source_pubchem_cid: Optional[str] = None


@dataclass
class ResolvedRecord:
    source: SourceRecord
    canonical_smiles: str
    inchikey: str
    pubchem_cid: str


class RequestRateLimiter:
    def __init__(self, requests_per_second: float = 4.0) -> None:
        self.interval = 1.0 / requests_per_second
        self.lock = threading.Lock()
        self.next_request = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            delay = max(0.0, self.next_request - now)
            self.next_request = max(now, self.next_request) + self.interval
        if delay:
            time.sleep(delay)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def clean_text(value: Any) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    text = str(value).strip()
    if text.lower() in {"nan", "none", "null"}:
        return ""
    return re.sub(r"\s+", " ", text)


def clean_identifier(value: Any) -> str:
    text = clean_text(value)
    if re.fullmatch(r"\d+\.0", text):
        return text[:-2]
    return text


def normalize_key(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip()).casefold()


def normalize_concern(value: Any, prefix: str) -> Optional[str]:
    text = clean_text(value).lower().replace("_", " ")
    compact = re.sub(r"[^a-z]+", "", text)
    if "ambiguous" in compact:
        return "AMBIGUOUS"
    if "most" in compact:
        return "MOST"
    if "less" in compact:
        return "LESS"
    if compact in {"no", f"no{prefix.lower()}concern", f"vno{prefix.lower()}concern", "noconcern"}:
        return "NO"
    return None


def normalize_binary(value: Any) -> Optional[str]:
    text = clean_text(value)
    if not text:
        return None
    try:
        numeric = int(float(text))
    except ValueError:
        return None
    return str(numeric) if numeric in {0, 1} else None


def normalize_diril(value: Any) -> Optional[str]:
    compact = re.sub(r"[^a-z]+", "", clean_text(value).lower())
    if compact in {"nonnephrotoxic", "negative", "nontoxic"}:
        return "0"
    if compact in {"nephrotoxic", "positive", "toxic"}:
        return "1"
    return None


def atomic_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_sources(directory: Path) -> Tuple[Dict[str, Path], Dict[str, Dict[str, Any]]]:
    paths: Dict[str, Path] = {}
    provenance: Dict[str, Dict[str, Any]] = {}
    for source_name, source in SOURCES.items():
        path = directory / source["filename"]
        print(f"Downloading {source_name} from FDA", flush=True)
        with requests.get(
            source["url"], headers={"User-Agent": USER_AGENT}, stream=True, timeout=180
        ) as response:
            response.raise_for_status()
            with path.open("wb") as output:
                for block in response.iter_content(chunk_size=1024 * 1024):
                    if block:
                        output.write(block)
        paths[source_name] = path
        provenance[source_name] = {
            **source,
            "downloadedAt": utc_now(),
            "contentLength": path.stat().st_size,
            "sha256": sha256_file(path),
        }
    return paths, provenance


def selected_metadata(row: pd.Series, mapping: Dict[str, str]) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for source_column, property_name in mapping.items():
        if source_column in row.index:
            value = clean_text(row[source_column])
            if value:
                result[property_name] = value
    return result


def parse_sources(paths: Dict[str, Path]) -> Tuple[Dict[str, List[SourceRecord]], List[Dict[str, str]]]:
    parsed: Dict[str, List[SourceRecord]] = defaultdict(list)
    rejected: List[Dict[str, str]] = []

    dilirank = pd.read_excel(paths["dilirank2"], sheet_name="version 2", header=1, dtype=object)
    dilirank.columns = [clean_text(column) for column in dilirank.columns]
    for index, row in dilirank.iterrows():
        source_id = clean_identifier(row.get("LTKBID")) or f"ROW-{index + 1:04d}"
        name = clean_text(row.get("CompoundName"))
        concern = normalize_concern(row.get("vDILI-Concern"), "DILI")
        if not name or concern is None:
            rejected.append(rejection("DILIrank2", source_id, name, "missing_name_or_label"))
            continue
        parsed["dilirank2"].append(
            SourceRecord(
                "DILIrank2",
                SOURCES["dilirank2"]["version"],
                source_id,
                name,
                {
                    "DILIrank_Class": concern,
                    "DILIrank_Binary": "0" if concern == "NO" else "1" if concern in {"MOST", "LESS"} else None,
                },
                selected_metadata(
                    row,
                    {
                        "SeverityClass": "DILI_SeverityClass",
                        "LabelSection": "DILI_LabelSection",
                        "Comment": "DILI_SourceComment",
                    },
                ),
            )
        )

    dilist = pd.read_excel(paths["dilist"], sheet_name="DILIst", dtype=object)
    dilist.columns = [clean_text(column) for column in dilist.columns]
    for index, row in dilist.iterrows():
        source_id = clean_identifier(row.get("DILIST_ID")) or f"ROW-{index + 1:04d}"
        name = clean_text(row.get("CompoundName"))
        target = normalize_binary(row.get("DILIst Classification"))
        if not name or target is None:
            rejected.append(rejection("DILIst", source_id, name, "missing_name_or_label"))
            continue
        parsed["dilist"].append(
            SourceRecord(
                "DILIst",
                SOURCES["dilist"]["version"],
                source_id,
                name,
                {"DILIst": target},
                selected_metadata(row, {"Routs of Administration": "RoutesOfAdministration"}),
            )
        )

    dictrank = pd.read_excel(paths["dictrank"], sheet_name="Table S1", dtype=object)
    dictrank.columns = [clean_text(column) for column in dictrank.columns]
    for index, row in dictrank.iterrows():
        source_id = f"DICT-{index + 1:04d}"
        name = clean_text(row.get("Active Ingredient(s)"))
        concern = normalize_concern(row.get("DICT _ Concern"), "DICT")
        if not name or concern is None:
            rejected.append(rejection("DICTrank", source_id, name, "missing_name_or_label"))
            continue
        parsed["dictrank"].append(
            SourceRecord(
                "DICTrank",
                SOURCES["dictrank"]["version"],
                source_id,
                name,
                {
                    "DICTrank_Class": concern,
                    "DICTrank_Binary": "0" if concern == "NO" else "1" if concern in {"MOST", "LESS"} else None,
                },
                selected_metadata(
                    row,
                    {
                        "Trade Name": "DICT_TradeName",
                        "Generic/Proper Name(s)": "DICT_GenericName",
                        "Cardiotoxicity": "DICT_Cardiotoxicity",
                        "Label Section": "DICT_LabelSection",
                        "DIC Severity Level": "DICT_SeverityLevel",
                    },
                ),
            )
        )

    wanted_diril_columns = {
        "PubChem_CID",
        "drugbank_id",
        "name",
        "inchikey",
        "smiles",
        "Label_Gong",
        "Label_Shi",
        "My Findings (Toxicity)",
        "2023 ATC Search Name",
        "2023 ATC level name",
    }
    diril = pd.read_excel(
        paths["diril"],
        sheet_name="A. DIRIL (317)",
        dtype=object,
        usecols=lambda column: clean_text(column) in wanted_diril_columns,
    )
    diril.columns = [clean_text(column) for column in diril.columns]
    for index, row in diril.iterrows():
        cid = clean_identifier(row.get("PubChem_CID"))
        source_id = f"CID-{cid}" if cid else f"DIRIL-{index + 1:04d}"
        name = clean_text(row.get("name"))
        target = normalize_diril(row.get("My Findings (Toxicity)"))
        smiles = clean_text(row.get("smiles"))
        if not name or target is None or not smiles:
            rejected.append(rejection("DIRIL", source_id, name, "missing_name_label_or_structure"))
            continue
        parsed["diril"].append(
            SourceRecord(
                "DIRIL",
                SOURCES["diril"]["version"],
                source_id,
                name,
                {"DIRI": target},
                selected_metadata(
                    row,
                    {
                        "drugbank_id": "DrugBankID",
                        "Label_Gong": "DIRI_LabelGong",
                        "Label_Shi": "DIRI_LabelShi",
                        "2023 ATC Search Name": "ATC_SearchName",
                        "2023 ATC level name": "ATC_LevelName",
                    },
                ),
                source_smiles=smiles,
                source_pubchem_cid=cid,
            )
        )
    return parsed, rejected


def rejection(dataset: str, source_id: str, name: str, reason: str, details: str = "") -> Dict[str, str]:
    return {
        "SourceDataset": dataset,
        "SourceRecordID": source_id,
        "MoleculeName": name,
        "Reason": reason,
        "Details": details,
    }


def load_resolution_cache(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {"schemaVersion": 1, "records": {}}
    loaded = json.loads(path.read_text(encoding="utf-8"))
    if loaded.get("schemaVersion") != 1 or not isinstance(loaded.get("records"), dict):
        raise ValueError(f"Unsupported PubChem cache schema in {path}")
    return loaded


def request_pubchem(query: str, limiter: RequestRateLimiter) -> Dict[str, Any]:
    url = PUBCHEM_PROPERTY_URL.format(name=quote(query, safe=""))
    for attempt in range(6):
        limiter.wait()
        try:
            response = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=60)
        except requests.RequestException as error:
            if attempt == 5:
                return {"status": "error", "query": query, "error": str(error)}
            time.sleep(2**attempt)
            continue
        if response.status_code == 404:
            return {"status": "not_found", "query": query}
        if response.status_code in {429, 500, 502, 503, 504}:
            if attempt == 5:
                return {
                    "status": "error",
                    "query": query,
                    "error": f"PubChem HTTP {response.status_code}",
                }
            time.sleep(2**attempt)
            continue
        try:
            response.raise_for_status()
            properties = response.json()["PropertyTable"]["Properties"]
        except (requests.RequestException, KeyError, ValueError) as error:
            return {"status": "error", "query": query, "error": str(error)}
        candidates = []
        for item in properties:
            smiles = (
                item.get("IsomericSMILES")
                or item.get("SMILES")
                or item.get("CanonicalSMILES")
                or item.get("ConnectivitySMILES")
            )
            if smiles:
                candidates.append(
                    {
                        "cid": str(item.get("CID", "")),
                        "title": clean_text(item.get("Title")),
                        "smiles": smiles,
                        "inchi": clean_text(item.get("InChI")),
                        "inchikey": clean_text(item.get("InChIKey")),
                    }
                )
        return {
            "status": "resolved" if candidates else "not_found",
            "query": query,
            "candidates": candidates,
            "retrievedAt": utc_now(),
        }
    raise AssertionError("unreachable")


def resolve_pubchem_names(names: Iterable[str], cache_path: Path) -> Dict[str, Any]:
    cache = load_resolution_cache(cache_path)
    records = cache["records"]
    unique_names = {normalize_key(name): name for name in names if clean_text(name)}
    missing = [
        (key, query)
        for key, query in sorted(unique_names.items())
        if key not in records or records[key].get("status") == "error"
    ]
    if not missing:
        return cache
    limiter = RequestRateLimiter(4.0)
    completed = 0
    print(f"Resolving {len(missing)} unique FDA names through PubChem", flush=True)
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(request_pubchem, query, limiter): key for key, query in missing}
        for future in as_completed(futures):
            key = futures[future]
            records[key] = future.result()
            completed += 1
            if completed % 50 == 0 or completed == len(missing):
                cache["updatedAt"] = utc_now()
                atomic_json(cache_path, cache)
                print(f"PubChem resolution {completed}/{len(missing)}", flush=True)
    return cache


def standardize_smiles(smiles: str) -> Tuple[Optional[Chem.Mol], str, str, str]:
    try:
        molecule = Chem.MolFromSmiles(smiles)
        if molecule is None:
            return None, "", "", "RDKit could not parse SMILES"
        molecule = rdMolStandardize.Cleanup(molecule)
        molecule = rdMolStandardize.FragmentParent(molecule)
        Chem.SanitizeMol(molecule)
        if not any(atom.GetAtomicNum() == 6 for atom in molecule.GetAtoms()):
            return None, "", "", "structure has no carbon atom"
        if molecule.GetNumHeavyAtoms() > 250:
            return None, "", "", "structure exceeds 250 heavy atoms"
        canonical = Chem.MolToSmiles(molecule, canonical=True, isomericSmiles=True)
        inchikey = Chem.MolToInchiKey(molecule)
        if not inchikey:
            return None, "", "", "RDKit could not generate InChIKey"
        return molecule, canonical, inchikey, ""
    except Exception as error:  # RDKit exposes several C++ exception wrappers.
        return None, "", "", f"RDKit standardization failed: {error}"


def resolve_records(
    source_records: Sequence[SourceRecord], cache: Dict[str, Any]
) -> Tuple[List[ResolvedRecord], List[Dict[str, str]]]:
    resolved: List[ResolvedRecord] = []
    rejected: List[Dict[str, str]] = []
    cached_records = cache["records"]
    for source in source_records:
        if source.source_smiles:
            candidates = [{"smiles": source.source_smiles, "cid": source.source_pubchem_cid or ""}]
            resolution_status = "source_structure"
        else:
            cache_entry = cached_records.get(normalize_key(source.name), {})
            candidates = cache_entry.get("candidates", [])
            resolution_status = cache_entry.get("status", "not_found")
        standardized: Dict[str, Tuple[str, str]] = {}
        errors: List[str] = []
        for candidate in candidates:
            _, canonical, inchikey, error = standardize_smiles(clean_text(candidate.get("smiles")))
            if error:
                errors.append(error)
                continue
            standardized.setdefault(inchikey, (canonical, clean_identifier(candidate.get("cid"))))
        if not standardized:
            detail = "; ".join(sorted(set(errors))) or resolution_status
            rejected.append(
                rejection(
                    source.source_dataset,
                    source.source_record_id,
                    source.name,
                    "structure_unresolved",
                    detail,
                )
            )
            continue
        if len(standardized) > 1:
            rejected.append(
                rejection(
                    source.source_dataset,
                    source.source_record_id,
                    source.name,
                    "ambiguous_pubchem_structure",
                    ";".join(sorted(standardized)),
                )
            )
            continue
        inchikey, (canonical, cid) = next(iter(standardized.items()))
        resolved.append(ResolvedRecord(source, canonical, inchikey, cid))
    return resolved, rejected


def merge_values(records: Sequence[ResolvedRecord], attribute: str) -> str:
    values = sorted(
        {
            clean_text(record.source.metadata.get(attribute))
            for record in records
            if clean_text(record.source.metadata.get(attribute))
        }
    )
    return " | ".join(values)


def materialize_benchmark(
    benchmark_name: str,
    records: Sequence[ResolvedRecord],
    target_property: str,
) -> Tuple[List[Dict[str, str]], List[Dict[str, str]]]:
    eligible = [record for record in records if record.source.targets.get(target_property) is not None]
    grouped: Dict[str, List[ResolvedRecord]] = defaultdict(list)
    for record in eligible:
        grouped[record.inchikey].append(record)
    output: List[Dict[str, str]] = []
    rejected: List[Dict[str, str]] = []
    for inchikey, members in sorted(grouped.items()):
        labels = {member.source.targets[target_property] for member in members}
        if len(labels) != 1:
            for member in members:
                rejected.append(
                    rejection(
                        member.source.source_dataset,
                        member.source.source_record_id,
                        member.source.name,
                        "conflicting_labels_after_structure_deduplication",
                        f"{benchmark_name}:{','.join(sorted(str(label) for label in labels))}",
                    )
                )
            continue
        ordered = sorted(members, key=lambda item: item.source.source_record_id)
        first = ordered[0]
        source_ids = [member.source.source_record_id for member in ordered]
        names = sorted({member.source.name for member in ordered})
        cids = sorted({member.pubchem_cid for member in ordered if member.pubchem_cid})
        row: Dict[str, str] = {
            "Identifier": f"{benchmark_name.upper()}:{inchikey}",
            "MoleculeName": " | ".join(names),
            "CanonicalSMILES": first.canonical_smiles,
            "InChIKey": inchikey,
            "PubChemCID": ";".join(cids),
            "SourceDataset": first.source.source_dataset,
            "SourceVersion": first.source.source_version,
            "SourceRecordIDs": ";".join(source_ids),
            "StructureStandardization": STRUCTURE_POLICY,
            target_property: str(next(iter(labels))),
        }
        metadata_names = sorted({name for member in ordered for name in member.source.metadata})
        for metadata_name in metadata_names:
            merged = merge_values(ordered, metadata_name)
            if merged:
                row[metadata_name] = merged
        output.append(row)
    return output, rejected


def write_benchmark(output_dir: Path, name: str, rows: Sequence[Dict[str, str]]) -> Dict[str, Any]:
    if not rows:
        raise ValueError(f"No records remained for {name}")
    output_dir.mkdir(parents=True, exist_ok=True)
    data_dir = output_dir / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    tab_path = data_dir / f"{name}.tab"
    sdf_path = output_dir / f"{name}.sdf"
    columns = [
        "Identifier",
        "MoleculeName",
        "CanonicalSMILES",
        "InChIKey",
        "PubChemCID",
        "SourceDataset",
        "SourceVersion",
        "SourceRecordIDs",
        "StructureStandardization",
    ]
    columns.extend(sorted({column for row in rows for column in row if column not in columns}))
    pd.DataFrame(rows, columns=columns).to_csv(tab_path, sep="\t", index=False)

    writer = Chem.SDWriter(str(sdf_path))
    try:
        for row in rows:
            molecule = Chem.MolFromSmiles(row["CanonicalSMILES"])
            if molecule is None:
                raise ValueError(f"Generated canonical SMILES failed to parse: {row['Identifier']}")
            AllChem.Compute2DCoords(molecule)
            molecule.SetProp("_Name", row["Identifier"])
            for key in columns:
                value = clean_text(row.get(key))
                if value:
                    molecule.SetProp(key, value)
            writer.write(molecule)
    finally:
        writer.close()
    return {
        "records": len(rows),
        "tab": str(tab_path.relative_to(output_dir)),
        "sdf": str(sdf_path.relative_to(output_dir)),
        "sdfSha256": sha256_file(sdf_path),
        "sdfBytes": sdf_path.stat().st_size,
    }


def analyze_sdf(output_dir: Path, name: str) -> None:
    sdf_path = (output_dir / f"{name}.sdf").resolve()
    analysis_path = (output_dir / f"{name}.analysis.json").resolve()
    arguments = f"analyze --sdf {sdf_path} --output {analysis_path}"
    print(f"Analyzing {name} with MolClass SdfAnalyzer", flush=True)
    subprocess.run(
        ["./gradlew", "--console=plain", "analyzeV3Sdf", f"-PimportArgs={arguments}"],
        cwd=REPOSITORY_ROOT,
        check=True,
    )


def write_rejections(path: Path, rejected: Sequence[Dict[str, str]]) -> None:
    columns = ["SourceDataset", "SourceRecordID", "MoleculeName", "Reason", "Details"]
    pd.DataFrame(rejected, columns=columns).drop_duplicates().to_csv(path, sep="\t", index=False)


def class_balance(rows: Sequence[Dict[str, str]], target: str) -> Dict[str, int]:
    return dict(sorted(Counter(row[target] for row in rows).items()))


def write_generated_summary(
    output_dir: Path,
    benchmark_results: Sequence[Tuple[str, str, str, Sequence[Dict[str, str]]]],
    provenance: Dict[str, Any],
) -> None:
    lines = [
        "# Generated safety benchmarks",
        "",
        f"Generated at `{provenance['generatedAt']}` from the official FDA releases recorded in ",
        "[`sources.json`](sources.json). Structures were resolved through PubChem and standardized ",
        f"using `{STRUCTURE_POLICY}`. Unresolved, ambiguous, and label-conflicting rows are retained in ",
        "[`data/fda_safety_unresolved.tab`](data/fda_safety_unresolved.tab).",
        "",
        "| benchmark | target | records | class balance |",
        "|---|---|---:|---|",
    ]
    for name, target, description, rows in benchmark_results:
        balance = ", ".join(f"{label}: {count}" for label, count in class_balance(rows, target).items())
        lines.append(f"| `{name}` | {description} (`{target}`) | {len(rows):,} | {balance} |")
    lines.extend(
        [
            "",
            "## Benchmark policy",
            "",
            "- Binary rank datasets exclude the source `AMBIGUOUS` class rather than treating it as inactive.",
            "- Duplicate source rows are merged only when their standardized parent structure and target agree.",
            "- Conflicting labels for one standardized parent structure are quarantined, never majority-voted.",
            "- Structures with multiple PubChem parent candidates, no carbon, or more than 250 heavy atoms are quarantined.",
            "- Source row IDs, source versions, PubChem CIDs, and standardized InChIKeys remain SDF properties.",
            "",
        ]
    )
    (output_dir / "GENERATED_DATASETS.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=SCRIPT_DIR)
    parser.add_argument("--skip-analysis", action="store_true")
    args = parser.parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    cache_path = output_dir / "data" / "pubchem_name_resolution.json"
    cache_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="molclass-fda-safety-") as temporary:
        paths, source_provenance = download_sources(Path(temporary))
        parsed, rejected = parse_sources(paths)

    pubchem_names = [
        record.name
        for records in parsed.values()
        for record in records
        if not record.source_smiles
    ]
    cache = resolve_pubchem_names(pubchem_names, cache_path)

    resolved_by_source: Dict[str, List[ResolvedRecord]] = {}
    for source_name, records in parsed.items():
        resolved, source_rejections = resolve_records(records, cache)
        resolved_by_source[source_name] = resolved
        rejected.extend(source_rejections)
        print(f"{source_name}: source={len(records)} structure_resolved={len(resolved)}", flush=True)

    definitions = [
        ("fda_dilirank2_multiclass", "dilirank2", "DILIrank_Class", "four-level DILI concern"),
        ("fda_dilirank2_binary", "dilirank2", "DILIrank_Binary", "DILI concern versus no concern"),
        ("fda_dilist", "dilist", "DILIst", "binary human hepatotoxicity"),
        ("fda_dictrank_multiclass", "dictrank", "DICTrank_Class", "four-level cardiotoxicity concern"),
        ("fda_dictrank_binary", "dictrank", "DICTrank_Binary", "cardiotoxicity concern versus no concern"),
        ("fda_diril", "diril", "DIRI", "binary drug-induced renal injury"),
    ]
    benchmark_results: List[Tuple[str, str, str, Sequence[Dict[str, str]]]] = []
    generated: Dict[str, Any] = {}
    for name, source_name, target, description in definitions:
        rows, benchmark_rejections = materialize_benchmark(name, resolved_by_source[source_name], target)
        rejected.extend(benchmark_rejections)
        generated[name] = {
            **write_benchmark(output_dir, name, rows),
            "targetProperty": target,
            "description": description,
            "classBalance": class_balance(rows, target),
        }
        benchmark_results.append((name, target, description, rows))

    rejected_path = output_dir / "data" / "fda_safety_unresolved.tab"
    write_rejections(rejected_path, rejected)
    rejection_balance = dict(sorted(Counter(row["Reason"] for row in rejected).items()))
    provenance = {
        "schemaVersion": 1,
        "generatedAt": utc_now(),
        "builder": Path(__file__).name,
        "structurePolicy": STRUCTURE_POLICY,
        "sources": source_provenance,
        "benchmarks": generated,
        "rejections": {
            "file": str(rejected_path.relative_to(output_dir)),
            "records": len(pd.DataFrame(rejected).drop_duplicates()),
            "byReason": rejection_balance,
        },
        "pubchem": {
            "service": "PUG REST",
            "cache": str(cache_path.relative_to(output_dir)),
            "uniqueNameQueries": len(cache.get("records", {})),
        },
    }
    atomic_json(output_dir / "fda_safety.provenance.json", provenance)
    write_generated_summary(output_dir, benchmark_results, provenance)

    if not args.skip_analysis:
        for name, _, _, _ in benchmark_results:
            analyze_sdf(output_dir, name)
    print(json.dumps({"benchmarks": generated, "rejections": rejection_balance}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
