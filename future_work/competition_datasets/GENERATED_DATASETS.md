# Generated safety benchmarks

Generated at `2026-10-22T16:10:41.773624Z` from the official FDA releases recorded in 
[`sources.json`](sources.json). Structures were resolved through PubChem and standardized 
using `RDKit Cleanup and FragmentParent; retain stereochemistry`. Unresolved, ambiguous, and label-conflicting rows are retained in 
[`data/fda_safety_unresolved.tab`](data/fda_safety_unresolved.tab).

| benchmark | target | records | class balance |
|---|---|---:|---|
| `fda_dilirank2_multiclass` | four-level DILI concern (`DILIrank_Class`) | 1,177 | AMBIGUOUS: 319, LESS: 324, MOST: 205, NO: 329 |
| `fda_dilirank2_binary` | DILI concern versus no concern (`DILIrank_Binary`) | 863 | 0: 332, 1: 531 |
| `fda_dilist` | binary human hepatotoxicity (`DILIst`) | 1,151 | 0: 431, 1: 720 |
| `fda_dictrank_multiclass` | four-level cardiotoxicity concern (`DICTrank_Class`) | 1,070 | AMBIGUOUS: 86, LESS: 436, MOST: 270, NO: 278 |
| `fda_dictrank_binary` | cardiotoxicity concern versus no concern (`DICTrank_Binary`) | 997 | 0: 281, 1: 716 |
| `fda_diril` | binary drug-induced renal injury (`DIRI`) | 317 | 0: 146, 1: 171 |

## Benchmark policy

- Binary rank datasets exclude the source `AMBIGUOUS` class rather than treating it as inactive.
- Duplicate source rows are merged only when their standardized parent structure and target agree.
- Conflicting labels for one standardized parent structure are quarantined, never majority-voted.
- Structures with multiple PubChem parent candidates, no carbon, or more than 250 heavy atoms are quarantined.
- Source row IDs, source versions, PubChem CIDs, and standardized InChIKeys remain SDF properties.
