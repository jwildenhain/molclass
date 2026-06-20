DROP TABLE IF EXISTS `_seq`;
CREATE TABLE `_seq` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `auth`;
CREATE TABLE `auth` (
  `r_uid` varchar(100) NOT NULL,
  `r_pw` varchar(40) NOT NULL,
  `r_fn` varchar(100) NOT NULL,
  `r_ln` varchar(100) NOT NULL,
  `r_email` varchar(200) NOT NULL,
  `r_as` varchar(150) NOT NULL,
  `r_loc` varchar(150) NOT NULL,
  `r_ip` char(17) NOT NULL,
  `lastlogin` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `r_title` char(4) NOT NULL,
  `r_ts` date NOT NULL,
  `btnSubmit` varchar(6) NOT NULL,
  `r_pw_conf` varchar(40) NOT NULL,
  `r_login` varchar(20) NOT NULL,
  PRIMARY KEY (`r_uid`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `batchlist`;
CREATE TABLE `batchlist` (
  `batch_id` int(11) unsigned zerofill NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `filename` varchar(100) NOT NULL,
  `tags` text NOT NULL,
  `mol_type` varchar(20) NOT NULL,
  `pmid` varchar(20) DEFAULT NULL,
  `info` text,
  `uploaded` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`batch_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `batchmols`;
CREATE TABLE `batchmols` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `batch_id` int(11) unsigned zerofill NOT NULL DEFAULT '00000000000',
  UNIQUE KEY `mol_id` (`mol_id`,`batch_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `cdk_descriptors`;
CREATE TABLE `cdk_descriptors` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `nA` int(5) DEFAULT NULL,
  `nR` int(5) DEFAULT NULL,
  `nN` int(5) DEFAULT NULL,
  `nD` int(5) DEFAULT NULL,
  `nC` int(5) DEFAULT NULL,
  `nF` int(5) DEFAULT NULL,
  `nQ` int(5) DEFAULT NULL,
  `nE` int(5) DEFAULT NULL,
  `nG` int(5) DEFAULT NULL,
  `nH` int(5) DEFAULT NULL,
  `nI` int(5) DEFAULT NULL,
  `nP` int(5) DEFAULT NULL,
  `nL` int(5) DEFAULT NULL,
  `nK` int(5) DEFAULT NULL,
  `nM` int(5) DEFAULT NULL,
  `nS` int(5) DEFAULT NULL,
  `nT` int(5) DEFAULT NULL,
  `nY` int(5) DEFAULT NULL,
  `nV` int(5) DEFAULT NULL,
  `nW` int(5) DEFAULT NULL,
  `apol` double DEFAULT NULL,
  `naAromAtom` int(5) DEFAULT NULL,
  `nAromBond` int(5) DEFAULT NULL,
  `nAtom` int(5) DEFAULT NULL,
  `ATSc1` double DEFAULT NULL,
  `ATSc2` double DEFAULT NULL,
  `ATSc3` double DEFAULT NULL,
  `ATSc4` double DEFAULT NULL,
  `ATSc5` double DEFAULT NULL,
  `ATSm1` double DEFAULT NULL,
  `ATSm2` double DEFAULT NULL,
  `ATSm3` double DEFAULT NULL,
  `ATSm4` double DEFAULT NULL,
  `ATSm5` double DEFAULT NULL,
  `ATSp1` double DEFAULT NULL,
  `ATSp2` double DEFAULT NULL,
  `ATSp3` double DEFAULT NULL,
  `ATSp4` double DEFAULT NULL,
  `ATSp5` double DEFAULT NULL,
  `BCUTw_1l` double DEFAULT NULL,
  `BCUTw_1h` double DEFAULT NULL,
  `BCUTc_1l` double DEFAULT NULL,
  `BCUTc_1h` double DEFAULT NULL,
  `BCUTp_1l` double DEFAULT NULL,
  `BCUTp_1h` double DEFAULT NULL,
  `nB` int(5) DEFAULT NULL,
  `bpol` double DEFAULT NULL,
  `chi0C` double DEFAULT NULL,
  `chi1C` double DEFAULT NULL,
  `SCH_3` double DEFAULT NULL,
  `SCH_4` double DEFAULT NULL,
  `SCH_5` double DEFAULT NULL,
  `SCH_6` double DEFAULT NULL,
  `VCH_3` double DEFAULT NULL,
  `VCH_4` double DEFAULT NULL,
  `VCH_5` double DEFAULT NULL,
  `VCH_6` double DEFAULT NULL,
  `SC_3` double DEFAULT NULL,
  `SC_4` double DEFAULT NULL,
  `SC_5` double DEFAULT NULL,
  `SC_6` double DEFAULT NULL,
  `SPC_4` double DEFAULT NULL,
  `SPC_5` double DEFAULT NULL,
  `SPC_6` double DEFAULT NULL,
  `VPC_4` double DEFAULT NULL,
  `VPC_5` double DEFAULT NULL,
  `VPC_6` double DEFAULT NULL,
  `VC_3` double DEFAULT NULL,
  `VC_4` double DEFAULT NULL,
  `VC_5` double DEFAULT NULL,
  `VC_6` double DEFAULT NULL,
  `VP_8` double DEFAULT NULL,
  `VP_9` double DEFAULT NULL,
  `VP_10` double DEFAULT NULL,
  `VP_11` double DEFAULT NULL,
  `VP_12` double DEFAULT NULL,
  `VP_13` double DEFAULT NULL,
  `VP_14` double DEFAULT NULL,
  `VP_15` double DEFAULT NULL,
  `SP_0` double DEFAULT NULL,
  `SP_1` double DEFAULT NULL,
  `SP_2` double DEFAULT NULL,
  `SP_3` double DEFAULT NULL,
  `SP_4` double DEFAULT NULL,
  `SP_5` double DEFAULT NULL,
  `SP_6` double DEFAULT NULL,
  `SP_7` double DEFAULT NULL,
  `PPSA_1` double DEFAULT NULL,
  `PPSA_2` double DEFAULT NULL,
  `PPSA_3` double DEFAULT NULL,
  `PNSA_1` double DEFAULT NULL,
  `PNSA_2` double DEFAULT NULL,
  `PNSA_3` double DEFAULT NULL,
  `DPSA_1` double DEFAULT NULL,
  `DPSA_2` double DEFAULT NULL,
  `DPSA_3` double DEFAULT NULL,
  `FPSA_1` double DEFAULT NULL,
  `FPSA_2` double DEFAULT NULL,
  `FPSA_3` double DEFAULT NULL,
  `FNSA_1` double DEFAULT NULL,
  `FNSA_2` double DEFAULT NULL,
  `FNSA_3` double DEFAULT NULL,
  `WPSA_1` double DEFAULT NULL,
  `WPSA_2` double DEFAULT NULL,
  `WPSA_3` double DEFAULT NULL,
  `WNSA_1` double DEFAULT NULL,
  `WNSA_2` double DEFAULT NULL,
  `WNSA_3` double DEFAULT NULL,
  `RPCG` double DEFAULT NULL,
  `RNCG` double DEFAULT NULL,
  `RPCS` double DEFAULT NULL,
  `RNCS` double DEFAULT NULL,
  `THSA` double DEFAULT NULL,
  `TPSA` double DEFAULT NULL,
  `RHSA` double DEFAULT NULL,
  `RPSA` double DEFAULT NULL,
  `ECCEN` int(5) DEFAULT NULL,
  `fragC` double DEFAULT NULL,
  `nHBAcc` int(5) DEFAULT NULL,
  `nHBDon` int(5) DEFAULT NULL,
  `Kier1` double DEFAULT NULL,
  `Kier2` double DEFAULT NULL,
  `Kier3` double DEFAULT NULL,
  `nAtomP` int(5) DEFAULT NULL,
  `nAtomLAC` int(5) DEFAULT NULL,
  `nAtomLC` int(5) DEFAULT NULL,
  `MDEC_11` double DEFAULT NULL,
  `MDEC_12` double DEFAULT NULL,
  `MDEC_13` double DEFAULT NULL,
  `MDEC_14` double DEFAULT NULL,
  `MDEC_22` double DEFAULT NULL,
  `MDEC_23` double DEFAULT NULL,
  `MDEC_24` double DEFAULT NULL,
  `MDEC_33` double DEFAULT NULL,
  `MDEC_34` double DEFAULT NULL,
  `MDEC_44` double DEFAULT NULL,
  `MDEO_11` double DEFAULT NULL,
  `MDEO_12` double DEFAULT NULL,
  `MDEO_22` double DEFAULT NULL,
  `MDEN_11` double DEFAULT NULL,
  `MDEN_12` double DEFAULT NULL,
  `MDEN_13` double DEFAULT NULL,
  `MDEN_22` double DEFAULT NULL,
  `MDEN_23` double DEFAULT NULL,
  `MDEN_33` double DEFAULT NULL,
  `PetitjeanNumber` double DEFAULT NULL,
  `nRotB` int(5) DEFAULT NULL,
  `TopoPSA` double DEFAULT NULL,
  `VABC` double DEFAULT NULL,
  `vAdjMat` double DEFAULT NULL,
  `chi0vC` double DEFAULT NULL,
  `chi1vC` double DEFAULT NULL,
  `MW` double DEFAULT NULL,
  `WPATH` double DEFAULT NULL,
  `WPOL` double DEFAULT NULL,
  `XLogP` double DEFAULT NULL,
  `Zagreb` double DEFAULT NULL,
  `DoubleResult` double DEFAULT NULL,
  `ALogP` double DEFAULT NULL,
  `ALogP2` double DEFAULT NULL,
  `nAcid` int(4) DEFAULT NULL,
  `nBase` int(4) DEFAULT NULL,
  `C1SP1` int(4) DEFAULT NULL,
  `SCH_7` double DEFAULT NULL,
  `VP_0` double DEFAULT NULL,
  `FMF` double DEFAULT NULL,
  `HybRatio` double DEFAULT NULL,
  `khs_sLi` double DEFAULT NULL,
  `MLogP` double DEFAULT NULL,
  `topoShape` double DEFAULT NULL,
  `AMR` double DEFAULT NULL,
  `khs_ssBe` double DEFAULT NULL,
  `LipinskiFailures` int(4) DEFAULT NULL,
  `WTPT_3` double DEFAULT NULL,
  `C2SP1` int(4) DEFAULT NULL,
  `VCH_7` double DEFAULT NULL,
  `VP_1` double DEFAULT NULL,
  `C1SP2` int(4) DEFAULT NULL,
  `VP_2` double DEFAULT NULL,
  `khs_ssssBe` double DEFAULT NULL,
  `VP_3` double DEFAULT NULL,
  `C2SP2` int(4) DEFAULT NULL,
  `khs_ssBH` double DEFAULT NULL,
  `WTPT1` double DEFAULT NULL,
  `khs_sssB` double DEFAULT NULL,
  `C3SP2` int(4) DEFAULT NULL,
  `WTPT_4` double DEFAULT NULL,
  `VP_4` double DEFAULT NULL,
  `WTPT_5` double DEFAULT NULL,
  `khs_ssssB` double DEFAULT NULL,
  `C1SP3` int(4) DEFAULT NULL,
  `VP_5` double DEFAULT NULL,
  `khs_sCH3` double DEFAULT NULL,
  `C2SP3` int(4) DEFAULT NULL,
  `VP_6` double DEFAULT NULL,
  `khs_dCH2` double DEFAULT NULL,
  `C3SP3` int(4) DEFAULT NULL,
  `VP_7` double DEFAULT NULL,
  `khs_ssCH2` double DEFAULT NULL,
  `WTPT_1` double DEFAULT NULL,
  `WTPT_2` double DEFAULT NULL,
  `khs_tCH` double DEFAULT NULL,
  `C4SP3` int(4) DEFAULT NULL,
  `khs_dsCH` double DEFAULT NULL,
  `khs_aaCH` double DEFAULT NULL,
  `khs_sssCH` double DEFAULT NULL,
  `khs_ddC` double DEFAULT NULL,
  `khs_tsC` double DEFAULT NULL,
  `khs_dssC` double DEFAULT NULL,
  `khs_aasC` double DEFAULT NULL,
  `khs_aaaC` double DEFAULT NULL,
  `khs_ssssC` double DEFAULT NULL,
  `khs_sNH3` double DEFAULT NULL,
  `khs_sNH2` double DEFAULT NULL,
  `khs_ssNH2` double DEFAULT NULL,
  `khs_dNH` double DEFAULT NULL,
  `khs_ssNH` double DEFAULT NULL,
  `khs_aaNH` double DEFAULT NULL,
  `khs_tNH` double DEFAULT NULL,
  `khs_tN` double DEFAULT NULL,
  `Fsp3` double DEFAULT NULL,
  `nSmallRings` double DEFAULT NULL,
  `nAromRings` double DEFAULT NULL,
  `nRingBlocks` double DEFAULT NULL,
  `nAromBlocks` double DEFAULT NULL,
  `nRings3` double DEFAULT NULL,
  `nRings4` double DEFAULT NULL,
  `nRings5` double DEFAULT NULL,
  `nRings6` double DEFAULT NULL,
  `nRings7` double DEFAULT NULL,
  `nRings8` double DEFAULT NULL,
  `nRings9` double DEFAULT NULL,
  `tpsaEfficiency` double DEFAULT NULL,
  `geomShape` double DEFAULT NULL,
  `khs_sssNH` double DEFAULT NULL,
  `khs_dsN` double DEFAULT NULL,
  `khs_aaN` double DEFAULT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `class_models`;
CREATE TABLE `class_models` (
  `model_id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(128) DEFAULT NULL,
  `model_data` longblob,
  `header` longblob,
  `classes` text,
  `username` varchar(100) DEFAULT NULL,
  `batch_id` int(11) DEFAULT NULL,
  `data_type` varchar(10) DEFAULT NULL,
  `class_tag` varchar(40) DEFAULT NULL,
  `class_scheme` varchar(30) DEFAULT NULL,
  `email` varchar(40) DEFAULT NULL,
  `printout` longtext,
  PRIMARY KEY (`model_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `class_schemes`;
CREATE TABLE `class_schemes` (
  `class_scheme` varchar(30) DEFAULT NULL
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `data_types`;
CREATE TABLE `data_types` (
  `data_type` varchar(10) DEFAULT NULL
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `fingerprints`;
CREATE TABLE `fingerprints` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `MACCS` text,
  `EXT` text,
  `PubChem` text,
  `KR` text,
  `SUB` text,
  `sub_status` enum("ok", "fallback", "error") NOT NULL DEFAULT "ok",
  `sub_status_message` varchar(255) DEFAULT NULL,
  `GOFP` text,
  `ESFP` text,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `inchi_key`;
CREATE TABLE `inchi_key` (
  `mol_id` int(11) NOT NULL,
  `inchi_key` char(35) DEFAULT NULL,
  `mol_type` varchar(20) DEFAULT 'learn',
  `smiles` text,
  `inchi` mediumtext,
  PRIMARY KEY (`mol_id`),
  KEY `inchi_key` (`inchi_key`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `moldb_fpdef`;
CREATE TABLE `moldb_fpdef` (
  `fp_id` int(11) DEFAULT NULL,
  `fpdef` mediumblob,
  `fptype` smallint(6) DEFAULT NULL
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `moldb_meta`;
CREATE TABLE `moldb_meta` (
  `db_id` int(11) NOT NULL,
  `type` tinyint(4) unsigned NOT NULL DEFAULT '1' COMMENT '1 = substance, 2 = reaction, 3 = combined',
  `access` tinyint(4) unsigned NOT NULL DEFAULT '1' COMMENT '0=hidden, 1=read-only, 2=add/update, 3=full access',
  `name` varbinary(255) NOT NULL,
  `description` tinytext CHARACTER SET latin1 NOT NULL,
  `usemem` enum('T','F') NOT NULL DEFAULT 'F',
  `memstatus` tinyint(4) unsigned NOT NULL DEFAULT '0',
  `digits` tinyint(4) unsigned NOT NULL DEFAULT '8',
  `subdirdigits` tinyint(4) unsigned NOT NULL DEFAULT '4',
  `trustedIP` varbinary(255) NOT NULL,
  PRIMARY KEY (`db_id`)
) ENGINE=MyISAM DEFAULT CHARSET=binary COMMENT='meta information about MolDB5R data collections';

DROP TABLE IF EXISTS `moldb_molcfp`;
CREATE TABLE `moldb_molcfp` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `dfp01` bigint(20) NOT NULL,
  `hfp01` int(11) unsigned NOT NULL,
  `hfp02` int(11) unsigned NOT NULL,
  `hfp03` int(11) unsigned NOT NULL,
  `hfp04` int(11) unsigned NOT NULL,
  `hfp05` int(11) unsigned NOT NULL,
  `hfp06` int(11) unsigned NOT NULL,
  `hfp07` int(11) unsigned NOT NULL,
  `hfp08` int(11) unsigned NOT NULL,
  `hfp09` int(11) unsigned NOT NULL,
  `hfp10` int(11) unsigned NOT NULL,
  `hfp11` int(11) unsigned NOT NULL,
  `hfp12` int(11) unsigned NOT NULL,
  `hfp13` int(11) unsigned NOT NULL,
  `hfp14` int(11) unsigned NOT NULL,
  `hfp15` int(11) unsigned NOT NULL,
  `hfp16` int(11) unsigned NOT NULL,
  `n_h1bits` smallint(6) NOT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1 COMMENT='Combined dictionary-based and hash-based fingerprints';

DROP TABLE IF EXISTS `moldb_molcfp_mem`;
CREATE TABLE `moldb_molcfp_mem` (
  `mol_id` int(11) DEFAULT NULL,
  `dfp01` bigint(20) NOT NULL,
  `hfp01` int(11) unsigned NOT NULL,
  `hfp02` int(11) unsigned NOT NULL,
  `hfp03` int(11) unsigned NOT NULL,
  `hfp04` int(11) unsigned NOT NULL,
  `hfp05` int(11) unsigned NOT NULL,
  `hfp06` int(11) unsigned NOT NULL,
  `hfp07` int(11) unsigned NOT NULL,
  `hfp08` int(11) unsigned NOT NULL,
  `hfp09` int(11) unsigned NOT NULL,
  `hfp10` int(11) unsigned NOT NULL,
  `hfp11` int(11) unsigned NOT NULL,
  `hfp12` int(11) unsigned NOT NULL,
  `hfp13` int(11) unsigned NOT NULL,
  `hfp14` int(11) unsigned NOT NULL,
  `hfp15` int(11) unsigned NOT NULL,
  `hfp16` int(11) unsigned NOT NULL,
  `n_h1bits` smallint(6) NOT NULL
) ENGINE=MEMORY DEFAULT CHARSET=latin1 COMMENT='Combined dictionary-based and hash-based fingerprints';

DROP TABLE IF EXISTS `moldb_moldata`;
CREATE TABLE `moldb_moldata` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `mol_name` varchar(255) NOT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `moldb_molfgb`;
CREATE TABLE `moldb_molfgb` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `fg01` int(11) unsigned NOT NULL,
  `fg02` int(11) unsigned NOT NULL,
  `fg03` int(11) unsigned NOT NULL,
  `fg04` int(11) unsigned NOT NULL,
  `fg05` int(11) unsigned NOT NULL,
  `fg06` int(11) unsigned NOT NULL,
  `fg07` int(11) unsigned NOT NULL,
  `fg08` int(11) unsigned NOT NULL,
  `n_1bits` smallint(6) NOT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1 COMMENT='Functional group patterns';

DROP TABLE IF EXISTS `moldb_molstat`;
CREATE TABLE `moldb_molstat` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `n_atoms` smallint(6) NOT NULL DEFAULT '0',
  `n_bonds` smallint(6) NOT NULL DEFAULT '0',
  `n_rings` smallint(6) NOT NULL DEFAULT '0',
  `n_QA` smallint(6) NOT NULL DEFAULT '0',
  `n_QB` smallint(6) NOT NULL DEFAULT '0',
  `n_chg` smallint(6) NOT NULL DEFAULT '0',
  `n_C1` smallint(6) NOT NULL DEFAULT '0',
  `n_C2` smallint(6) NOT NULL DEFAULT '0',
  `n_C` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB1p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB2p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB3p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB4` smallint(6) NOT NULL DEFAULT '0',
  `n_O2` smallint(6) NOT NULL DEFAULT '0',
  `n_O3` smallint(6) NOT NULL DEFAULT '0',
  `n_N1` smallint(6) NOT NULL DEFAULT '0',
  `n_N2` smallint(6) NOT NULL DEFAULT '0',
  `n_N3` smallint(6) NOT NULL DEFAULT '0',
  `n_S` smallint(6) NOT NULL DEFAULT '0',
  `n_SeTe` smallint(6) NOT NULL DEFAULT '0',
  `n_F` smallint(6) NOT NULL DEFAULT '0',
  `n_Cl` smallint(6) NOT NULL DEFAULT '0',
  `n_Br` smallint(6) NOT NULL DEFAULT '0',
  `n_I` smallint(6) NOT NULL DEFAULT '0',
  `n_P` smallint(6) NOT NULL DEFAULT '0',
  `n_B` smallint(6) NOT NULL DEFAULT '0',
  `n_Met` smallint(6) NOT NULL DEFAULT '0',
  `n_X` smallint(6) NOT NULL DEFAULT '0',
  `n_b1` smallint(6) NOT NULL DEFAULT '0',
  `n_b2` smallint(6) NOT NULL DEFAULT '0',
  `n_b3` smallint(6) NOT NULL DEFAULT '0',
  `n_bar` smallint(6) NOT NULL DEFAULT '0',
  `n_C1O` smallint(6) NOT NULL DEFAULT '0',
  `n_C2O` smallint(6) NOT NULL DEFAULT '0',
  `n_CN` smallint(6) NOT NULL DEFAULT '0',
  `n_XY` smallint(6) NOT NULL DEFAULT '0',
  `n_r3` smallint(6) NOT NULL DEFAULT '0',
  `n_r4` smallint(6) NOT NULL DEFAULT '0',
  `n_r5` smallint(6) NOT NULL DEFAULT '0',
  `n_r6` smallint(6) NOT NULL DEFAULT '0',
  `n_r7` smallint(6) NOT NULL DEFAULT '0',
  `n_r8` smallint(6) NOT NULL DEFAULT '0',
  `n_r9` smallint(6) NOT NULL DEFAULT '0',
  `n_r10` smallint(6) NOT NULL DEFAULT '0',
  `n_r11` smallint(6) NOT NULL DEFAULT '0',
  `n_r12` smallint(6) NOT NULL DEFAULT '0',
  `n_r13p` smallint(6) NOT NULL DEFAULT '0',
  `n_rN` smallint(6) NOT NULL DEFAULT '0',
  `n_rN1` smallint(6) NOT NULL DEFAULT '0',
  `n_rN2` smallint(6) NOT NULL DEFAULT '0',
  `n_rN3p` smallint(6) NOT NULL DEFAULT '0',
  `n_rO` smallint(6) NOT NULL DEFAULT '0',
  `n_rO1` smallint(6) NOT NULL DEFAULT '0',
  `n_rO2p` smallint(6) NOT NULL DEFAULT '0',
  `n_rS` smallint(6) NOT NULL DEFAULT '0',
  `n_rX` smallint(6) NOT NULL DEFAULT '0',
  `n_rar` smallint(6) NOT NULL DEFAULT '0',
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1 COMMENT='Molecular statistics';

DROP TABLE IF EXISTS `moldb_molstat_mem`;
CREATE TABLE `moldb_molstat_mem` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `n_atoms` smallint(6) NOT NULL DEFAULT '0',
  `n_bonds` smallint(6) NOT NULL DEFAULT '0',
  `n_rings` smallint(6) NOT NULL DEFAULT '0',
  `n_QA` smallint(6) NOT NULL DEFAULT '0',
  `n_QB` smallint(6) NOT NULL DEFAULT '0',
  `n_chg` smallint(6) NOT NULL DEFAULT '0',
  `n_C1` smallint(6) NOT NULL DEFAULT '0',
  `n_C2` smallint(6) NOT NULL DEFAULT '0',
  `n_C` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB1p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB2p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB3p` smallint(6) NOT NULL DEFAULT '0',
  `n_CHB4` smallint(6) NOT NULL DEFAULT '0',
  `n_O2` smallint(6) NOT NULL DEFAULT '0',
  `n_O3` smallint(6) NOT NULL DEFAULT '0',
  `n_N1` smallint(6) NOT NULL DEFAULT '0',
  `n_N2` smallint(6) NOT NULL DEFAULT '0',
  `n_N3` smallint(6) NOT NULL DEFAULT '0',
  `n_S` smallint(6) NOT NULL DEFAULT '0',
  `n_SeTe` smallint(6) NOT NULL DEFAULT '0',
  `n_F` smallint(6) NOT NULL DEFAULT '0',
  `n_Cl` smallint(6) NOT NULL DEFAULT '0',
  `n_Br` smallint(6) NOT NULL DEFAULT '0',
  `n_I` smallint(6) NOT NULL DEFAULT '0',
  `n_P` smallint(6) NOT NULL DEFAULT '0',
  `n_B` smallint(6) NOT NULL DEFAULT '0',
  `n_Met` smallint(6) NOT NULL DEFAULT '0',
  `n_X` smallint(6) NOT NULL DEFAULT '0',
  `n_b1` smallint(6) NOT NULL DEFAULT '0',
  `n_b2` smallint(6) NOT NULL DEFAULT '0',
  `n_b3` smallint(6) NOT NULL DEFAULT '0',
  `n_bar` smallint(6) NOT NULL DEFAULT '0',
  `n_C1O` smallint(6) NOT NULL DEFAULT '0',
  `n_C2O` smallint(6) NOT NULL DEFAULT '0',
  `n_CN` smallint(6) NOT NULL DEFAULT '0',
  `n_XY` smallint(6) NOT NULL DEFAULT '0',
  `n_r3` smallint(6) NOT NULL DEFAULT '0',
  `n_r4` smallint(6) NOT NULL DEFAULT '0',
  `n_r5` smallint(6) NOT NULL DEFAULT '0',
  `n_r6` smallint(6) NOT NULL DEFAULT '0',
  `n_r7` smallint(6) NOT NULL DEFAULT '0',
  `n_r8` smallint(6) NOT NULL DEFAULT '0',
  `n_r9` smallint(6) NOT NULL DEFAULT '0',
  `n_r10` smallint(6) NOT NULL DEFAULT '0',
  `n_r11` smallint(6) NOT NULL DEFAULT '0',
  `n_r12` smallint(6) NOT NULL DEFAULT '0',
  `n_r13p` smallint(6) NOT NULL DEFAULT '0',
  `n_rN` smallint(6) NOT NULL DEFAULT '0',
  `n_rN1` smallint(6) NOT NULL DEFAULT '0',
  `n_rN2` smallint(6) NOT NULL DEFAULT '0',
  `n_rN3p` smallint(6) NOT NULL DEFAULT '0',
  `n_rO` smallint(6) NOT NULL DEFAULT '0',
  `n_rO1` smallint(6) NOT NULL DEFAULT '0',
  `n_rO2p` smallint(6) NOT NULL DEFAULT '0',
  `n_rS` smallint(6) NOT NULL DEFAULT '0',
  `n_rX` smallint(6) NOT NULL DEFAULT '0',
  `n_rar` smallint(6) NOT NULL DEFAULT '0',
  PRIMARY KEY (`mol_id`)
) ENGINE=MEMORY DEFAULT CHARSET=latin1 COMMENT='Molecular statistics';

DROP TABLE IF EXISTS `moldb_molstruc`;
CREATE TABLE `moldb_molstruc` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `struc` mediumblob NOT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `moldb_pic2d`;
CREATE TABLE `moldb_pic2d` (
  `mol_id` int(11) NOT NULL DEFAULT '0',
  `type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '1 = png',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '0 = does not exist, 1 = OK, 2 = OK, but do not show, 3 = to be created/updated, 4 = to be deleted',
  `svg` blob NOT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1 COMMENT='Housekeeping for 2D depiction';

DROP TABLE IF EXISTS `plate_info`;
CREATE TABLE `plate_info` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `plate_num` int(3) DEFAULT NULL,
  `plate_row` varchar(1) DEFAULT NULL,
  `plate_col` int(3) DEFAULT NULL
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `prediction_list`;
CREATE TABLE `prediction_list` (
  `pred_id` int(11) NOT NULL AUTO_INCREMENT,
  `pred_name` varchar(255) NOT NULL,
  `model_id` int(11) NOT NULL,
  `batch_id` int(11) NOT NULL,
  `username` varchar(100) NOT NULL,
  `email` varchar(40) NOT NULL,
  `printout` longtext,
  PRIMARY KEY (`pred_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `prediction_mols`;
CREATE TABLE `prediction_mols` (
  `mol_id` int(11) NOT NULL,
  `pred_id` int(11) NOT NULL,
  `main_class` varchar(20) NOT NULL,
  `distribution` text NOT NULL,
  `lhood` double NOT NULL,
  UNIQUE KEY `mol_id` (`mol_id`,`pred_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `sdftags`;
CREATE TABLE `sdftags` (
  `mol_id` int(8) unsigned zerofill NOT NULL DEFAULT '00000000',
  `external_predictor` double DEFAULT NULL,
  `classifier` varchar(80) DEFAULT NULL,
  `activity_class` varchar(40) DEFAULT NULL,
  `z_score` double DEFAULT NULL,
  `compound_name` varchar(255) DEFAULT NULL,
  `plate_row` varchar(10) DEFAULT NULL,
  `plate_column` int(11) DEFAULT NULL,
  `plate_number` int(11) DEFAULT NULL,
  `cas_no` varchar(40) DEFAULT NULL,
  `chembankid` int(11) DEFAULT NULL,
  `compositez` double DEFAULT NULL,
  `reproducibility` double DEFAULT NULL,
  `pubchem_compound_cid` int(11) DEFAULT NULL,
  `rankscore` int(11) DEFAULT NULL,
  `p_value` double NOT NULL,
  `external_value` double DEFAULT NULL,
  `class` varchar(80) DEFAULT NULL,
  `standard_value` int(11) DEFAULT NULL,
  `standard_units` varchar(80) DEFAULT NULL,
  `standard_type` varchar(160) DEFAULT NULL,
  `chembl_id` int(11) DEFAULT NULL,
  `vendor_id` varchar(40) DEFAULT NULL,
  `logd` double DEFAULT NULL,
  `rgyr` double DEFAULT NULL,
  `hcpsa` double DEFAULT NULL,
  `frotb` double DEFAULT NULL,
  `smiles` text,
  `canonical_smiles` text,
  `caco2` double DEFAULT NULL,
  PRIMARY KEY (`mol_id`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `tanimoto`;
CREATE TABLE `tanimoto` (
  `mol_id1` int(11) NOT NULL,
  `mol_id2` int(11) NOT NULL,
  `ext` decimal(5,4) NOT NULL,
  `kr` decimal(5,4) NOT NULL,
  PRIMARY KEY (`mol_id1`,`mol_id2`),
  KEY `ext` (`ext`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

DROP TABLE IF EXISTS `timeout_mols`;
CREATE TABLE `timeout_mols` (
  `mol_id` int(11) NOT NULL
) ENGINE=MyISAM DEFAULT CHARSET=latin1;

