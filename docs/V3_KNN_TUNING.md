# MolClass v3 KNN tuning contract

The legacy standalone KNN configuration used `CVParameterSelection` with
`K 25 20 18`. Weka 3.8.7 rejects that configuration because its upper bound is
below its lower bound. MolClass v3 replaces the invalid legacy value with a
deterministic, dataset-size-aware search contract.

## Search range

For a training partition containing `N` instances:

1. use `folds = min(10, N)`;
2. calculate the smallest internal CV training fold as
   `N - ceil(N / folds)`;
3. set the maximum K to the largest odd integer not exceeding both 25 and that
   smallest training fold;
4. test every odd value from 1 through the maximum;
5. when no valid range beyond 1 exists, train deterministic `IBk -K 1` without
   parameter selection.

For model definition 39, `N = 1258`, producing 10 folds and the Weka parameter
`K 1 25 13`. This evaluates `1, 3, 5, ..., 25`.

Odd values avoid the most common binary-vote ties. The cap of 25 preserves the
largest K used by the legacy MolClass ensemble implementation. Successful v3
ensemble builds provide evidence that fixed `K=25` is executable across datasets,
including a build with only 82 outer-training records.

## Reproducibility

The selected fold count and complete Weka parameter string are stored in
`model_build.build_manifest_json` as part of `algorithmContract`. A future change
to the cap, parity policy, fold calculation, or candidate count is therefore a
new model-generation contract and requires rebuilding affected models.

## Verification

`KnnTuningContractTest` checks:

- the production model-39 range;
- deterministic fallback for one to three training instances;
- every generated range for training sizes 1 through 10,000;
- upper-bound safety and odd parity;
- acceptance of each tuning string by Weka `CVParameterSelection` itself;
- rejection of an empty training partition.

Run the focused test with:

```bash
./gradlew test --tests molclass.models.KnnTuningContractTest
```

Model definition 39 rebuilt successfully as build 55 with all train, validation,
and holdout metrics plus hashed MODEL and HEADER artifacts. It remains
`AWAITING_APPROVAL`; tuning validation does not imply model approval.
