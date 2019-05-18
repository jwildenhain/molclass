#!/usr/bin/perl

use DBI();

$wwwserver = "http://bio-sbsr-2059.bio.ed.ac.uk/";
$wwwuser = "~ryukimura";
$configfile = "/home/ryukimura/public_html/molclass/tools/sdftools/xmlreader.pl";

$return     = do $configfile;
if (!defined $return) {
  die ("cannot read configuration file $configfile!\n");
}

$batch_id = $ARGV[0];

### Connect to DB ###
$user     = $rw_user;    # from configuration file
$password = $rw_password;

$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",$user, $password,
                    { RaiseError => 1}
                    ) || die ("database connection failed: $DBI::errstr");

### Delete SDF File with associated prediction and models ###
$query = "DELETE FROM `batchlist` WHERE batch_id = $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM cdk_descriptors USING cdk_descriptors, batchmols WHERE cdk_descriptors.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM `class_models` WHERE batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM fingerprints USING fingerprints, batchmols WHERE fingerprints.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM inchi_key USING inchi_key, batchmols WHERE inchi_key.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_molbfp USING moldb_molbfp, batchmols WHERE moldb_molbfp.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_moldata USING moldb_moldata, batchmols WHERE moldb_moldata.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_molfgb USING moldb_molfgb, batchmols WHERE moldb_molfgb.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_molhfp USING moldb_molhfp, batchmols WHERE moldb_molhfp.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_molstat USING moldb_molstat, batchmols WHERE moldb_molstat.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM moldb_molstruc USING moldb_molstruc, batchmols WHERE moldb_molstruc.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM `prediction_list` WHERE batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM prediction_mols USING prediction_mols, batchmols WHERE prediction_mols.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM sdftags USING sdftags, batchmols WHERE sdftags.mol_id = batchmols.mol_id AND batch_id = $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM timeout_mols USING timeout_mols, batchmols WHERE timeout_mols.mol_id = batchmols.mol_id AND batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;
$query = "DELETE FROM `batchmols` WHERE batch_id =  $batch_id";
$sth = $dbh->prepare("$query");
$sth->execute;

