# MolClass v3 MySQL production tuning

The migrated v3 database currently has roughly 2 GB of molecule, membership, descriptor, and fingerprint data, while the local InnoDB buffer pool is 128 MB. This guarantees avoidable disk churn during model feature joins.

For a dedicated host, start with `innodb_buffer_pool_size` at 60-70 percent of RAM. On the current mixed-use 125 GB host, a conservative initial value is 8-16 GB. Apply server settings through the managed MySQL configuration and restart process, not ad-hoc application SQL.

Recommended production checks:

- Keep `innodb_flush_log_at_trx_commit=1` for durable import and approval transactions.
- Size InnoDB redo capacity for long feature/model writes; start near 4 GB and observe checkpoint pressure.
- Keep the slow query log enabled during rollout with a one-second threshold.
- Keep `max_allowed_packet` above the configured compressed artifact ceiling. The local value is 640 MB and the application default ceiling is 128 MB.
- Monitor buffer-pool hit ratio, temporary tables on disk, row-lock waits, redo waits, and model artifact growth.
- Run `ANALYZE TABLE` after the migration and bulk feature generation, then again after large imports.

Relevant indexes are defined in the v3 migrations: status/queue indexes, dataset/molecule relationship indexes, unique feature keys, published-build lookup, global SDF source identifier lookup, canonical-SMILES hash lookup, InChIKey lookup, and primary-name prefix lookup.
