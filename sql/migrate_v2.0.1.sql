-- Migration script for MolClass v2.0.1
-- Adds new CDK 2.12 descriptors to the cdk_descriptors table in the middle of the table
-- Placing them after khs_tN ensures they are ignored properly when building featureset for older Weka implementations.

ALTER TABLE cdk_descriptors
  ADD COLUMN `Fsp3` double DEFAULT NULL AFTER `khs_tN`,
  ADD COLUMN `nSmallRings` double DEFAULT NULL AFTER `Fsp3`,
  ADD COLUMN `nAromRings` double DEFAULT NULL AFTER `nSmallRings`,
  ADD COLUMN `nRingBlocks` double DEFAULT NULL AFTER `nAromRings`,
  ADD COLUMN `nAromBlocks` double DEFAULT NULL AFTER `nRingBlocks`,
  ADD COLUMN `nRings3` double DEFAULT NULL AFTER `nAromBlocks`,
  ADD COLUMN `nRings4` double DEFAULT NULL AFTER `nRings3`,
  ADD COLUMN `nRings5` double DEFAULT NULL AFTER `nRings4`,
  ADD COLUMN `nRings6` double DEFAULT NULL AFTER `nRings5`,
  ADD COLUMN `nRings7` double DEFAULT NULL AFTER `nRings6`,
  ADD COLUMN `nRings8` double DEFAULT NULL AFTER `nRings7`,
  ADD COLUMN `nRings9` double DEFAULT NULL AFTER `nRings8`,
  ADD COLUMN `tpsaEfficiency` double DEFAULT NULL AFTER `nRings9`,
  ADD COLUMN `geomShape` double DEFAULT NULL AFTER `tpsaEfficiency`;
