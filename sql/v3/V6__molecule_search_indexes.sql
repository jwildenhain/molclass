-- Exact global identifier lookup and prefix name lookup for the v3 web API.
ALTER TABLE dataset_molecule
    ADD INDEX IF NOT EXISTS ix_dataset_molecule_source_identifier (source_identifier);

ALTER TABLE molecule
    ADD INDEX IF NOT EXISTS ix_molecule_primary_name (primary_name(191));
