# MolClass v3 Weka model evaluation completeness audit

## Audit decision

**Overall result: FAIL for production approval readiness.**

The database has 115 latest builds in `AWAITING_APPROVAL`. All 115 have the required
evaluation rows, exact support counts, reconciled split membership, valid class metadata,
complete artifact metadata, matching manifest hashes, completed jobs, and no generation
or decision anomalies. However, nine builds contain 34 SQL `NULL` metric values. The
current Java approval gate checks metric codes and support counts but does not inspect
`metric_value`; the current production audit has the same value-validation blind spot.
Those nine builds can therefore pass the implemented approval completeness check despite
incomplete quantitative evidence.

The remaining 106 awaiting candidates pass every database-metadata check in this audit.
That is not an approval recommendation. Artifact BLOB payloads were deliberately not read,
their bytes and hashes were not verified, and aggregate metrics alone do not establish
scientific validity or production fitness.

## Snapshot and scope

| Item | Value |
|---|---|
| Snapshot UTC | `2026-08-17T12:00:23.297332Z` |
| MariaDB | `10.11.14-MariaDB-0ubuntu0.24.04.1` |
| Transaction | `REPEATABLE-READ`, `WITH CONSISTENT SNAPSHOT`, `READ ONLY` |
| Session time zone | `+00:00` |
| Schema | `molclass_v3` |
| Model definitions | 118 |
| Build generations | 135 |
| Evaluation rows | 2,112 |
| Artifact metadata rows | 236 |
| Artifact payload rows read | 0 |
| DDL, DML, rebuild, approval, or Git operations | 0 |

The audit selected artifact kind, format, media type, declared size, SHA-256 metadata
length, and timestamps only. It never selected `model_artifact.artifact_payload`, never
called `--verify-artifact-digests`, and never compared declared artifact size with BLOB
length.

## Authoritative contracts

The acceptance criteria were derived from:

- `src/molclass/models/V3ModelApproval.java`
- `src/molclass/audit/V3ProductionAudit.java`
- `src/molclass/models/V3ModelRebuilder.java`
- `docs/V3_MODEL_APPROVAL.md`
- `docs/V3_MODEL_REVIEW.md`
- `sql/v3/V1__molclass_v3_baseline.sql`
- `sql/v3/V4__model_rebuild_constraints.sql`
- `html/molclass/api/app/v3_model_reviews.py`

The implemented approval contract requires:

1. Build and definition status `AWAITING_APPROVAL`.
2. Generation label `v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1`.
3. A nonblank code revision other than `working-tree`.
4. A non-null JSON manifest whose stored SHA-256 matches its UTF-8 content.
5. Exact persisted `TRAIN`, `VALIDATION`, `HOLDOUT`, and `EXCLUDED` membership counts.
6. At least two persisted classes.
7. Six aggregate metric codes for each non-empty evaluation set:
   `ACCURACY`, `KAPPA`, `WEIGHTED_PRECISION`, `WEIGHTED_RECALL`,
   `WEIGHTED_F1`, and `WEIGHTED_AUC`.
8. Every metric row's support count equal to the corresponding build split count.
9. Exactly one `MODEL` and one `HEADER` artifact in
   `JAVA_SERIALIZATION_WEKA_3_8_7_GZIP` format.
10. Artifact bytes, declared sizes, and SHA-256 digests verified by streaming during the
    actual approval transaction.

This audit strengthens item 7 by requiring each aggregate `metric_value` to be non-null
and in its mathematical range. `KAPPA` must be in `[-1,1]`; all other mandatory
metrics must be in `[0,1]`. A bounded value is finite by construction.

## Pass/fail criteria and results

| Gate | Pass criterion | Result |
|---|---|---|
| Latest-build coverage | One audited latest row for every definition | PASS: 118/118 |
| Awaiting status alignment | Definition and latest build both awaiting | PASS: 115/115 |
| Metric sets | Exactly 18 distinct required aggregate set/code pairs | PASS: 115/115 |
| Metric support | Every support equals the matching split count | PASS: 115/115 |
| Metric values | Every required value non-null, finite, and in range | **FAIL: 106/115; 9 builds fail** |
| Split membership | Stored and actual T/V/H/X counts equal | PASS: 115/115 |
| Membership uniqueness | One membership row per molecule/build | PASS: 115/115 |
| Dataset reconciliation | T+V+H+X equals dataset molecule count | PASS: 115/115 |
| Membership metadata | Valid partitions, null folds, valid exclusion reasons, correct dataset | PASS: 115/115 |
| Class metadata | At least two distinct nonblank classes with positive support | PASS: 115/115 |
| Class support | Sum equals T+V+H non-excluded population | PASS: 115/115 |
| Artifact metadata | Exactly MODEL+HEADER, expected format/media, positive sizes, 32-byte hashes | PASS: 115/115 |
| Artifact bytes/digests | Stream payload and recompute length/SHA-256 | **SKIPPED by request** |
| Manifest | Valid JSON, 32-byte hash, recomputed hash matches | PASS: 115/115 |
| Provenance | Production label, accepted revision, expected split, complete lifecycle | PASS: 115/115 |
| Job state | Rebuild job completed and finished | PASS: 115/115 |
| Decisions | No decision already attached to awaiting build | PASS: 115/115 |
| Generation integrity | No duplicates, ties, gaps, stale awaiting builds, or pointer anomalies | PASS: 0 anomalies |
| Entire definition registry complete | No running, failed, or unbuilt definitions | **FAIL: 3 definitions unresolved** |

## Registry status

| Definition status | Count |
|---|---:|
| `AWAITING_APPROVAL` | 115 |
| `PENDING_REBUILD` | 1 |
| `REBUILD_FAILED` | 2 |
| **Total** | **118** |

| Latest build status | Count |
|---|---:|
| `AWAITING_APPROVAL` | 115 |
| `RUNNING` | 1 |
| `REBUILD_FAILED` | 1 |
| `NO_BUILD` | 1 |
| **Total** | **118** |

## Critical metric-value gaps

The six required rows exist for every split and every support count is correct. The
failure is specifically that Weka returned non-finite values which the rebuilder stored
as SQL `NULL`. There are no non-null out-of-range values.

| Definition | Build | Null count | Affected evidence |
|---:|---:|---:|---|
| 59 | 74 | 2 | HOLDOUT weighted precision and F1 |
| 61 | 76 | 4 | VALIDATION and HOLDOUT weighted precision and F1 |
| 64 | 79 | 6 | TRAIN, VALIDATION, and HOLDOUT weighted precision and F1 |
| 65 | 80 | 6 | TRAIN, VALIDATION, and HOLDOUT weighted precision and F1 |
| 68 | 83 | 2 | HOLDOUT weighted precision and F1 |
| 87 | 103 | 4 | VALIDATION and HOLDOUT weighted precision and F1 |
| 93 | 109 | 5 | VALIDATION AUC, precision, F1; HOLDOUT precision and F1 |
| 94 | 110 | 1 | VALIDATION weighted AUC |
| 98 | 5 | 4 | VALIDATION and HOLDOUT weighted precision and F1 |
| **Total** |  | **34** | **9 builds** |

Definition 93 has validation support 1 and holdout support 3. Definition 94 has validation
support 1 and holdout support 2. Definition 98 has validation and holdout support 3.
Those tiny partitions make several aggregate metrics undefined or statistically
uninformative even when a numeric value exists.

Exact invalid-value rows:

<!-- INVALID_METRIC_START -->
```text
definition	build	set	metric	value	support	expected	issue
59	74	HOLDOUT	WEIGHTED_F1	NULL	11	11	NULL
59	74	HOLDOUT	WEIGHTED_PRECISION	NULL	11	11	NULL
61	76	VALIDATION	WEIGHTED_F1	NULL	109	109	NULL
61	76	VALIDATION	WEIGHTED_PRECISION	NULL	109	109	NULL
61	76	HOLDOUT	WEIGHTED_F1	NULL	109	109	NULL
61	76	HOLDOUT	WEIGHTED_PRECISION	NULL	109	109	NULL
64	79	TRAIN	WEIGHTED_F1	NULL	884	884	NULL
64	79	TRAIN	WEIGHTED_PRECISION	NULL	884	884	NULL
64	79	VALIDATION	WEIGHTED_F1	NULL	109	109	NULL
64	79	VALIDATION	WEIGHTED_PRECISION	NULL	109	109	NULL
64	79	HOLDOUT	WEIGHTED_F1	NULL	109	109	NULL
64	79	HOLDOUT	WEIGHTED_PRECISION	NULL	109	109	NULL
65	80	TRAIN	WEIGHTED_F1	NULL	884	884	NULL
65	80	TRAIN	WEIGHTED_PRECISION	NULL	884	884	NULL
65	80	VALIDATION	WEIGHTED_F1	NULL	109	109	NULL
65	80	VALIDATION	WEIGHTED_PRECISION	NULL	109	109	NULL
65	80	HOLDOUT	WEIGHTED_F1	NULL	109	109	NULL
65	80	HOLDOUT	WEIGHTED_PRECISION	NULL	109	109	NULL
68	83	HOLDOUT	WEIGHTED_F1	NULL	174	174	NULL
68	83	HOLDOUT	WEIGHTED_PRECISION	NULL	174	174	NULL
87	103	VALIDATION	WEIGHTED_F1	NULL	59	59	NULL
87	103	VALIDATION	WEIGHTED_PRECISION	NULL	59	59	NULL
87	103	HOLDOUT	WEIGHTED_F1	NULL	59	59	NULL
87	103	HOLDOUT	WEIGHTED_PRECISION	NULL	59	59	NULL
93	109	VALIDATION	WEIGHTED_AUC	NULL	1	1	NULL
93	109	VALIDATION	WEIGHTED_F1	NULL	1	1	NULL
93	109	VALIDATION	WEIGHTED_PRECISION	NULL	1	1	NULL
93	109	HOLDOUT	WEIGHTED_F1	NULL	3	3	NULL
93	109	HOLDOUT	WEIGHTED_PRECISION	NULL	3	3	NULL
94	110	VALIDATION	WEIGHTED_AUC	NULL	1	1	NULL
98	5	VALIDATION	WEIGHTED_F1	NULL	3	3	NULL
98	5	VALIDATION	WEIGHTED_PRECISION	NULL	3	3	NULL
98	5	HOLDOUT	WEIGHTED_F1	NULL	3	3	NULL
98	5	HOLDOUT	WEIGHTED_PRECISION	NULL	3	3	NULL
```
<!-- INVALID_METRIC_END -->

## Metric distribution

Every row count is 115 and every support mismatch count is zero.

| Set | Metric | Null | Out of range | Minimum | Maximum | Support range |
|---|---|---:|---:|---:|---:|---|
| TRAIN | ACCURACY | 0 | 0 | 0.333710407239819 | 1 | 23..8343 |
| TRAIN | KAPPA | 0 | 0 | 0.12616409135841003 | 1 | 23..8343 |
| TRAIN | WEIGHTED_AUC | 0 | 0 | 0.5634694280829305 | 1 | 23..8343 |
| TRAIN | WEIGHTED_F1 | 2 | 0 | 0.33046646701037447 | 1 | 23..8343 |
| TRAIN | WEIGHTED_PRECISION | 2 | 0 | 0.4125824678883749 | 1 | 23..8343 |
| TRAIN | WEIGHTED_RECALL | 0 | 0 | 0.333710407239819 | 1 | 23..8343 |
| VALIDATION | ACCURACY | 0 | 0 | 0 | 1 | 1..1042 |
| VALIDATION | KAPPA | 0 | 0 | -0.5000000000000001 | 1 | 1..1042 |
| VALIDATION | WEIGHTED_AUC | 2 | 0 | 0 | 0.9845480891633488 | 1..1042 |
| VALIDATION | WEIGHTED_F1 | 6 | 0 | 0.2553544228954489 | 1 | 1..1042 |
| VALIDATION | WEIGHTED_PRECISION | 6 | 0 | 0.24737211996884914 | 1 | 1..1042 |
| VALIDATION | WEIGHTED_RECALL | 0 | 0 | 0 | 1 | 1..1042 |
| HOLDOUT | ACCURACY | 0 | 0 | 0.33027522935779813 | 1 | 2..1042 |
| HOLDOUT | KAPPA | 0 | 0 | -0.26229508196721313 | 1 | 2..1042 |
| HOLDOUT | WEIGHTED_AUC | 0 | 0 | 0.2166666666666667 | 1 | 2..1042 |
| HOLDOUT | WEIGHTED_F1 | 8 | 0 | 0.3292565232932205 | 1 | 2..1042 |
| HOLDOUT | WEIGHTED_PRECISION | 8 | 0 | 0.3322622923096436 | 1 | 2..1042 |
| HOLDOUT | WEIGHTED_RECALL | 0 | 0 | 0.3302752293577982 | 1 | 2..1042 |

A value of 0, 1, or a negative kappa is range-valid but is not necessarily acceptable.
Several candidates have perfect training metrics or very poor validation metrics. Neither
condition is an automatic approval or rejection rule without model-specific acceptance
criteria and scientific review.

## Algorithm, feature-selection, and profile distribution

### Algorithms

| Algorithm | Awaiting candidates |
|---|---:|
| BayesNet | 16 |
| DecisionTreeNaiveBayes | 1 |
| Ensemble | 41 |
| Ensemble2 | 2 |
| J48 | 2 |
| KNN | 1 |
| LMT | 1 |
| LibSVM | 6 |
| LibSVM2 | 2 |
| LogitBoost | 1 |
| NBTree | 1 |
| NaiveBayes | 8 |
| NeuralNet | 2 |
| RandomForest | 30 |
| SMO | 1 |
| **Total** | **115** |

### Feature selection

| Feature selection | Awaiting candidates |
|---|---:|
| CfsSubsetEval | 113 |
| ReliefFAttributeEval | 2 |
| **Total** | **115** |

### Feature profiles

| Profile | Awaiting candidates |
|---|---:|
| ALL | 19 |
| CDK | 14 |
| JUMBO | 33 |
| MCAT | 35 |
| PubChem | 5 |
| EXT | 2 |
| EXTGO | 2 |
| KR | 3 |
| MACCS | 2 |
| **Total** | **115** |

All 115 candidates share this provenance tuple:

```text
generation_label=v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1
java_version=21.0.11
cdk_version=2.12
weka_version=3.8.7
database_schema_version=V4
code_revision=local-production-candidate-20260814
split_strategy=STRATIFIED_HASH_80_10_10_V1
```

The current approval code accepts that code-revision label because it is nonblank and is
not exactly `working-tree`. Human reviewers should still require evidence that it maps to
an immutable, reproducible source revision.

## Split, class, artifact, manifest, and lifecycle reconciliation

| Evidence | Exact result |
|---|---|
| Required metric pairs | 115 complete, 0 incomplete/duplicate |
| Metric supports | 115 complete, 0 mismatched |
| Extra aggregate metrics | 0 builds |
| Fold-level or class-level metric rows | 0 builds |
| Partition counts | 115 exact, 0 mismatched |
| Duplicate members | 0 builds |
| Dataset-total mismatches | 0 builds |
| Membership metadata anomalies | 0 builds |
| Valid class metadata | 115 builds |
| Class support equals T+V+H | 115 builds |
| MODEL+HEADER metadata sets | 115 complete, 0 incomplete, 230 rows |
| Valid JSON manifests | 115 |
| Matching manifest SHA-256 values | 115 |
| Production generation labels | 115 |
| Nonempty T/V/H splits | 115 |
| Completed rebuild jobs | 115 |
| Awaiting builds with an approval decision | 0 |

Artifact metadata completeness does not prove artifact integrity. The actual approval CLI
must stream both BLOBs, verify exact byte counts, recompute both SHA-256 values, and perform
the decision in its locking transaction.

## Failed, held, and active definitions

| Definition | Legacy | Definition state | Latest build | Build/job state | Actionable gap |
|---:|---:|---|---:|---|---|
| 19 | 19 | `REBUILD_FAILED` | 135 generation 2 | Build `RUNNING`, job 142 `RUNNING`, runstep `TRAIN`; heartbeat `2026-08-17 11:28:42.039737`; no lease expiry | Do not review or approve. Confirm the worker outcome. The definition state and active build state are intentionally transitional but operationally ambiguous, and the job has no lease deadline. |
| 103 | 106 | `REBUILD_FAILED` | 118 generation 1 | Build/job failed | Weka supervised discretization raised `IllegalArgumentException: A duplicate bin range was detected`. Correct the robust discretization contract and rebuild. |
| 104 | 107 | `PENDING_REBUILD` | none | No job | Held because it uses the same CFS/JUMBO path as definition 103. Rebuild only after the discretization fix is approved and implemented. |

The snapshot does not prove whether the operating-system process for definition 19 was
alive. It proves only the database state at the timestamp above.

## Duplicate and stale-generation audit

All anomaly counts were zero:

| Anomaly | Count |
|---|---:|
| Duplicate `(definition, generation label, generation number)` keys | 0 |
| Generation-number ties | 0 |
| Stale older awaiting builds | 0 |
| Definitions with multiple awaiting builds | 0 |
| Latest awaiting/definition status mismatches | 0 |
| Invalid published pointers | 0 |
| Definitions with multiple published builds | 0 |
| Decisions attached to awaiting builds | 0 |
| Builds with duplicate decisions | 0 |
| Definitions with generation gaps | 0 |

There are no `PUBLISHED` or `ACTIVE` models in this snapshot. This is consistent with the
explicit human approval boundary and means prediction publication is still incomplete.

## Complete latest-definition matrix

`T/V/H/X` means training, validation, holdout, and excluded counts. `Metrics=FAIL` means
one or more required values are null or out of range; required codes and supports still
exist. Artifact status covers metadata only, never BLOB bytes.

<!-- FULL_MATRIX_START -->
```text
Def	Legacy	Dataset	Algorithm	Feature selection	Profile	Definition status	Build	Gen	Build status	T/V/H/X	Metrics	Membership	Artifact metadata	Manifest	Classes	Job
1	1	1	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	16	2	AWAITING_APPROVAL	1014/126/126/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
2	2	2	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	12	2	AWAITING_APPROVAL	82/9/9/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
3	3	3	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	17	2	AWAITING_APPROVAL	837/103/103/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
4	4	4	NaiveBayes	CfsSubsetEval	EXT	AWAITING_APPROVAL	14	3	AWAITING_APPROVAL	382/47/47/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
5	5	5	Ensemble2	CfsSubsetEval	MCAT	AWAITING_APPROVAL	134	3	AWAITING_APPROVAL	4283/534/534/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
6	6	6	RandomForest	CfsSubsetEval	MCAT	AWAITING_APPROVAL	15	2	AWAITING_APPROVAL	3411/425/425/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
7	7	7	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	18	1	AWAITING_APPROVAL	332/40/40/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
8	8	8	LibSVM	CfsSubsetEval	ALL	AWAITING_APPROVAL	19	1	AWAITING_APPROVAL	1004/124/124/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
9	9	9	Ensemble	CfsSubsetEval	KR	AWAITING_APPROVAL	20	1	AWAITING_APPROVAL	1294/160/160/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
10	10	10	NaiveBayes	CfsSubsetEval	EXTGO	AWAITING_APPROVAL	21	1	AWAITING_APPROVAL	351/42/42/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
11	11	11	J48	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	22	1	AWAITING_APPROVAL	727/90/90/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
12	12	12	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	23	1	AWAITING_APPROVAL	1089/135/135/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
13	13	13	LibSVM	CfsSubsetEval	ALL	AWAITING_APPROVAL	24	1	AWAITING_APPROVAL	3930/490/490/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
14	14	14	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	25	1	AWAITING_APPROVAL	3902/487/487/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
15	15	15	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	26	1	AWAITING_APPROVAL	788/97/97/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
16	16	16	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	27	1	AWAITING_APPROVAL	588/72/72/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
17	17	17	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	28	1	AWAITING_APPROVAL	738/91/91/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
18	18	18	Ensemble	CfsSubsetEval	KR	AWAITING_APPROVAL	29	1	AWAITING_APPROVAL	8343/1042/1042/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
19	19	19	Ensemble	CfsSubsetEval	MCAT	REBUILD_FAILED	135	2	RUNNING	0/0/0/0	N/A	N/A	N/A	N/A	N/A	RUNNING
20	20	2	LibSVM	CfsSubsetEval	CDK	AWAITING_APPROVAL	32	2	AWAITING_APPROVAL	82/9/9/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
21	21	1	NaiveBayes	CfsSubsetEval	PubChem	AWAITING_APPROVAL	33	1	AWAITING_APPROVAL	1014/126/126/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
22	22	2	DecisionTreeNaiveBayes	CfsSubsetEval	CDK	AWAITING_APPROVAL	34	1	AWAITING_APPROVAL	82/9/9/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
23	23	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	35	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
24	24	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	36	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
25	25	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	38	2	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
26	26	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	39	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
27	27	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	40	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
28	28	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	41	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
29	29	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	42	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
30	30	23	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	43	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
31	31	23	LibSVM	CfsSubsetEval	MCAT	AWAITING_APPROVAL	44	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
32	32	23	RandomForest	CfsSubsetEval	EXT	AWAITING_APPROVAL	45	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
33	33	23	RandomForest	CfsSubsetEval	MCAT	AWAITING_APPROVAL	46	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
34	34	23	RandomForest	CfsSubsetEval	EXTGO	AWAITING_APPROVAL	47	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
35	35	23	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	48	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
36	36	23	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	49	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
37	37	23	NaiveBayes	CfsSubsetEval	MCAT	AWAITING_APPROVAL	50	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
38	38	23	LogitBoost	CfsSubsetEval	MCAT	AWAITING_APPROVAL	51	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
39	39	23	KNN	CfsSubsetEval	MCAT	AWAITING_APPROVAL	55	2	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
40	40	23	SMO	CfsSubsetEval	MCAT	AWAITING_APPROVAL	54	2	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
41	41	23	BayesNet	CfsSubsetEval	MCAT	AWAITING_APPROVAL	56	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
42	42	23	J48	CfsSubsetEval	MCAT	AWAITING_APPROVAL	57	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
43	43	23	LibSVM2	CfsSubsetEval	MCAT	AWAITING_APPROVAL	58	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
44	44	23	LMT	CfsSubsetEval	MCAT	AWAITING_APPROVAL	59	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
45	45	23	NBTree	CfsSubsetEval	MCAT	AWAITING_APPROVAL	60	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
46	46	23	NeuralNet	CfsSubsetEval	MCAT	AWAITING_APPROVAL	61	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
47	47	23	Ensemble2	CfsSubsetEval	MCAT	AWAITING_APPROVAL	132	2	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
48	48	23	RandomForest	CfsSubsetEval	CDK	AWAITING_APPROVAL	63	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
49	49	23	RandomForest	CfsSubsetEval	MACCS	AWAITING_APPROVAL	64	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
50	50	23	RandomForest	CfsSubsetEval	PubChem	AWAITING_APPROVAL	65	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
51	51	23	RandomForest	CfsSubsetEval	KR	AWAITING_APPROVAL	66	1	AWAITING_APPROVAL	1258/156/156/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
52	52	25	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	67	1	AWAITING_APPROVAL	504/62/62/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
53	53	25	NaiveBayes	CfsSubsetEval	ALL	AWAITING_APPROVAL	68	1	AWAITING_APPROVAL	504/62/62/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
54	54	25	Ensemble	CfsSubsetEval	ALL	AWAITING_APPROVAL	69	1	AWAITING_APPROVAL	504/62/62/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
55	55	25	BayesNet	CfsSubsetEval	ALL	AWAITING_APPROVAL	70	1	AWAITING_APPROVAL	504/62/62/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
56	56	27	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	71	1	AWAITING_APPROVAL	7490/936/936/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
57	57	28	BayesNet	CfsSubsetEval	PubChem	AWAITING_APPROVAL	72	1	AWAITING_APPROVAL	336/41/41/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
58	58	30	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	73	1	AWAITING_APPROVAL	101/11/11/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
59	59	30	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	74	1	AWAITING_APPROVAL	101/11/11/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
60	60	31	NaiveBayes	CfsSubsetEval	CDK	AWAITING_APPROVAL	75	1	AWAITING_APPROVAL	884/109/109/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
61	61	31	BayesNet	CfsSubsetEval	CDK	AWAITING_APPROVAL	76	1	AWAITING_APPROVAL	884/109/109/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
62	62	31	NeuralNet	CfsSubsetEval	CDK	AWAITING_APPROVAL	77	1	AWAITING_APPROVAL	884/109/109/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
63	63	31	RandomForest	CfsSubsetEval	CDK	AWAITING_APPROVAL	78	1	AWAITING_APPROVAL	884/109/109/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
64	64	31	LibSVM2	CfsSubsetEval	CDK	AWAITING_APPROVAL	79	1	AWAITING_APPROVAL	884/109/109/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
65	65	31	LibSVM	CfsSubsetEval	CDK	AWAITING_APPROVAL	80	1	AWAITING_APPROVAL	884/109/109/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
66	66	31	Ensemble	CfsSubsetEval	CDK	AWAITING_APPROVAL	81	1	AWAITING_APPROVAL	884/109/109/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
67	70	31	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	82	1	AWAITING_APPROVAL	884/109/109/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
68	71	32	BayesNet	CfsSubsetEval	CDK	AWAITING_APPROVAL	83	1	AWAITING_APPROVAL	1414/174/174/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
69	72	33	BayesNet	CfsSubsetEval	CDK	AWAITING_APPROVAL	84	1	AWAITING_APPROVAL	1293/160/160/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
70	73	34	BayesNet	CfsSubsetEval	ALL	AWAITING_APPROVAL	85	1	AWAITING_APPROVAL	3364/418/418/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
71	74	35	BayesNet	CfsSubsetEval	ALL	AWAITING_APPROVAL	86	1	AWAITING_APPROVAL	327/40/40/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
72	75	48	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	87	1	AWAITING_APPROVAL	658/81/81/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
73	76	48	Ensemble	CfsSubsetEval	MCAT	AWAITING_APPROVAL	88	1	AWAITING_APPROVAL	658/81/81/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
74	77	49	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	89	1	AWAITING_APPROVAL	369/45/45/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
75	78	10	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	90	1	AWAITING_APPROVAL	351/42/42/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
76	79	50	RandomForest	CfsSubsetEval	MCAT	AWAITING_APPROVAL	92	2	AWAITING_APPROVAL	1987/247/247/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
77	80	51	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	93	1	AWAITING_APPROVAL	246/29/29/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
78	81	52	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	94	1	AWAITING_APPROVAL	378/46/46/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
79	82	53	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	95	1	AWAITING_APPROVAL	2198/274/274/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
80	83	54	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	96	1	AWAITING_APPROVAL	282/33/33/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
81	84	48	BayesNet	CfsSubsetEval	ALL	AWAITING_APPROVAL	97	1	AWAITING_APPROVAL	658/81/81/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
82	85	55	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	98	1	AWAITING_APPROVAL	290/35/35/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
83	86	60	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	99	1	AWAITING_APPROVAL	1511/187/187/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
84	87	62	Ensemble	CfsSubsetEval	PubChem	AWAITING_APPROVAL	100	1	AWAITING_APPROVAL	8149/1017/1017/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
85	88	61	RandomForest	CfsSubsetEval	PubChem	AWAITING_APPROVAL	101	1	AWAITING_APPROVAL	3915/489/489/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
86	89	63	NaiveBayes	CfsSubsetEval	ALL	AWAITING_APPROVAL	102	1	AWAITING_APPROVAL	7055/881/881/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
87	90	64	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	103	1	AWAITING_APPROVAL	489/59/59/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
88	91	64	Ensemble	CfsSubsetEval	ALL	AWAITING_APPROVAL	104	1	AWAITING_APPROVAL	487/60/60/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
89	92	64	Ensemble	CfsSubsetEval	CDK	AWAITING_APPROVAL	105	1	AWAITING_APPROVAL	489/59/59/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
90	93	64	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	106	1	AWAITING_APPROVAL	489/59/59/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
91	94	65	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	107	1	AWAITING_APPROVAL	85/9/9/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
92	95	65	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	108	1	AWAITING_APPROVAL	85/9/9/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
93	96	66	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	109	1	AWAITING_APPROVAL	23/1/3/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
94	97	66	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	110	1	AWAITING_APPROVAL	24/1/2/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
95	98	67	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	111	1	AWAITING_APPROVAL	7003/874/874/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
96	99	67	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	112	1	AWAITING_APPROVAL	7003/874/874/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
97	100	73	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	113	1	AWAITING_APPROVAL	31/3/3/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
98	101	73	RandomForest	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	5	3	AWAITING_APPROVAL	31/3/3/0	FAIL	PASS	PASS	PASS	PASS	COMPLETED
99	102	73	NaiveBayes	CfsSubsetEval	CDK	AWAITING_APPROVAL	114	2	AWAITING_APPROVAL	31/3/3/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
100	103	74	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	115	1	AWAITING_APPROVAL	528/64/64/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
101	104	74	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	116	1	AWAITING_APPROVAL	278/33/33/312	PASS	PASS	PASS	PASS	PASS	COMPLETED
102	105	75	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	117	1	AWAITING_APPROVAL	100/11/11/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
103	106	76	RandomForest	CfsSubsetEval	JUMBO	REBUILD_FAILED	118	1	REBUILD_FAILED	0/0/0/0	N/A	N/A	N/A	N/A	N/A	FAILED
104	107	76	Ensemble	CfsSubsetEval	JUMBO	PENDING_REBUILD	NULL	NULL	NO_BUILD	-	N/A	N/A	N/A	N/A	N/A	NO_JOB
105	108	74	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	119	1	AWAITING_APPROVAL	278/33/33/312	PASS	PASS	PASS	PASS	PASS	COMPLETED
106	109	78	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	120	1	AWAITING_APPROVAL	5053/630/630/3158	PASS	PASS	PASS	PASS	PASS	COMPLETED
107	110	78	BayesNet	CfsSubsetEval	MCAT	AWAITING_APPROVAL	129	1	AWAITING_APPROVAL	5040/629/629/3173	PASS	PASS	PASS	PASS	PASS	COMPLETED
108	111	74	BayesNet	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	122	1	AWAITING_APPROVAL	528/64/64/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
109	112	81	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	123	1	AWAITING_APPROVAL	576/70/70/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
110	113	81	LibSVM	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	128	1	AWAITING_APPROVAL	576/70/70/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
111	114	83	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	124	1	AWAITING_APPROVAL	617/74/74/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
112	115	83	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	125	1	AWAITING_APPROVAL	615/75/75/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
113	116	83	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	126	1	AWAITING_APPROVAL	613/76/76/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
114	117	81	Ensemble	CfsSubsetEval	JUMBO	AWAITING_APPROVAL	127	1	AWAITING_APPROVAL	574/71/71/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
115	118	74	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	121	1	AWAITING_APPROVAL	526/65/65/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
116	119	4	RandomForest	ReliefFAttributeEval	ALL	AWAITING_APPROVAL	131	1	AWAITING_APPROVAL	382/47/47/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
117	120	1	RandomForest	ReliefFAttributeEval	MACCS	AWAITING_APPROVAL	130	1	AWAITING_APPROVAL	1014/126/126/1	PASS	PASS	PASS	PASS	PASS	COMPLETED
118	NULL	88	RandomForest	CfsSubsetEval	ALL	AWAITING_APPROVAL	31	1	AWAITING_APPROVAL	1424/177/177/0	PASS	PASS	PASS	PASS	PASS	COMPLETED
```
<!-- FULL_MATRIX_END -->

The matrix partitions exactly as follows:

- 106 candidates pass the audit's database-metadata and metric-value checks:
  definitions 1-18, 20-58, 60, 62-63, 66-67, 69-86, 88-92, 95-97,
  99-102, and 105-118.
- 9 candidates fail metric-value completeness:
  definitions 59, 61, 64, 65, 68, 87, 93, 94, and 98.
- 3 definitions are not complete builds:
  definitions 19, 103, and 104.

## Approval and production-audit contract gaps

### Metric value is not validated

`V3ModelApproval.verifyMetrics` selects only `metric_code` and `support_count`.
`verifyMetricEvidence` verifies set equality and support equality but never selects or
validates `metric_value`. The nine builds above would pass that part of approval.

`V3ProductionAudit.completedBuildReviewContractViolations` counts distinct
`evaluation_set:metric_code` pairs but does not validate value or support. It filters
`class_label IS NULL` but not `fold_number IS NULL`, so a fold-level row could also satisfy
its aggregate pair count if the aggregate row were missing. Current data has no fold/class
rows, so that second weakness is latent rather than an observed false pass.

Action: make both gates require the exact aggregate key
`fold_number IS NULL AND class_label IS NULL`, exact support, a non-null finite value, and
the metric-specific range. If undefined metrics are scientifically legitimate, represent
availability explicitly and define a reviewed policy; do not silently treat SQL `NULL` as
complete evidence.

### Class support semantics are weaker than the persisted data

All class rows are nonblank, distinct, and positive. Their support totals equal
`training_count + validation_count + holdout_count`, not training alone. The approver only
requires two class rows and does not reconcile class supports.

Action: document class support as full non-excluded support and enforce that sum, or persist
partition-specific class support if reviewers need training-only distributions.

### Artifact metadata is not artifact integrity

This audit proves only metadata shape. It intentionally provides no evidence that BLOB
bytes exist, match declared sizes, deserialize safely, or hash to stored digests.

Action: keep the streaming artifact verification in the transactional approval CLI. Never
replace it with this metadata audit.

### Source provenance is human-readable but not immutable

All builds use `local-production-candidate-20260814`. The current gate rejects only blank
and exact `working-tree` values.

Action: require an immutable commit or release digest plus a reproducible dependency lock
before production approval.

### Running model jobs lack a lease deadline

Definition 19's active job has a heartbeat but `lease_expires_at IS NULL`. Its definition
still says `REBUILD_FAILED` while its latest build says `RUNNING`.

Action: add model-job lease ownership, periodic heartbeats, lease-loss handling, and a
clear transitional definition state before relying on automatic recovery.

## Human-review cautions

- Do not approve any model automatically from this audit or from aggregate metrics.
- Block the nine null-metric candidates until an explicit undefined-metric policy and gate
  correction are in place.
- Treat the other 106 only as structurally reviewable, not as scientifically accepted.
- Stream and verify artifact payloads in the approval transaction before any publication.
- Review confusion matrices and per-class sensitivity, specificity, precision, recall,
  F1, ROC/PR behavior, class prevalence, and calibration. Those are not persisted here.
- Establish model-specific minimum criteria before looking at results to avoid
  post-hoc cherry-picking.
- Treat validation or holdout supports below a defensible minimum as insufficient,
  especially definitions 93, 94, and 98.
- Investigate perfect training metrics for overfitting or leakage.
- Compare against simple baselines and the legacy model using the same immutable split.
- Review scaffold, analogue, temporal, assay, and duplicate-compound leakage. A stratified
  hash split alone does not prove chemical-domain independence.
- Review applicability domain and out-of-distribution behavior before deployment.
- Review exclusions and missing-label patterns for selection bias even though counts
  reconcile.
- Require a human note recording scientific evidence, limitations, and intended domain.
- Keep approval credentials separate from read-only review credentials.
- Never expose awaiting, rejected, failed, or superseded artifacts to prediction.

## Actionable next steps

| Priority | Action | Exit evidence |
|---:|---|---|
| P0 | Harden `V3ModelApproval` metric validation | Tests prove null, NaN/infinite, out-of-range, wrong-support, duplicate, fold-only, and class-only rows fail |
| P0 | Harden `V3ProductionAudit` to the same exact aggregate contract | Production audit reports the nine current candidates as failures |
| P0 | Define the policy for undefined weighted metrics | Written rule distinguishes unavailable evidence from valid numeric zero |
| P0 | Do not approve the nine affected builds | They remain awaiting or are explicitly rejected by a human |
| P1 | Add persisted confusion/per-class evidence and minimum split-size review flags | Review API exposes evidence without BLOBs |
| P1 | Implement robust supervised-discretization labels | Definitions 103 and 104 rebuild without changing bin assignments |
| P1 | Complete or safely terminate definition 19 | Build/job/definition states reconcile and lease behavior is proven |
| P1 | Add model-job leases and periodic heartbeat | Running jobs have owner, heartbeat, expiry, and lease-loss tests |
| P1 | Require immutable code provenance | Approval rejects non-reproducible labels |
| P1 | Perform streaming artifact verification during human approval | Exact size and SHA-256 pass for MODEL and HEADER |
| P2 | Establish algorithm/dataset-specific acceptance criteria | Human review checklist references fixed thresholds and baselines |
| P2 | Evaluate scaffold/temporal splits and applicability domain | Independent evidence is attached to the decision |

## Commands and results

Repository inspection used `rg`, `nl -ba`, and `awk` against the authoritative files listed
above. `DatabaseUtils.props` was inspected with every value redacted; it is a Weka database
type mapping file and contains no usable local credential.

The database command used the MariaDB batch client with credentials supplied through the
process environment and omitted from all output. The executed payload was the exact SQL
in the appendix below. The successful run returned
`FINAL_READ_ONLY_SNAPSHOT_COMPLETE`.

No rebuild task, approval task, production audit digest mode, DDL, DML, Git command, or
artifact-payload query was executed.

## Exact executed SQL

The following is the credential-free SQL sent to MariaDB. Repetitive CTEs are intentional:
each result is independently reproducible while all statements share the surrounding
consistent read-only transaction.

<!-- SQL_START -->
```sql
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

SELECT 'Q00_SNAPSHOT' AS audit_section;
SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ') AS snapshot_utc,
       VERSION() AS mariadb_version,
       @@tx_isolation AS transaction_isolation,
       @@session.time_zone AS session_time_zone;

SELECT 'Q01_DEFINITION_STATUS_DISTRIBUTION' AS audit_section;
SELECT status,COUNT(*) AS definition_count
FROM molclass_v3.model_definition
GROUP BY status ORDER BY status;

SELECT 'Q02_LATEST_BUILD_STATUS_DISTRIBUTION' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT COALESCE(r.status,'NO_BUILD') AS latest_build_status,COUNT(*) AS definition_count
FROM molclass_v3.model_definition md
LEFT JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1
GROUP BY COALESCE(r.status,'NO_BUILD') ORDER BY latest_build_status;

SELECT 'Q03_INVENTORY_COUNTS' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT COUNT(*) AS definitions,
       SUM(r.model_build_id IS NULL) AS definitions_without_build,
       SUM(md.status='AWAITING_APPROVAL') AS awaiting_definitions,
       SUM(md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL') AS awaiting_latest_candidates,
       SUM(r.status='RUNNING') AS latest_running_builds,
       SUM(md.status IN ('PENDING_REBUILD','REBUILD_FAILED','UNSUPPORTED_CONFIGURATION')) AS failed_or_held_definitions,
       (SELECT COUNT(*) FROM molclass_v3.model_build) AS all_build_generations,
       (SELECT COUNT(*) FROM molclass_v3.model_evaluation) AS all_evaluation_rows,
       (SELECT COUNT(*) FROM molclass_v3.model_artifact) AS all_artifact_metadata_rows
FROM molclass_v3.model_definition md
LEFT JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1;

SELECT 'Q04_ALL_LATEST_ALGORITHM_PROFILE_DISTRIBUTION' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT md.status AS definition_status,COALESCE(r.status,'NO_BUILD') AS latest_build_status,
       md.algorithm_code,md.feature_selection_code,fp.profile_code,COUNT(*) AS definitions
FROM molclass_v3.model_definition md
JOIN molclass_v3.feature_profile fp ON fp.feature_profile_id=md.feature_profile_id
LEFT JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1
GROUP BY md.status,COALESCE(r.status,'NO_BUILD'),md.algorithm_code,
         md.feature_selection_code,fp.profile_code
ORDER BY md.status,latest_build_status,md.algorithm_code,md.feature_selection_code,fp.profile_code;

SELECT 'Q05_AWAITING_ALGORITHM_PROFILE_DISTRIBUTION' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT md.algorithm_code,md.feature_selection_code,fp.profile_code,COUNT(*) AS candidates
FROM molclass_v3.model_definition md
JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1
JOIN molclass_v3.feature_profile fp ON fp.feature_profile_id=md.feature_profile_id
WHERE md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
GROUP BY md.algorithm_code,md.feature_selection_code,fp.profile_code
ORDER BY md.algorithm_code,md.feature_selection_code,fp.profile_code;

SELECT 'Q06_AWAITING_METRIC_CODE_VALUE_DISTRIBUTION' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT me.evaluation_set,me.metric_code,COUNT(*) AS rows_count,
       SUM(me.metric_value IS NULL) AS null_values,
       SUM(me.metric_value IS NOT NULL AND
           CASE WHEN me.metric_code='KAPPA'
                THEN NOT(me.metric_value BETWEEN -1.0 AND 1.0)
                ELSE NOT(me.metric_value BETWEEN 0.0 AND 1.0) END) AS nonfinite_or_out_of_range,
       SUM(me.support_count IS NULL OR me.support_count<>
           CASE me.evaluation_set
             WHEN 'TRAIN' THEN r.training_count
             WHEN 'VALIDATION' THEN r.validation_count
             WHEN 'HOLDOUT' THEN r.holdout_count
           END) AS support_mismatches,
       MIN(me.metric_value) AS minimum_value,MAX(me.metric_value) AS maximum_value,
       MIN(me.support_count) AS minimum_support,MAX(me.support_count) AS maximum_support
FROM ranked r
JOIN molclass_v3.model_definition md ON md.model_definition_id=r.model_definition_id
JOIN molclass_v3.model_evaluation me ON me.model_build_id=r.model_build_id
WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
  AND me.fold_number IS NULL AND me.class_label IS NULL
GROUP BY me.evaluation_set,me.metric_code
ORDER BY FIELD(me.evaluation_set,'TRAIN','VALIDATION','HOLDOUT'),me.metric_code;

SELECT 'Q07_AWAITING_METRIC_CONTRACT_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), e AS (
  SELECT c.model_build_id,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')) AS required_rows,
    COUNT(DISTINCT CASE WHEN me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
      THEN CONCAT(me.evaluation_set,':',me.metric_code) END) AS distinct_required_pairs,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND (me.evaluation_set NOT IN ('TRAIN','VALIDATION','HOLDOUT')
          OR me.metric_code NOT IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
            'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC'))) AS unexpected_aggregate_rows,
    SUM(me.fold_number IS NOT NULL OR me.class_label IS NOT NULL) AS nonaggregate_rows,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
        AND (me.support_count IS NULL OR me.support_count<>
          CASE me.evaluation_set WHEN 'TRAIN' THEN c.training_count
            WHEN 'VALIDATION' THEN c.validation_count
            WHEN 'HOLDOUT' THEN c.holdout_count END)) AS support_mismatches,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
        AND (me.metric_value IS NULL OR
          CASE WHEN me.metric_code='KAPPA'
            THEN NOT(me.metric_value BETWEEN -1.0 AND 1.0)
            ELSE NOT(me.metric_value BETWEEN 0.0 AND 1.0) END)) AS invalid_values
  FROM candidates c
  LEFT JOIN molclass_v3.model_evaluation me ON me.model_build_id=c.model_build_id
  GROUP BY c.model_build_id
)
SELECT COUNT(*) AS candidate_builds,
       SUM(required_rows=18 AND distinct_required_pairs=18) AS complete_required_metric_sets,
       SUM(required_rows<>18 OR distinct_required_pairs<>18) AS incomplete_or_duplicate_metric_sets,
       SUM(support_mismatches=0) AS support_complete_builds,
       SUM(support_mismatches<>0) AS support_mismatch_builds,
       SUM(invalid_values=0) AS value_valid_builds,
       SUM(invalid_values<>0) AS invalid_value_builds,
       SUM(unexpected_aggregate_rows<>0) AS builds_with_unexpected_aggregate_rows,
       SUM(nonaggregate_rows<>0) AS builds_with_fold_or_class_rows
FROM e;

SELECT 'Q08_AWAITING_METRIC_ANOMALIES' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), e AS (
  SELECT c.model_definition_id,c.model_build_id,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')) AS required_rows,
    COUNT(DISTINCT CASE WHEN me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
      THEN CONCAT(me.evaluation_set,':',me.metric_code) END) AS distinct_pairs,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
        AND (me.support_count IS NULL OR me.support_count<>
          CASE me.evaluation_set WHEN 'TRAIN' THEN c.training_count
            WHEN 'VALIDATION' THEN c.validation_count
            WHEN 'HOLDOUT' THEN c.holdout_count END)) AS support_mismatches,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
        AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
        AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
          'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
        AND (me.metric_value IS NULL OR
          CASE WHEN me.metric_code='KAPPA'
            THEN NOT(me.metric_value BETWEEN -1.0 AND 1.0)
            ELSE NOT(me.metric_value BETWEEN 0.0 AND 1.0) END)) AS invalid_values
  FROM candidates c LEFT JOIN molclass_v3.model_evaluation me
    ON me.model_build_id=c.model_build_id
  GROUP BY c.model_definition_id,c.model_build_id
)
SELECT * FROM e
WHERE required_rows<>18 OR distinct_pairs<>18 OR support_mismatches<>0 OR invalid_values<>0
ORDER BY model_definition_id;

SELECT 'Q09_AWAITING_MEMBERSHIP_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*,md.dataset_id
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), m AS (
  SELECT c.model_definition_id,c.model_build_id,c.training_count,c.validation_count,
         c.holdout_count,c.excluded_count,c.dataset_id,
         COUNT(mtm.dataset_molecule_id) AS actual_total,
         COUNT(DISTINCT mtm.dataset_molecule_id) AS distinct_molecules,
         SUM(mtm.partition_name='TRAIN') AS actual_train,
         SUM(mtm.partition_name='VALIDATION') AS actual_validation,
         SUM(mtm.partition_name='HOLDOUT') AS actual_holdout,
         SUM(mtm.partition_name='EXCLUDED') AS actual_excluded,
         SUM(mtm.partition_name NOT IN ('TRAIN','VALIDATION','HOLDOUT','EXCLUDED')) AS unknown_partitions,
         SUM(mtm.fold_number IS NOT NULL) AS nonnull_fold_numbers,
         SUM((mtm.partition_name='EXCLUDED' AND
              (mtm.exclusion_reason IS NULL OR TRIM(mtm.exclusion_reason)=''))
             OR (mtm.partition_name<>'EXCLUDED' AND mtm.exclusion_reason IS NOT NULL)) AS bad_exclusion_reasons,
         SUM(dm.dataset_id<>c.dataset_id) AS wrong_dataset_members
  FROM candidates c
  LEFT JOIN molclass_v3.model_training_member mtm ON mtm.model_build_id=c.model_build_id
  LEFT JOIN molclass_v3.dataset_molecule dm ON dm.dataset_molecule_id=mtm.dataset_molecule_id
  GROUP BY c.model_definition_id,c.model_build_id,c.training_count,c.validation_count,
           c.holdout_count,c.excluded_count,c.dataset_id
), d AS (
  SELECT dataset_id,COUNT(*) AS dataset_molecule_count
  FROM molclass_v3.dataset_molecule GROUP BY dataset_id
)
SELECT COUNT(*) AS candidate_builds,
       SUM(actual_train=training_count AND actual_validation=validation_count
           AND actual_holdout=holdout_count AND actual_excluded=excluded_count) AS exact_partition_count_builds,
       SUM(NOT(actual_train=training_count AND actual_validation=validation_count
           AND actual_holdout=holdout_count AND actual_excluded=excluded_count)) AS partition_count_mismatch_builds,
       SUM(actual_total=distinct_molecules) AS duplicate_free_builds,
       SUM(actual_total<>distinct_molecules) AS builds_with_duplicate_members,
       SUM(actual_total=COALESCE(d.dataset_molecule_count,0)) AS full_dataset_reconciliation_builds,
       SUM(actual_total<>COALESCE(d.dataset_molecule_count,0)) AS dataset_total_mismatch_builds,
       SUM(unknown_partitions<>0 OR nonnull_fold_numbers<>0 OR bad_exclusion_reasons<>0
           OR wrong_dataset_members<>0) AS membership_metadata_anomaly_builds
FROM m LEFT JOIN d ON d.dataset_id=m.dataset_id;

SELECT 'Q10_AWAITING_MEMBERSHIP_ANOMALIES' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*,md.dataset_id
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), m AS (
  SELECT c.model_definition_id,c.model_build_id,c.training_count,c.validation_count,
         c.holdout_count,c.excluded_count,c.dataset_id,
         COUNT(mtm.dataset_molecule_id) AS actual_total,
         COUNT(DISTINCT mtm.dataset_molecule_id) AS distinct_molecules,
         SUM(mtm.partition_name='TRAIN') AS actual_train,
         SUM(mtm.partition_name='VALIDATION') AS actual_validation,
         SUM(mtm.partition_name='HOLDOUT') AS actual_holdout,
         SUM(mtm.partition_name='EXCLUDED') AS actual_excluded,
         SUM(mtm.partition_name NOT IN ('TRAIN','VALIDATION','HOLDOUT','EXCLUDED')) AS unknown_partitions,
         SUM(mtm.fold_number IS NOT NULL) AS nonnull_fold_numbers,
         SUM((mtm.partition_name='EXCLUDED' AND
              (mtm.exclusion_reason IS NULL OR TRIM(mtm.exclusion_reason)=''))
             OR (mtm.partition_name<>'EXCLUDED' AND mtm.exclusion_reason IS NOT NULL)) AS bad_exclusion_reasons,
         SUM(dm.dataset_id<>c.dataset_id) AS wrong_dataset_members
  FROM candidates c
  LEFT JOIN molclass_v3.model_training_member mtm ON mtm.model_build_id=c.model_build_id
  LEFT JOIN molclass_v3.dataset_molecule dm ON dm.dataset_molecule_id=mtm.dataset_molecule_id
  GROUP BY c.model_definition_id,c.model_build_id,c.training_count,c.validation_count,
           c.holdout_count,c.excluded_count,c.dataset_id
), d AS (
  SELECT dataset_id,COUNT(*) AS dataset_molecule_count
  FROM molclass_v3.dataset_molecule GROUP BY dataset_id
)
SELECT m.*,COALESCE(d.dataset_molecule_count,0) AS dataset_molecule_count
FROM m LEFT JOIN d ON d.dataset_id=m.dataset_id
WHERE actual_train<>training_count OR actual_validation<>validation_count
   OR actual_holdout<>holdout_count OR actual_excluded<>excluded_count
   OR actual_total<>distinct_molecules
   OR actual_total<>COALESCE(d.dataset_molecule_count,0)
   OR unknown_partitions<>0 OR nonnull_fold_numbers<>0
   OR bad_exclusion_reasons<>0 OR wrong_dataset_members<>0
ORDER BY model_definition_id;

SELECT 'Q11_AWAITING_CLASS_SUPPORT_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), c AS (
  SELECT x.model_definition_id,x.model_build_id,x.training_count,x.validation_count,x.holdout_count,
         COUNT(mc.model_build_id) AS class_count,
         COUNT(DISTINCT mc.class_label) AS distinct_labels,
         SUM(mc.class_label IS NULL OR TRIM(mc.class_label)='') AS blank_labels,
         SUM(mc.support_count IS NULL OR mc.support_count<=0) AS nonpositive_supports,
         COALESCE(SUM(mc.support_count),0) AS class_support_total
  FROM candidates x LEFT JOIN molclass_v3.model_class mc
    ON mc.model_build_id=x.model_build_id
  GROUP BY x.model_definition_id,x.model_build_id,x.training_count,x.validation_count,x.holdout_count
)
SELECT COUNT(*) AS candidate_builds,
       SUM(class_count>=2 AND class_count=distinct_labels AND blank_labels=0
           AND nonpositive_supports=0) AS valid_class_metadata_builds,
       SUM(NOT(class_count>=2 AND class_count=distinct_labels AND blank_labels=0
           AND nonpositive_supports=0)) AS invalid_class_metadata_builds,
       SUM(class_support_total=training_count+validation_count+holdout_count) AS class_support_matches_nonexcluded_builds,
       SUM(class_support_total<>training_count+validation_count+holdout_count) AS class_support_nonexcluded_mismatch_builds
FROM c;

SELECT 'Q12_AWAITING_CLASS_SUPPORT_ANOMALIES' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), c AS (
  SELECT x.model_definition_id,x.model_build_id,x.training_count,x.validation_count,x.holdout_count,
         COUNT(mc.model_build_id) AS class_count,
         COUNT(DISTINCT mc.class_label) AS distinct_labels,
         SUM(mc.class_label IS NULL OR TRIM(mc.class_label)='') AS blank_labels,
         SUM(mc.support_count IS NULL OR mc.support_count<=0) AS nonpositive_supports,
         COALESCE(SUM(mc.support_count),0) AS class_support_total
  FROM candidates x LEFT JOIN molclass_v3.model_class mc
    ON mc.model_build_id=x.model_build_id
  GROUP BY x.model_definition_id,x.model_build_id,x.training_count,x.validation_count,x.holdout_count
)
SELECT * FROM c
WHERE class_count<2 OR class_count<>distinct_labels OR blank_labels<>0
   OR nonpositive_supports<>0 OR class_support_total<>training_count+validation_count+holdout_count
ORDER BY model_definition_id;

SELECT 'Q13_AWAITING_ARTIFACT_METADATA_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), a AS (
  SELECT c.model_definition_id,c.model_build_id,
         COUNT(ma.model_artifact_id) AS artifact_count,
         SUM(ma.artifact_kind='MODEL') AS model_count,
         SUM(ma.artifact_kind='HEADER') AS header_count,
         SUM(ma.artifact_kind NOT IN ('MODEL','HEADER')) AS unexpected_kinds,
         SUM(ma.artifact_format<>'JAVA_SERIALIZATION_WEKA_3_8_7_GZIP') AS bad_formats,
         SUM(ma.media_type<>'application/gzip') AS bad_media_types,
         SUM(ma.artifact_size IS NULL OR ma.artifact_size<=0) AS bad_sizes,
         SUM(ma.artifact_sha256 IS NULL OR OCTET_LENGTH(ma.artifact_sha256)<>32) AS bad_hash_metadata,
         SUM(ma.created_at IS NULL) AS missing_created_at
  FROM candidates c LEFT JOIN molclass_v3.model_artifact ma
    ON ma.model_build_id=c.model_build_id
  GROUP BY c.model_definition_id,c.model_build_id
)
SELECT COUNT(*) AS candidate_builds,
       SUM(artifact_count=2 AND model_count=1 AND header_count=1
           AND unexpected_kinds=0 AND bad_formats=0 AND bad_media_types=0
           AND bad_sizes=0 AND bad_hash_metadata=0 AND missing_created_at=0) AS complete_metadata_sets,
       SUM(NOT(artifact_count=2 AND model_count=1 AND header_count=1
           AND unexpected_kinds=0 AND bad_formats=0 AND bad_media_types=0
           AND bad_sizes=0 AND bad_hash_metadata=0 AND missing_created_at=0)) AS incomplete_metadata_sets,
       SUM(artifact_count) AS metadata_rows_examined
FROM a;

SELECT 'Q14_AWAITING_ARTIFACT_METADATA_ANOMALIES' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), a AS (
  SELECT c.model_definition_id,c.model_build_id,
         COUNT(ma.model_artifact_id) AS artifact_count,
         SUM(ma.artifact_kind='MODEL') AS model_count,
         SUM(ma.artifact_kind='HEADER') AS header_count,
         SUM(ma.artifact_kind NOT IN ('MODEL','HEADER')) AS unexpected_kinds,
         SUM(ma.artifact_format<>'JAVA_SERIALIZATION_WEKA_3_8_7_GZIP') AS bad_formats,
         SUM(ma.media_type<>'application/gzip') AS bad_media_types,
         SUM(ma.artifact_size IS NULL OR ma.artifact_size<=0) AS bad_sizes,
         SUM(ma.artifact_sha256 IS NULL OR OCTET_LENGTH(ma.artifact_sha256)<>32) AS bad_hash_metadata,
         SUM(ma.created_at IS NULL) AS missing_created_at
  FROM candidates c LEFT JOIN molclass_v3.model_artifact ma
    ON ma.model_build_id=c.model_build_id
  GROUP BY c.model_definition_id,c.model_build_id
)
SELECT * FROM a
WHERE artifact_count<>2 OR model_count<>1 OR header_count<>1
   OR unexpected_kinds<>0 OR bad_formats<>0 OR bad_media_types<>0
   OR bad_sizes<>0 OR bad_hash_metadata<>0 OR missing_created_at<>0
ORDER BY model_definition_id;

SELECT 'Q15_AWAITING_MANIFEST_PROVENANCE_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
)
SELECT COUNT(*) AS candidate_builds,
       SUM(build_manifest_json IS NOT NULL AND JSON_VALID(build_manifest_json)=1) AS valid_json_manifests,
       SUM(manifest_sha256 IS NOT NULL AND OCTET_LENGTH(manifest_sha256)=32) AS present_manifest_hashes,
       SUM(build_manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL
           AND manifest_sha256=UNHEX(SHA2(build_manifest_json,256))) AS matching_manifest_hashes,
       SUM(generation_label='v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1') AS production_generation_labels,
       SUM(code_revision IS NOT NULL AND TRIM(code_revision)<>'' AND code_revision<>'working-tree') AS acceptable_code_revisions,
       SUM(runstep='COMPLETE' AND finished_at IS NOT NULL) AS complete_lifecycle_rows,
       SUM(training_count>0 AND validation_count>0 AND holdout_count>0) AS nonempty_required_splits,
       SUM(split_strategy='STRATIFIED_HASH_80_10_10_V1') AS expected_split_strategies
FROM candidates;

SELECT 'Q16_AWAITING_MANIFEST_PROVENANCE_ANOMALIES' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT r.model_definition_id,r.model_build_id,r.generation_label,r.code_revision,
       r.java_version,r.cdk_version,r.weka_version,r.database_schema_version,
       r.split_strategy,r.runstep,r.training_count,r.validation_count,r.holdout_count,
       JSON_VALID(r.build_manifest_json) AS manifest_json_valid,
       OCTET_LENGTH(r.manifest_sha256) AS manifest_hash_bytes,
       (r.manifest_sha256=UNHEX(SHA2(r.build_manifest_json,256))) AS manifest_hash_matches
FROM ranked r JOIN molclass_v3.model_definition md
  ON md.model_definition_id=r.model_definition_id
WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
  AND (r.build_manifest_json IS NULL OR JSON_VALID(r.build_manifest_json)<>1
    OR r.manifest_sha256 IS NULL OR OCTET_LENGTH(r.manifest_sha256)<>32
    OR r.manifest_sha256<>UNHEX(SHA2(r.build_manifest_json,256))
    OR r.generation_label<>'v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1'
    OR r.code_revision IS NULL OR TRIM(r.code_revision)='' OR r.code_revision='working-tree'
    OR r.runstep<>'COMPLETE' OR r.finished_at IS NULL
    OR r.training_count=0 OR r.validation_count=0 OR r.holdout_count=0
    OR r.split_strategy<>'STRATIFIED_HASH_80_10_10_V1')
ORDER BY r.model_definition_id;

SELECT 'Q17_AWAITING_PROVENANCE_DISTRIBUTION' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT generation_label,java_version,cdk_version,weka_version,database_schema_version,
       code_revision,split_strategy,COUNT(*) AS candidates
FROM ranked r JOIN molclass_v3.model_definition md
  ON md.model_definition_id=r.model_definition_id
WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
GROUP BY generation_label,java_version,cdk_version,weka_version,
         database_schema_version,code_revision,split_strategy
ORDER BY candidates DESC,generation_label;

SELECT 'Q18_AWAITING_JOB_AND_APPROVAL_SUMMARY' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), candidates AS (
  SELECT r.*
  FROM ranked r JOIN molclass_v3.model_definition md
    ON md.model_definition_id=r.model_definition_id
  WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
), ap AS (
  SELECT model_build_id,COUNT(*) AS approvals
  FROM molclass_v3.model_approval GROUP BY model_build_id
)
SELECT COUNT(*) AS candidate_builds,
       SUM(j.status='COMPLETED' AND j.finished_at IS NOT NULL) AS completed_jobs,
       SUM(NOT(j.status='COMPLETED' AND j.finished_at IS NOT NULL)) AS noncompleted_jobs,
       SUM(COALESCE(ap.approvals,0)=0) AS candidates_without_decision,
       SUM(COALESCE(ap.approvals,0)<>0) AS candidates_with_premature_or_duplicate_decision
FROM candidates c
LEFT JOIN molclass_v3.job j ON j.job_id=c.job_id
LEFT JOIN ap ON ap.model_build_id=c.model_build_id;

SELECT 'Q19_DUPLICATE_STALE_ANOMALY_COUNTS' AS audit_section;
WITH max_generation AS (
  SELECT model_definition_id,MAX(generation_number) AS max_generation
  FROM molclass_v3.model_build GROUP BY model_definition_id
), latest AS (
  SELECT mb.*
  FROM molclass_v3.model_build mb JOIN max_generation x
    ON x.model_definition_id=mb.model_definition_id
   AND x.max_generation=mb.generation_number
)
SELECT
 (SELECT COUNT(*) FROM (
   SELECT model_definition_id,generation_label,generation_number
   FROM molclass_v3.model_build
   GROUP BY model_definition_id,generation_label,generation_number HAVING COUNT(*)>1
 ) q) AS duplicate_generation_keys,
 (SELECT COUNT(*) FROM (
   SELECT model_definition_id,generation_number
   FROM molclass_v3.model_build
   GROUP BY model_definition_id,generation_number HAVING COUNT(*)>1
 ) q) AS generation_number_ties,
 (SELECT COUNT(*) FROM molclass_v3.model_build old
   WHERE old.status='AWAITING_APPROVAL' AND EXISTS (
     SELECT 1 FROM molclass_v3.model_build newer
     WHERE newer.model_definition_id=old.model_definition_id
       AND newer.generation_number>old.generation_number)) AS stale_awaiting_builds,
 (SELECT COUNT(*) FROM (
   SELECT model_definition_id FROM molclass_v3.model_build
   WHERE status='AWAITING_APPROVAL'
   GROUP BY model_definition_id HAVING COUNT(*)>1
 ) q) AS definitions_with_multiple_awaiting_builds,
 (SELECT COUNT(*) FROM molclass_v3.model_definition md
   LEFT JOIN latest l ON l.model_definition_id=md.model_definition_id
   WHERE (md.status='AWAITING_APPROVAL' AND COALESCE(l.status,'')<>'AWAITING_APPROVAL')
      OR (l.status='AWAITING_APPROVAL' AND md.status<>'AWAITING_APPROVAL')) AS latest_definition_status_mismatches,
 (SELECT COUNT(*) FROM molclass_v3.model_definition md
   LEFT JOIN molclass_v3.model_build pb
     ON pb.model_build_id=md.published_model_build_id
    AND pb.model_definition_id=md.model_definition_id
   WHERE md.published_model_build_id IS NOT NULL
     AND (md.status<>'ACTIVE' OR pb.status<>'PUBLISHED')) AS bad_published_pointers,
 (SELECT COUNT(*) FROM (
   SELECT model_definition_id FROM molclass_v3.model_build
   WHERE status='PUBLISHED' GROUP BY model_definition_id HAVING COUNT(*)>1
 ) q) AS definitions_with_multiple_published_builds,
 (SELECT COUNT(*) FROM molclass_v3.model_build mb
   JOIN molclass_v3.model_approval ma ON ma.model_build_id=mb.model_build_id
   WHERE mb.status='AWAITING_APPROVAL') AS decisions_on_awaiting_builds,
 (SELECT COUNT(*) FROM (
   SELECT model_build_id FROM molclass_v3.model_approval
   GROUP BY model_build_id HAVING COUNT(*)>1
 ) q) AS builds_with_duplicate_decisions,
 (SELECT COUNT(*) FROM (
   SELECT model_definition_id,MIN(generation_number) AS min_gen,
          MAX(generation_number) AS max_gen,COUNT(DISTINCT generation_number) AS generations
   FROM molclass_v3.model_build GROUP BY model_definition_id
   HAVING max_gen-min_gen+1<>generations
 ) q) AS definitions_with_generation_gaps;

SELECT 'Q20_DUPLICATE_STALE_ANOMALY_DETAILS' AS audit_section;
WITH max_generation AS (
  SELECT model_definition_id,MAX(generation_number) AS max_generation
  FROM molclass_v3.model_build GROUP BY model_definition_id
), latest AS (
  SELECT mb.*
  FROM molclass_v3.model_build mb JOIN max_generation x
    ON x.model_definition_id=mb.model_definition_id
   AND x.max_generation=mb.generation_number
)
SELECT 'GENERATION_NUMBER_TIE' AS anomaly,CAST(model_definition_id AS CHAR) AS definition_id,
       CONCAT('generation=',generation_number,', rows=',COUNT(*)) AS detail
FROM molclass_v3.model_build
GROUP BY model_definition_id,generation_number HAVING COUNT(*)>1
UNION ALL
SELECT 'STALE_AWAITING_BUILD',CAST(old.model_definition_id AS CHAR),
       CONCAT('build=',old.model_build_id,', generation=',old.generation_number)
FROM molclass_v3.model_build old
WHERE old.status='AWAITING_APPROVAL' AND EXISTS (
  SELECT 1 FROM molclass_v3.model_build newer
  WHERE newer.model_definition_id=old.model_definition_id
    AND newer.generation_number>old.generation_number)
UNION ALL
SELECT 'LATEST_DEFINITION_STATUS_MISMATCH',CAST(md.model_definition_id AS CHAR),
       CONCAT('definition=',md.status,', latest=',COALESCE(l.status,'NO_BUILD'))
FROM molclass_v3.model_definition md
LEFT JOIN latest l ON l.model_definition_id=md.model_definition_id
WHERE (md.status='AWAITING_APPROVAL' AND COALESCE(l.status,'')<>'AWAITING_APPROVAL')
   OR (l.status='AWAITING_APPROVAL' AND md.status<>'AWAITING_APPROVAL')
UNION ALL
SELECT 'GENERATION_GAP',CAST(model_definition_id AS CHAR),
       CONCAT('min=',MIN(generation_number),', max=',MAX(generation_number),
              ', distinct=',COUNT(DISTINCT generation_number))
FROM molclass_v3.model_build
GROUP BY model_definition_id
HAVING MAX(generation_number)-MIN(generation_number)+1<>COUNT(DISTINCT generation_number)
ORDER BY anomaly,definition_id;

SELECT 'Q21_FAILED_HELD_ACTIVE_DETAILS' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT md.model_definition_id,md.legacy_model_id,md.status AS definition_status,
       d.name AS dataset_name,md.algorithm_code,md.feature_selection_code,fp.profile_code,
       r.model_build_id,r.generation_number,r.status AS build_status,r.runstep,
       r.error_code,LEFT(REPLACE(REPLACE(r.error_message,'\n',' '),'\r',' '),300) AS error_message,
       j.job_id,j.status AS job_status,j.runstep AS job_runstep,
       j.heartbeat_at,j.lease_expires_at
FROM molclass_v3.model_definition md
JOIN molclass_v3.dataset d ON d.dataset_id=md.dataset_id
JOIN molclass_v3.feature_profile fp ON fp.feature_profile_id=md.feature_profile_id
LEFT JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1
LEFT JOIN molclass_v3.job j ON j.job_id=r.job_id
WHERE md.status<>'AWAITING_APPROVAL' OR COALESCE(r.status,'')<>'AWAITING_APPROVAL'
ORDER BY md.model_definition_id;

SELECT 'Q22_ALL_LATEST_DEFINITION_BUILD_MATRIX' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
), e AS (
  SELECT me.model_build_id,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
      AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
      AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
        'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')) AS required_rows,
    COUNT(DISTINCT CASE WHEN me.fold_number IS NULL AND me.class_label IS NULL
      AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
      AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
        'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
      THEN CONCAT(me.evaluation_set,':',me.metric_code) END) AS distinct_pairs,
    SUM(me.fold_number IS NULL AND me.class_label IS NULL
      AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
      AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
        'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
      AND (me.metric_value IS NULL OR
        CASE WHEN me.metric_code='KAPPA'
          THEN NOT(me.metric_value BETWEEN -1.0 AND 1.0)
          ELSE NOT(me.metric_value BETWEEN 0.0 AND 1.0) END)) AS invalid_values
  FROM molclass_v3.model_evaluation me GROUP BY me.model_build_id
), m AS (
  SELECT model_build_id,COUNT(*) AS total_members,
         SUM(partition_name='TRAIN') AS actual_train,
         SUM(partition_name='VALIDATION') AS actual_validation,
         SUM(partition_name='HOLDOUT') AS actual_holdout,
         SUM(partition_name='EXCLUDED') AS actual_excluded
  FROM molclass_v3.model_training_member GROUP BY model_build_id
), a AS (
  SELECT model_build_id,COUNT(*) AS artifact_count,
         SUM(artifact_kind='MODEL') AS model_count,
         SUM(artifact_kind='HEADER') AS header_count,
         SUM(artifact_format<>'JAVA_SERIALIZATION_WEKA_3_8_7_GZIP'
             OR media_type<>'application/gzip' OR artifact_size<=0
             OR artifact_sha256 IS NULL OR OCTET_LENGTH(artifact_sha256)<>32) AS bad_metadata
  FROM molclass_v3.model_artifact GROUP BY model_build_id
), c AS (
  SELECT model_build_id,COUNT(*) AS class_count,
         SUM(class_label IS NULL OR TRIM(class_label)='' OR support_count<=0) AS bad_classes
  FROM molclass_v3.model_class GROUP BY model_build_id
)
SELECT md.model_definition_id,md.legacy_model_id,md.dataset_id,
       md.algorithm_code,md.feature_selection_code,fp.profile_code,
       md.status AS definition_status,r.model_build_id,r.generation_number,
       COALESCE(r.status,'NO_BUILD') AS build_status,
       CASE WHEN r.model_build_id IS NULL THEN '-'
            ELSE CONCAT(r.training_count,'/',r.validation_count,'/',
                        r.holdout_count,'/',r.excluded_count) END AS train_validation_holdout_excluded,
       CASE WHEN r.status IN ('AWAITING_APPROVAL','PUBLISHED') THEN
         IF(COALESCE(e.required_rows,0)=18 AND COALESCE(e.distinct_pairs,0)=18
            AND COALESCE(e.invalid_values,0)=0,'PASS','FAIL') ELSE 'N/A' END AS metrics,
       CASE WHEN r.status IN ('AWAITING_APPROVAL','PUBLISHED','REJECTED') THEN
         IF(COALESCE(m.actual_train,0)=r.training_count
            AND COALESCE(m.actual_validation,0)=r.validation_count
            AND COALESCE(m.actual_holdout,0)=r.holdout_count
            AND COALESCE(m.actual_excluded,0)=r.excluded_count,'PASS','FAIL')
         ELSE 'N/A' END AS membership,
       CASE WHEN r.status IN ('AWAITING_APPROVAL','PUBLISHED') THEN
         IF(COALESCE(a.artifact_count,0)=2 AND COALESCE(a.model_count,0)=1
            AND COALESCE(a.header_count,0)=1 AND COALESCE(a.bad_metadata,0)=0,
            'PASS','FAIL') ELSE 'N/A' END AS artifact_metadata,
       CASE WHEN r.status IN ('AWAITING_APPROVAL','PUBLISHED') THEN
         IF(r.build_manifest_json IS NOT NULL AND JSON_VALID(r.build_manifest_json)=1
            AND r.manifest_sha256 IS NOT NULL
            AND OCTET_LENGTH(r.manifest_sha256)=32
            AND r.manifest_sha256=UNHEX(SHA2(r.build_manifest_json,256)),
            'PASS','FAIL') ELSE 'N/A' END AS manifest,
       CASE WHEN r.status IN ('AWAITING_APPROVAL','PUBLISHED') THEN
         IF(COALESCE(c.class_count,0)>=2 AND COALESCE(c.bad_classes,0)=0,
            'PASS','FAIL') ELSE 'N/A' END AS classes,
       COALESCE(j.status,'NO_JOB') AS job_status
FROM molclass_v3.model_definition md
JOIN molclass_v3.feature_profile fp ON fp.feature_profile_id=md.feature_profile_id
LEFT JOIN ranked r ON r.model_definition_id=md.model_definition_id AND r.rn=1
LEFT JOIN e ON e.model_build_id=r.model_build_id
LEFT JOIN m ON m.model_build_id=r.model_build_id
LEFT JOIN a ON a.model_build_id=r.model_build_id
LEFT JOIN c ON c.model_build_id=r.model_build_id
LEFT JOIN molclass_v3.job j ON j.job_id=r.job_id
ORDER BY md.model_definition_id;


SELECT 'Q23_AWAITING_NULL_OR_INVALID_METRIC_DETAILS' AS audit_section;
WITH ranked AS (
  SELECT mb.*,ROW_NUMBER() OVER (
    PARTITION BY model_definition_id
    ORDER BY generation_number DESC,model_build_id DESC
  ) AS rn
  FROM molclass_v3.model_build mb
)
SELECT r.model_definition_id,r.model_build_id,me.evaluation_set,me.metric_code,
       me.metric_value,me.support_count,
       CASE me.evaluation_set WHEN 'TRAIN' THEN r.training_count
         WHEN 'VALIDATION' THEN r.validation_count
         WHEN 'HOLDOUT' THEN r.holdout_count END AS expected_support,
       CASE WHEN me.metric_value IS NULL THEN 'NULL'
            WHEN me.metric_code='KAPPA' AND NOT(me.metric_value BETWEEN -1.0 AND 1.0)
              THEN 'OUT_OF_RANGE'
            WHEN me.metric_code<>'KAPPA' AND NOT(me.metric_value BETWEEN 0.0 AND 1.0)
              THEN 'OUT_OF_RANGE'
            ELSE 'VALID' END AS value_issue
FROM ranked r
JOIN molclass_v3.model_definition md ON md.model_definition_id=r.model_definition_id
JOIN molclass_v3.model_evaluation me ON me.model_build_id=r.model_build_id
WHERE r.rn=1 AND md.status='AWAITING_APPROVAL' AND r.status='AWAITING_APPROVAL'
  AND me.fold_number IS NULL AND me.class_label IS NULL
  AND me.evaluation_set IN ('TRAIN','VALIDATION','HOLDOUT')
  AND me.metric_code IN ('ACCURACY','KAPPA','WEIGHTED_PRECISION',
      'WEIGHTED_RECALL','WEIGHTED_F1','WEIGHTED_AUC')
  AND (me.metric_value IS NULL
    OR (me.metric_code='KAPPA' AND NOT(me.metric_value BETWEEN -1.0 AND 1.0))
    OR (me.metric_code<>'KAPPA' AND NOT(me.metric_value BETWEEN 0.0 AND 1.0))
    OR me.support_count IS NULL
    OR me.support_count<>CASE me.evaluation_set WHEN 'TRAIN' THEN r.training_count
         WHEN 'VALIDATION' THEN r.validation_count
         WHEN 'HOLDOUT' THEN r.holdout_count END)
ORDER BY r.model_definition_id,FIELD(me.evaluation_set,'TRAIN','VALIDATION','HOLDOUT'),
         me.metric_code;

COMMIT;
```
<!-- SQL_END -->

## Internal consistency validation

Validation is performed after this document is assembled. The checks enforce:

- exactly 118 rows in the complete latest-definition matrix;
- matrix partition `106 PASS + 9 FAIL + 3 N/A = 118`;
- exactly 34 detailed invalid metric rows;
- status totals `115 + 1 + 2 = 118`;
- algorithm totals, profile totals, and feature-selection totals each equal 115;
- no mutation statement or artifact-payload reference in the exact SQL appendix;
- no database username, password, client password variable, or credential-bearing URL in
  this document.

Validation result: **PASS**.

~~~text
matrix_rows=118
metric_pass_rows=106
metric_fail_rows=9
incomplete_rows=3
invalid_metric_detail_rows=34
sql_mutation_or_payload_references=0
credential_leak_matches=0
~~~

## Post-audit artifact payload verification

After the read-only snapshot above, the repository production audit was rerun with
`--verify-artifact-digests` on 2026-08-17. It streamed every stored artifact payload and
reported:

```text
[PASS] artifact_payload_digests: 236 artifacts verified
```

This closes the payload-size and SHA-256 verification gap for the 236 artifacts present at
that time. It does not approve any model. The overall production audit still failed because
the active `MODEL_REBUILD` job for definition 19 had no lease expiry, and it still warned
that no model had been human-approved/published.
