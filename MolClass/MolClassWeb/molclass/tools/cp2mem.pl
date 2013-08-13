#!/usr/bin/perl
#
# cp2mem.pl     Norbert Haider, University of Vienna, 2007-2010
#               norbert.haider@univie.ac.at
#
# 
# script modified to use with MolClass/ChemGRID
#
# This script is part of the MolDB5R package. Last change: 2010-04-29
#
# Example script which copies the persistent molstat and molcfp
# MySQL tables to heap-based MySQL tables, which are accessed 
# faster than the disk-based tables.
#
# After successful copying of the two tables to their memory-based
# counterparts, appropriate flags are set in the moldb_meta table
# (the two least significant bits of "memstatus").
#
# ATTENTION: the administrative user must have FILE privileges on
# the entire MySQL server (*.*), as this privilege cannot be assigned
# on a single database or even table. The scratch directory must be
# readable and writable by mysql.mysql as well as by the user running
# this script.
#
# NOTE: the MySQL variable "max_heap_table_size" must be large enough
# to accomodate the molstat table. Default is 16M which may be (much)
# too low. This variable is typically set in /etc/my.cnf, but it may
# be also set on the MySQL prompt, using the "SET GLOBAL variable=value"
# syntax (the value must be specified as number of bytes, because the
# server does not understand "K", "M", or "G").

use DBI();

$configfile = "./tools/sdftools/xmlreader.pl";
$use_fixed_fields = 0;   # 0 or 1, should be 1 for older versions of checkmol
$verbose    = 1;


$return     = do $configfile;
if (!defined $return) {
  die("ERROR: cannot read configuration file $configfile!\n");
}	

$user     = $rw_user;    # from configuration file
$password = $rw_password;


$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",
                    $user, $password,
                    {'RaiseError' => 1});

# read moldb_meta table and find out which data collections are to be processed
$ndb = 0;
@db = [""];
$sth0 = $dbh->prepare("SELECT db_id FROM $metatable WHERE (type = 1) AND (usemem = \"T\") ");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $db_id = $ref0->{'db_id'};
  $ndb++;
  @db[($ndb-1)] = $db_id;
}
$sth0->finish;

# read moldb_fpdef table and find out how many dictionaries are used (and which size)
$createstr = "";
$n_dict    = 0;
$sth0 = $dbh->prepare("SELECT fpdef, fptype FROM $fpdeftable");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $fpdef   = $ref0->{'fpdef'};
  if (length($fpdef) > 20) {
    $n_dict++;
    $dictnum = $n_dict;
    while (length($dictnum) < 2) { $dictnum = "0" . $dictnum;  }
    $fptype = $ref0->{'fptype'};
    if ($fptype == 1) {
      $createstr .= "  dfp$dictnum BIGINT NOT NULL,\n";
    } else {
      $createstr .= "  dfp$dictnum INT(11) UNSIGNED NOT NULL,\n";
    }
  }
}                 # end while ($ref...
$sth0->finish;
chomp($createstr);
if ($n_dict < 1) {
  die("ERROR: could not retrieve fingerprint definition from table $fpdeftable");
}


for ($i = 0; $i < $ndb; $i++) {
  $dbnum = @db[$i];
  #$dbprefix = $prefix . "db" . $dbnum . "_";

  if ($verbose > 0) {
    print "processing data collection $dbnum\n";
  }

  $tmpfile1 = $scratchdir . '/' . $molstattable . '.txt';
  $tmpfile2 = $scratchdir . '/' . $molcfptable . '.txt';
  
  if ( -f $tmpfile1) {
    if ($verbose > 0) {
      print "file $tmpfile1 exists already! attempting to delete it...\n";
    }
    unlink $tmpfile1;
    if ( -f $tmpfile1) {
      die("ERROR: file $tmpfile1 still exists! please remove it first.\n");
    }
  }
  
  if ( -f $tmpfile2) {
    if ($verbose > 0) {
      print "file $tmpfile2 exists already! attempting to delete it...\n";
    }
    unlink $tmpfile2;
    if ( -f $tmpfile2) {
      die("ERROR: file $tmpfile2 still exists! please remove it first.\n");
    }
  }
 
  
  if ($verbose > 0) {
    print "dumping data to $tmpfile1...\n";	
  }
  #$molstattable = $dbprefix . $molstatsuffix;
  #$molcfptable  = $dbprefix . $molcfpsuffix;

  $mem_molstattable = $molstattable . '_mem';
  $mem_molcfptable  = $molcfptable . '_mem';
  
  # drop molstat_mem table if it exists already
  $dbh->do("DROP TABLE IF EXISTS $mem_molstattable");
  
  # create a new molstat_mem table
  # use field listing from checkmol > v0.3l, else use static list
  
  $createcmd = "CREATE TABLE IF NOT EXISTS $mem_molstattable (
    mol_id int(11) NOT NULL DEFAULT '0', \n";
  open (MSDEF, "$CHECKMOL -l |");
  $nfields = 0;
  while ($line = <MSDEF>) {
    chomp($line);
    @valid = split (/:/, $line);  # ignore everything behind the colon
    $line  = $valid[0];
    if (index($line,'n_') == 0) {
      $nfields ++;
      $createcmd = $createcmd . "  $line" . " SMALLINT(6) NOT NULL DEFAULT '0',\n";
    }
  }
  $createcmd = $createcmd . "  PRIMARY KEY  (mol_id)
  ) ENGINE = MEMORY COMMENT='Molecular statistics';";
  
  
  # check if we have an older version of checkmol; if yes,
  # revert to fixed field definitions
  if (($nfields < 50) || ($use_fixed_fields == 1)) {
    if ($verbose > 0) {
      print "reverting to fixed molstat fields\n";
    }
    $createcmd="CREATE TABLE IF NOT EXISTS $mem_molstattable (
    mol_id int(11) NOT NULL DEFAULT '0',
    n_atoms smallint(6) NOT NULL DEFAULT '0',
    n_bonds smallint(6) NOT NULL DEFAULT '0',
    n_rings smallint(6) NOT NULL DEFAULT '0',
    n_QA smallint(6) NOT NULL DEFAULT '0',
    n_QB smallint(6) NOT NULL DEFAULT '0',
    n_chg smallint(6) NOT NULL DEFAULT '0',
    n_C1 smallint(6) NOT NULL DEFAULT '0',
    n_C2 smallint(6) NOT NULL DEFAULT '0',
    n_C smallint(6) NOT NULL DEFAULT '0',
    n_CHB1p smallint(6) NOT NULL DEFAULT '0',
    n_CHB2p smallint(6) NOT NULL DEFAULT '0',
    n_CHB3p smallint(6) NOT NULL DEFAULT '0',
    n_CHB4 smallint(6) NOT NULL DEFAULT '0',
    n_O2 smallint(6) NOT NULL DEFAULT '0',
    n_O3 smallint(6) NOT NULL DEFAULT '0',
    n_N1 smallint(6) NOT NULL DEFAULT '0',
    n_N2 smallint(6) NOT NULL DEFAULT '0',
    n_N3 smallint(6) NOT NULL DEFAULT '0',
    n_S smallint(6) NOT NULL DEFAULT '0',
    n_SeTe smallint(6) NOT NULL DEFAULT '0',
    n_F smallint(6) NOT NULL DEFAULT '0',
    n_Cl smallint(6) NOT NULL DEFAULT '0',
    n_Br smallint(6) NOT NULL DEFAULT '0',
    n_I smallint(6) NOT NULL DEFAULT '0',
    n_P smallint(6) NOT NULL DEFAULT '0',
    n_B smallint(6) NOT NULL DEFAULT '0',
    n_Met smallint(6) NOT NULL DEFAULT '0',
    n_X smallint(6) NOT NULL DEFAULT '0',
    n_b1 smallint(6) NOT NULL DEFAULT '0',
    n_b2 smallint(6) NOT NULL DEFAULT '0',
    n_b3 smallint(6) NOT NULL DEFAULT '0',
    n_bar smallint(6) NOT NULL DEFAULT '0',
    n_C1O smallint(6) NOT NULL DEFAULT '0',
    n_C2O smallint(6) NOT NULL DEFAULT '0',
    n_CN smallint(6) NOT NULL DEFAULT '0',
    n_XY smallint(6) NOT NULL DEFAULT '0',
    n_r3 smallint(6) NOT NULL DEFAULT '0',
    n_r4 smallint(6) NOT NULL DEFAULT '0',
    n_r5 smallint(6) NOT NULL DEFAULT '0',
    n_r6 smallint(6) NOT NULL DEFAULT '0',
    n_r7 smallint(6) NOT NULL DEFAULT '0',
    n_r8 smallint(6) NOT NULL DEFAULT '0',
    n_r9 smallint(6) NOT NULL DEFAULT '0',
    n_r10 smallint(6) NOT NULL DEFAULT '0',
    n_r11 smallint(6) NOT NULL DEFAULT '0',
    n_r12 smallint(6) NOT NULL DEFAULT '0',
    n_r13p smallint(6) NOT NULL DEFAULT '0',
    n_rN smallint(6) NOT NULL DEFAULT '0',
    n_rN1 smallint(6) NOT NULL DEFAULT '0',
    n_rN2 smallint(6) NOT NULL DEFAULT '0',
    n_rN3p smallint(6) NOT NULL DEFAULT '0',
    n_rO smallint(6) NOT NULL DEFAULT '0',
    n_rO1 smallint(6) NOT NULL DEFAULT '0',
    n_rO2p smallint(6) NOT NULL DEFAULT '0',
    n_rS smallint(6) NOT NULL DEFAULT '0',
    n_rX smallint(6) NOT NULL DEFAULT '0',
    n_rar smallint(6) NOT NULL DEFAULT '0',
    PRIMARY KEY  (mol_id)
    ) ENGINE = MEMORY COMMENT='Molecular statistics';";
  }
  
  $dbh->do($createcmd);
  
  $dbh->do("SELECT * FROM $molstattable INTO OUTFILE '$tmpfile1';");
  if ($verbose > 0) {
    print "dump finished, now loading data into new table...\n";
  }
  $dbh->do("LOAD DATA INFILE '$tmpfile1' INTO TABLE $mem_molstattable;");
  if ($verbose > 0) {
    print "done (molstat).\n";
    print "dumping data to $tmpfile2...\n";	
  }
  
  # drop molcfp_mem table if it exists already
  $dbh->do("DROP TABLE IF EXISTS $mem_molcfptable");
  
  # create a new molcfp_mem table
  $createcmd="CREATE TABLE $mem_molcfptable (mol_id INT(11), $createstr
  hfp01 INT(11) UNSIGNED NOT NULL,
  hfp02 INT(11) UNSIGNED NOT NULL,
  hfp03 INT(11) UNSIGNED NOT NULL,
  hfp04 INT(11) UNSIGNED NOT NULL,
  hfp05 INT(11) UNSIGNED NOT NULL,
  hfp06 INT(11) UNSIGNED NOT NULL,
  hfp07 INT(11) UNSIGNED NOT NULL,
  hfp08 INT(11) UNSIGNED NOT NULL,
  hfp09 INT(11) UNSIGNED NOT NULL,
  hfp10 INT(11) UNSIGNED NOT NULL,
  hfp11 INT(11) UNSIGNED NOT NULL,
  hfp12 INT(11) UNSIGNED NOT NULL,
  hfp13 INT(11) UNSIGNED NOT NULL,
  hfp14 INT(11) UNSIGNED NOT NULL,
  hfp15 INT(11) UNSIGNED NOT NULL,
  hfp16 INT(11) UNSIGNED NOT NULL,
  n_h1bits SMALLINT NOT NULL ) 
  ENGINE = MEMORY COMMENT='Combined dictionary-based and hash-based fingerprints'";
  $dbh->do($createcmd);
  
  $dbh->do("SELECT * FROM $molcfptable INTO OUTFILE '$tmpfile2';");
  if ($verbose > 0) {
    print "dump finished, now loading data into new table...\n";
  }
  $dbh->do("LOAD DATA INFILE '$tmpfile2' INTO TABLE $mem_molcfptable;");
  if ($verbose > 0) {
    print "done (molcfp).\n";
    print "dumping data to $tmpfile3...\n";	
  }
  
  # check if number of entries matches in disk table and memory table
  # start with molstat
  
  $sth1 = $dbh->prepare("SELECT COUNT(mol_id) AS molcount FROM $molstattable ");
  $sth1->execute();
  while ($ref1 = $sth1->fetchrow_hashref()) {
    $molcount = $ref1->{'molcount'};
  }
  $sth1->finish;
  $sth1 = $dbh->prepare("SELECT COUNT(mol_id) AS mem_molcount FROM $mem_molstattable");
  $sth1->execute();
  while ($ref1 = $sth1->fetchrow_hashref()) {
    $mem_molcount = $ref1->{'mem_molcount'};
  }
  $sth1->finish;

  if ($verbose > 0) {
    print "  molstat entries (disk/memory): $molcount/$mem_molcount\n";
  }
  if ($molcount == $mem_molcount) {
    $updstr = "UPDATE $metatable SET memstatus = (memstatus | 1) WHERE db_id = $dbnum";
    $dbh->do($updstr);	
  }

  # next: molcfp
  
  $sth1 = $dbh->prepare("SELECT COUNT(mol_id) AS molcount FROM $molcfptable");
  $sth1->execute();
  while ($ref1 = $sth1->fetchrow_hashref()) {
    $molcount = $ref1->{'molcount'};
  }
  $sth1->finish;
  $sth1 = $dbh->prepare("SELECT COUNT(mol_id) AS mem_molcount FROM $mem_molcfptable");
  $sth1->execute();
  while ($ref1 = $sth1->fetchrow_hashref()) {
    $mem_molcount = $ref1->{'mem_molcount'};
  }
  $sth1->finish;

  if ($verbose > 0) {
    print "  molcfp  entries (disk/memory): $molcount/$mem_molcount\n";
  }
  if ($molcount == $mem_molcount) {
    $updstr = "UPDATE $metatable SET memstatus = (memstatus | 2) WHERE db_id = $dbnum";
    $dbh->do($updstr);	
  }


}   # for $i = ...


$dbh->disconnect();
