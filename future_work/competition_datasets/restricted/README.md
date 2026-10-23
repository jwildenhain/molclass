# Permission-controlled crop-protection sources

IRAC, FRAC, and HRAC publish authoritative mode-of-action classifications, but their current
terms do not grant unrestricted redistribution of transformed datasets. Do not commit downloaded
committee tables or derived SDFs here without written permission covering the intended use.

After permission is recorded, prepare a CSV or TSV with these required columns:

| column | meaning |
|---|---|
| `authority` | `IRAC`, `FRAC`, or `HRAC` |
| `source_record_id` | Stable identifier from the authorized source |
| `active_ingredient` | ISO/common active-ingredient name |
| `moa_group` | Exact committee mode-of-action group |

Optional columns are `source_version`, `target_site`, `chemical_class`, `source_url`, `smiles`,
and `pubchem_cid`. A supplied structure takes precedence; otherwise the adapter performs an
audited PubChem name resolution.

Run a local, ignored build:

```bash
python3 ../build_rac_from_licensed_source.py authorized_rac_table.csv
```

Only use `--redistribution-authorized --output-dir ..` when written permission permits committing
the derived artifacts. These are multiclass MoA datasets among known active ingredients; they do
not contain inactive controls and must not be described as pesticide-activity classifiers.
