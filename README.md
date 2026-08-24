# MolClass

MolClass builds and serves machine learning models that predict small-molecule
bioactivity — training supervised classifiers on your own SDF datasets, then
searching, browsing, and predicting against them through a modern web UI, REST
API, or MCP tools for agentic/programmatic workflows.

Give it a labeled set of molecules (active/inactive, or any categorical
property), and it computes chemical descriptors and fingerprints, trains and
evaluates a classifier, and puts the result behind a human approval gate before
it's used for predictions. Everything after that — searching your compound
library, predicting on new molecules, reviewing model evidence — is available
through the web app, the REST API, or directly from an AI assistant via the
bundled MCP server.

## Why it's worth a look

- **Benchmarked, not just built.** A default RandomForest-on-JUMBO
  configuration matches DeepTox — the winning method of the [Tox21 Data
  Challenge](https://tripod.nih.gov/tox21/challenge/) — without any
  deep-learning infrastructure. See [Details](molclass-frontend/src/app/details/page.tsx)
  in the app, or `/news`, for the writeup.
- **A real chemistry pipeline, not a thin wrapper.** Descriptor and
  fingerprint generation runs on the [Chemistry Development Kit
  (CDK)](https://cdk.github.io/) — 200+ molecular descriptors plus 8
  fingerprint families (MACCS, PubChem, Extended, Substructure, Klekota-Roth,
  Graph-only, EState, ECFP4) — computed deterministically and versioned per
  molecule, not recomputed ad hoc on every request.
- **Eleven classifiers, two feature-selection strategies**, via
  [Weka](https://www.cs.waikato.ac.nz/ml/weka/): Random Forest, J48, Naive
  Bayes, SMO, k-NN, LibSVM, Logistic Model Tree, LogitBoost, a stacked
  ensemble, Bagged J48, and AdaBoost.M1 — with correlation-based (CFS) or
  ReliefF feature selection, or none.
- **A human release gate, not a training-equals-deploy pipeline.** Every
  model build is immutable evidence (holdout/validation/train metrics,
  manifest hash, artifact checksums) that a named reviewer must explicitly
  approve — recorded via a canonical, audited transaction — before it's ever
  used for a prediction.
- **Structure search that's actually structure-aware**: exact match and
  substructure search by SMILES, not just text matching on names.
- **Talk to it from an AI assistant.** [`mcp-server/`](mcp-server/) exposes
  search and prediction as MCP tools, including registering a brand-new
  molecule from a raw SMILES string and predicting on it in one call — useful
  for wiring MolClass into an agent workflow instead of a browser.

## Architecture

A modern, containerized stack — six Docker Compose services:

| Service | What it does |
|---|---|
| `frontend` | Next.js / React web UI |
| `api` | FastAPI REST service — datasets, uploads, model definitions, review |
| `sdf-worker` | Imports and analyzes uploaded SDF files (Java, CDK) |
| `model-worker` | Trains and evaluates models (Java, CDK + Weka) |
| `molecule-worker` | Registers ad-hoc molecules and runs one-off predictions |
| `predictor` | Spring Boot service serving predictions and structure search |
| `db` | MariaDB |

Chemistry and machine learning run on the JVM (CDK + Weka); the API and web
layers are Python (FastAPI) and TypeScript (Next.js). Everything is wired
together through a versioned job queue, so long-running work (import, feature
generation, model training) is resumable and auditable rather than a
fire-and-forget background thread.

## Getting started

See [INSTALL.md](INSTALL.md) for the full guide. The short version:

```bash
git clone https://github.com/jwildenhain/molclass.git
cd molclass
cp .env.example .env   # fill in MOLCLASS_DB_ROOT_PASSWORD and MOLCLASS_DB_PASSWORD
docker compose build
docker compose up -d
```

Then open `http://127.0.0.1:3000`.

## Citation

If you use MolClass in your work, please cite:

> Wildenhain J, Fitzgerald N, Tyers M. *Bioinformatics.* 2012 Aug
> 15;28(16):2200-1.

## Contact

Jan Wildenhain — [LinkedIn](https://www.linkedin.com/in/jan-wildenhain-39908610/)
