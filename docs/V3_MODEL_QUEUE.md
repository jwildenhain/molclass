# MolClass v3 model rebuild queue

The worker is sequential at the model level and uses a database named lock. RandomForest tree induction uses bounded internal parallelism controlled by `--threads` or `MOLCLASS_MODEL_THREADS` (default 8, maximum 64).

Run small batches so an external supervisor can enforce a wall-clock limit:

```bash
timeout --signal=TERM --kill-after=30s 1h ./gradlew rebuildV3Models -PmodelArgs='--limit 1 --threads 8'
```

If the process is killed, the next invocation marks the orphaned build and job interrupted, writes an audit event, marks that definition `REBUILD_FAILED`, and advances to the next `PENDING_REBUILD` definition. Retry a reviewed failure explicitly with `--model-id ID`.

Build runsteps are `PREPARE`, `CONFIGURE`, `LOAD_DATA`, `TRAIN`, `EVALUATE`, `SERIALIZE`, and `COMPLETE`. The job heartbeat is updated at every transition. Long classifier training remains subject to the external wall-clock supervisor.
