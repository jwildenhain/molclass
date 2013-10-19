#!/usr/bin/perl
#
# sdf2moldb.pl   Norbert Haider, University of Vienna, 2009-2013
#                norbert.haider@univie.ac.at
#
# This script is part of the MolDB5R package. Last change: 2013-06-10
#
# This script reads an SDF file which was previously analyzed by the script 
# "sdfcheck.pl" and adds its content (structures and data) into a MySQL-
# based MolDB5R database. The auxiliary tables are also created, SVG graphics
# are created and optionally bitmap graphics in PNG format are created.
#
# ===========================================================================
#
# Modified to work with MolClass v1.3 and Chemgrid v1.0
# author of modifications: jw
#

$verbose          = 1;   # 0, 1 or 2
$askuser          = 0;   # 0 or 1, change to 0 for skipping the confirmation
$use_fixed_fields = 0;   # 0 or 1, should be 1 for older versions of checkmol
$append           = 1;   # 0 or 1 (if 0, all existing data will be erased)
$make_bitmaps     = 1;   # 0 or 1 (1 requires write permission in directory)
                         # setting $make_bitmaps to 0 will speed up data
                         # import considerably, you can later generate the
                         # bitmap files with the script "updatebitmap.pl"
$chkzero          = 1;   # 0 or 1, check if molecule contains > 1 atoms with X,Y,Z = 0,0,0


# define some defaults, may be overridden by conf file

$MOL2SVG        = "/usr/local/bin/mol2svg"; # covered by xmlreader
$mol2svgopt     = "--rotate=auto3Donly --hydrogenonmethyl=off --color=/usr/local/etc/color.conf"; # options for mol2svg, e.g. "--showmolname=on"
#$mol2svgopt     = "--rotate=auto3Donly --hydrogenonmethyl=off"; # options for mol2svg, e.g. "--showmolname=on"
#$mol2svgopt_rxn = "-R --rotate=auto3Donly --hydrogenonmethyl=off"; # options for mol2svg in reaction mode
$svg_scalingfactor = 1.0;           # 1.0  gives good results
#$svg_scalingfactor_rxn = 0.75;      # 0.75 is a good compromise for reactions


if ($#ARGV < 0) {
  print "Usage: sdf2moldb.pl <inputfile>\n";
  exit;
}

use File::Basename;

$infile = $ARGV[0];

#
# molclass/chemgrid
#
$username = $ARGV[1];
$email = $ARGV[2];
$mol_type = $ARGV[3];
$pmid = $ARGV[4];
$info = $ARGV[5];
$id= $ARGV[6];
$inbase = basename($infile);
$indir = dirname($infile);

#
# molclass/chemgrid
#

use DBI();
$ostype = getostype();
if ($ostype eq 2) { use File::Temp qw/ tempfile tempdir /; }

#
# Read MolClass/ChemGRID Config File 
#
$configfile = "./tools/sdftools/xmlreader.pl";

$return     = do $configfile;
if (!defined $return) {
  die ("cannot read configuration file $configfile!\n");
}

#
#
#	

# assuming mol2svg version 0.4 or higher: scaling is added to the options, 
# if not already there
if (index($mol2svgopt, '--scaling=') < 0) {
  $mol2svgopt .= " --scaling=" . $svg_scalingfactor; 
}

$user     = $rw_user;    # from configuration file
$password = $rw_password;

$mysql_charset   = "latin1";          # default
$mysql_collation = "latin1_swedish_ci";

if ($charset eq "latin2") {           # from configuration file
  $mysql_charset   = "latin2";
  $mysql_collation = "latin2_general_ci";
}
if ($charset eq "utf8") {
  $mysql_charset   = "utf8";
  $mysql_collation = "utf8_unicode_ci";
}

$db_id                   = 1;  # default data collection number
$db_access               = 1;  # default access mode (0 = hidden, 1 = read-only, 2 = read/write)
$bitmapfile_digits       = 8;  # default values for bitmap filenames (e.g., 00000123.png)
$bitmapfile_subdirdigits = 4;  # default value for subdirectory names (e.g., 0000/00000123.png)
$db_name                 = "";

$deffile = "$indir/sdf2moldb_".$inbase.".def";

# $deffile = "sdf2moldb.def"; # modified jw

$found_mol_id   = 0;
$found_mol_name = 0;
open (DEF, "<$deffile") || die("ERROR: cannot open definition file $deffile!");
$nfields = 0;
while ($line = <DEF>) {
  chomp($line);
  @valid = split (/#/, $line);  # ignore everything behind the first pound sign
  $line  = $valid[0];
  $line  = ltrim($line);
  $line  = rtrim($line);
  if ((index($line,'sdfilename') == 0) && (index($line,'=') >= 10)) {
    @defrec = split (/=/, $line);
    $sdfile = $defrec[1];
    $sdfile = ltrim($sdfile);
  }
  if ((index($line,'db_id') == 0) && (index($line,'=') >= 5)) {
    @defrec = split (/=/, $line);
    $db_id = $defrec[1];
    $db_id = ltrim($db_id);
  }
  if ((index($line,'db_name') == 0) && (index($line,'=') >= 7)) {
    @defrec = split (/=/, $line);
    $db_name = $defrec[1];
    $db_name = ltrim($db_name);
    $db_name =~ s/\"//g;
  }
  if ((index($line,'db_description') == 0) && (index($line,'=') >= 14)) {
    @defrec = split (/=/, $line);
    $db_description = $defrec[1];
    $db_description = ltrim($db_description);
    $db_description =~ s/\"//g;
  }
  if ((index($line,'db_access') == 0) && (index($line,'=') >= 9)) {
    @defrec = split (/=/, $line);
    $tmp_access = $defrec[1];
    $tmp_access = ltrim($tmp_access);
    if ($tmp_access == 0) { $db_access = 0; }
    if ($tmp_access == 1) { $db_access = 1; }
    if ($tmp_access == 2) { $db_access = 2; }
    if ($tmp_access == 3) { $db_access = 3; }    
  }
  if ((index($line,'bitmapfile_digits') == 0) && (index($line,'=') >= 17)) {
    @defrec = split (/=/, $line);
    $tmp_digits = $defrec[1];
    $tmp_digits = ltrim($tmp_digits);
    if (($tmp_digits > 0) && ($tmp_digits <= 32)) { $bitmapfile_digits = $tmp_digits; }
  }
  if ((index($line,'bitmapfile_subdirdigits') == 0) && (index($line,'=') >= 23)) {
    @defrec = split (/=/, $line);
    $tmp_subdirdigits = $defrec[1];
    $tmp_subdirdigits = ltrim($tmp_subdirdigits);
    if (($tmp_subdirdigits >= 0) && ($tmp_subdirdigits <= $bitmapfile_digits)) { $bitmapfile_subdirdigits = $tmp_subdirdigits; }
  }
  
  $lpos = index($line,':');
  $rpos = rindex($line,':');
  if (($lpos >= 1) && ($rpos >= 3) && ($rpos > $lpos)) {
    # this should be a definition line
    @defrec = split (/:/, $line);
    $sdf_label   = $defrec[0];
    $mysql_label = $defrec[1];
    $mysql_type  = $defrec[2];
    $html_label  = $defrec[3];
    $html_format = $defrec[4];
    @afield[($nfields)] = [ $sdf_label, $mysql_label, $mysql_type, $html_label, $html_format, "" ];
    $nfields++;
    if ($mysql_label eq "mol_name") { $found_mol_name = 1; }
    if ($mysql_label eq "mol_id") { $found_mol_id = 1; print "$line\n"; }
  }
}

if ($verbose > 0) { 
  print "\nAdding data to MolDB5R database '$database', using configuration\n";
  print "file '$configfile'.\n\n";
  print "Your SDF input file must have the same format as the one which\n";
  print "has been used previously for analysis with the sdfcheck.pl script:\n";
  print "$sdfile\n\n";
  if ($append eq 0) {
    print "WARNING: all existing data in this collection will be erased!\n\n";
  }
  	
}

if ($found_mol_id > 0) {
  print STDERR "\nERROR: your definition file $deffile contains a field named\n";
  print STDERR "'mol_id'! This label must not be used, as it is automatically created\n";
  print STDERR "by the system. Please edit $deffile and change 'mol_id' into some\n";
  print STDERR "other name.\n";
  exit;
}

if ($found_mol_name == 0) {
  print "\nWARNING: your definition file $deffile does not contain a field\n";
  print "named 'mol_name'. It is highly recommended to rename the most descriptive\n";
  print "field into 'mol_name', please read the instructions in $deffile!\n";
  print "Otherwise, a field 'mol_name' will be automatically created, but it\n";
  print "might remain empty during the SDF import operation.\n\n";
  $askuser = 1;
}

if ($askuser > 0) {
  print "Do you really want to continue (y/n)? ";
  chomp($word = <STDIN>);
  if (lc($word) eq 'y') {
    if ($verbose > 0) { 
      print "OK. This will take some time, please be patient.\n";
    }
  } else {
    print "aborting...\n";
    exit;
  }
}

if ($verbose > 1) { 
  print "using SD file $infile\n"; 
  print "found the following data field definitions:\n";
  for ($i = 0; $i <= $#afield; $i++) {
    $l1  = $afield[$i][0];
    $l2  = $afield[$i][1];
    $l3  = $afield[$i][2];
    $l4  = $afield[$i][3];
    $l5  = $afield[$i][4];
    while (length($l1) < 25) { $l1 = $l1 . " "; }
    while (length($l2) < 25) { $l2 = $l2 . " "; }
    while (length($l3) < 25) { $l3 = $l3 . " "; }
    while (length($l4) < 5) { $l4 = $l4 . " "; }
    print "$l1   $l2  $l3 $l4 $l5\n";
  } 
}

if ($verbose > 0) { 
  print "\nthese settings are used:\n";
  print "  MySQL database:           $database\n";
  print "  hostname:                 $hostname\n";
  print "  admin user:               $user\n";
  print "  SDF file:                 $infile\n";
  print "  data collection number:   $db_id\n";
  print "  data collection name:     $db_name\n";
  print "  description:              $db_description\n";
  print "  access mode (0,1,2,3):    $db_access\n";
  print "  bitmap directory:         $bitmapdir\n";
  print "  bitmap filename digits:   $bitmapfile_digits\n";
  print "  subdirectory name digits: $bitmapfile_subdirdigits\n\n";
}

# check for checkmol
$return = `$CHECKMOL -v`;
if (index($return,"Usage:") < 0) {
  die("ERROR: could not find 'checkmol', make sure it is installed");	
}  

# check for matchmol
$return = `$MATCHMOL -v`;
if (index($return,"Usage:") < 0) {
  die("ERROR: could not find 'matchmol', make sure it is installed");	
}  

# check for mol2svg
$return = `$MOL2SVG -v`;
if (index($return,"Usage:") < 0) {
  die("ERROR: could not find 'mol2svg', make sure it is installed");	
}  

if ((!defined $user) || ($user eq "")) {
  die("ERROR: no username specified!\n");
}

if ((defined $bitmapdir) && ($bitmapdir ne "") && ($make_bitmaps == 1)) {
  if ( ! -d $bitmapdir) {
    #mkdir $bitmapdir || die("ERROR: cannot create directory $bitmapdir !");
    if (!mkdir $bitmapdir) {
    	print "cannot create bitmap directory $bitmapdir\n  ==> skipping bitmaps!\n";
    	$make_bitmaps = 0;
    } else { print "created directory: $bitmapdir\n"; }
  }
  if ( ! -W $bitmapdir) {
    print "cannot write to directory $bitmapdir\n  ==> skipping bitmaps!\n";
    $make_bitmaps = 0;
  }
  $dbbitmapdir = $bitmapdir . "/" . $db_id;
  if ( ! -d $dbbitmapdir) {
    if (!mkdir $dbbitmapdir) {
    	print "cannot create db bitmap directory $dbbitmapdir\n  ==> skipping bitmaps!\n";
    	$make_bitmaps = 0;
    } else { print "created directory: $dbbitmapdir\n"; }
  }
  if ( ! -W $dbbitmapdir) {
    print "cannot write to directory $dbbitmapdir\n  ==> skipping bitmaps!\n";
    $make_bitmaps = 0;
  }
  # check for mol2ps
  $return = `$MOL2PS -v`;
  if (index($return,"Usage:") < 0) {
    print "WARNING: could not find 'mol2ps', make sure it is installed\n";	
    #exit;
    print "  ==> skipping bitmaps!\n";
    $make_bitmaps = 0;
  }  
  # check for Ghostscript
  $return = `$GHOSTSCRIPT -v`;
  if (index($return,"Ghostscript") < 0) {
    print "WARNING: could not find 'gs', make sure it is installed\n";	
    #exit;
    print "  ==> skipping bitmaps!\n";
    $make_bitmaps = 0;
  }  
}


# open the MySQL database and check if data collection exists already

$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",
                    $user, $password,
                    { RaiseError => 1}
                    ) || die("ERROR: database connection failed: $DBI::errstr");
$qstr = "SET NAMES " . $mysql_charset;
$dbh->do($qstr);
$qstr = "SELECT db_id, name FROM $metatable WHERE db_id = $db_id";
$sth0 = $dbh->prepare($qstr);
$sth0->execute();
$n = 0;
while ($ref0 = $sth0->fetchrow_hashref()) {
  $n++;
  $id   = $ref0->{'db_id'};
  $name = $ref0->{'name'};
}

if ($n > 0) {
  if ($name eq $db_name) {
    if ($verbose > 0) {
      print "INFORMATION: adding data to an already existing collection\n";
    }
    $isnewdb = 0;
  } else {
    print STDERR "ERROR: a data collection with this number, but with a different\n";
    print STDERR "name ($name) exists already: check your settings in $deffile!\n";
    exit;
  }	
} else {
  if ($verbose > 0) {
    print "INFORMATION: this is a new data collection\n";	
  }
  $isnewdb = 1;
}

#
# not needed for molclass and chemgrid
#
#$dbprefix      = $prefix . "db" . $db_id . "_";
#$molstructable = $dbprefix . $molstrucsuffix;
#$moldatatable  = $dbprefix . $moldatasuffix;
#$molstattable  = $dbprefix . $molstatsuffix;
#$molfgbtable   = $dbprefix . $molfgbsuffix;
#$molcfptable   = $dbprefix . $molcfpsuffix;
#$pic2dtable    = $dbprefix . $pic2dsuffix;


open (SDF, "<$infile") || die("ERROR: cannot open input file $infile!");

if ($isnewdb == 1) {
  $insertcmd = "INSERT INTO $metatable VALUES ( $db_id, 1, $db_access, \"$db_name\", ";
  $insertcmd .= " \"$db_description\", \"F\", 0, $bitmapfile_digits, $bitmapfile_subdirdigits, \"\")";
  $dbh->do($insertcmd);
}

# read the fragment dictionary from the fpdef table

$createstr = "";
$n_dict = 0;
@fpstruc = [""];
$sth = $dbh->prepare("SELECT fpdef, fptype FROM $fpdeftable");
$sth->execute();
while ($ref = $sth->fetchrow_hashref()) {
  $fpdef   = $ref->{'fpdef'};
  if (length($fpdef)>20) {
    $n_dict++;
    @fpstruc[($n_dict - 1)] = $fpdef;
    $dictnum = $n_dict;
    while (length($dictnum) < 2) { $dictnum = "0" . $dictnum;  }
    $fptype             = $ref->{'fptype'};
    if ($fptype == 1) {
      $createstr .= "  dfp$dictnum BIGINT NOT NULL,\n";
    } else {
      $createstr .= "  dfp$dictnum INT(11) UNSIGNED NOT NULL,\n";
    }
  }
}                 # end while ($ref...
$sth->finish;
chomp($createstr);
if ($n_dict < 1) {
  die("ERROR: could not retrieve fingerprint definition from table $fpdeftable\n");
}


# create tables if they do not exist already

$createcmd = "CREATE TABLE IF NOT EXISTS $molstructable (
  mol_id INT(11) NOT NULL DEFAULT '0', 
  struc MEDIUMBLOB NOT NULL,
  PRIMARY KEY mol_id (mol_id));";
$dbh->do($createcmd);

#
# MolClass/ChemGRID modification to store sdftags seperately
#
$createcmd = "CREATE TABLE IF NOT EXISTS $moldatatable (
  mol_id INT($digits) ZEROFILL NOT NULL DEFAULT '0',\n" ;
$createcmd2 = "CREATE TABLE IF NOT EXISTS $strucinfotable (
  mol_id INT($digits) ZEROFILL NOT NULL DEFAULT '0',\n";
for ($i = 0; $i <= $#afield; $i++) {
  $l1  = $afield[$i][0];
  $l2  = $afield[$i][1];
  $l3  = $afield[$i][2];
	if($l2 eq 'mol_name') ##########################
  		{$createcmd = $createcmd . "  $l2 $l3,\n";}
	elsif($l2 eq 'plate_number'||$l2 eq 'plate_row'||$l2 eq 'plate_col'){}
	else
		{$createcmd2 = $createcmd2 . "  $l2 $l3,\n";}
} 
$createcmd = $createcmd . "  PRIMARY KEY mol_id (mol_id));";
$dbh->do($createcmd);
$createcmd2 = $createcmd2 . "  PRIMARY KEY mol_id (mol_id));";
#print "$createcmd2\n\n";
#$dbh->do($createcmd2);

# adds new columns to $strucinfotable if they are not present
my @headers;
	my $sth=$dbh->prepare("SHOW COLUMNS FROM $strucinfotable") or die "Unable to prepare error:". $dbh->errstr."\n";
	$sth->execute or die "Unable to execute error:". $dbh->errstr."\n";
	
while (@arr = $sth->fetchrow_array()) {
	push(@headers, $arr[0]);
		}
$sth->finish;

undef %is_header;
for(@headers) { $is_header{$_} = 1;}

@infocols;
for ($i = 0; $i <= $#afield; $i++) {
  $l1  = $afield[$i][0];
  $l2  = $afield[$i][1];
  $l3  = $afield[$i][2];
if($l2 ne 'mol_name')
{
	push(@infocols, $l2);
	unless($is_header{$l2})
	{
		my $sqlstr = "ALTER TABLE $strucinfotable ADD `".$l2."` ".$l3.";";
		$dbh->do($sqlstr);
	}
}
}
#
############################################################
#  end Molclass/chemgrid modification
############################################################
#

$createcmd = "CREATE TABLE IF NOT EXISTS $molstattable (
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
) ENGINE=MYISAM COMMENT='Molecular statistics';";

# check if we have an older version of checkmol; if yes,
# revert to fixed field definitions
if (($nfields < 50) || ($use_fixed_fields == 1)) {
  if ($verbose > 0) {
    print "reverting to fixed molstat fields\n";
  }
  $createcmd="CREATE TABLE IF NOT EXISTS $molstattable (
  mol_id int(11) NOT NULL DEFAULT '0',
  n_atoms SMALLINT(6) NOT NULL DEFAULT '0',
  n_bonds SMALLINT(6) NOT NULL DEFAULT '0',
  n_rings SMALLINT(6) NOT NULL DEFAULT '0',
  n_QA SMALLINT(6) NOT NULL DEFAULT '0',
  n_QB SMALLINT(6) NOT NULL DEFAULT '0',
  n_chg SMALLINT(6) NOT NULL DEFAULT '0',
  n_C1 SMALLINT(6) NOT NULL DEFAULT '0',
  n_C2 SMALLINT(6) NOT NULL DEFAULT '0',
  n_C SMALLINT(6) NOT NULL DEFAULT '0',
  n_CHB1p SMALLINT(6) NOT NULL DEFAULT '0',
  n_CHB2p SMALLINT(6) NOT NULL DEFAULT '0',
  n_CHB3p SMALLINT(6) NOT NULL DEFAULT '0',
  n_CHB4 SMALLINT(6) NOT NULL DEFAULT '0',
  n_O2 SMALLINT(6) NOT NULL DEFAULT '0',
  n_O3 SMALLINT(6) NOT NULL DEFAULT '0',
  n_N1 SMALLINT(6) NOT NULL DEFAULT '0',
  n_N2 SMALLINT(6) NOT NULL DEFAULT '0',
  n_N3 SMALLINT(6) NOT NULL DEFAULT '0',
  n_S SMALLINT(6) NOT NULL DEFAULT '0',
  n_SeTe SMALLINT(6) NOT NULL DEFAULT '0',
  n_F SMALLINT(6) NOT NULL DEFAULT '0',
  n_Cl SMALLINT(6) NOT NULL DEFAULT '0',
  n_Br SMALLINT(6) NOT NULL DEFAULT '0',
  n_I SMALLINT(6) NOT NULL DEFAULT '0',
  n_P SMALLINT(6) NOT NULL DEFAULT '0',
  n_B SMALLINT(6) NOT NULL DEFAULT '0',
  n_Met SMALLINT(6) NOT NULL DEFAULT '0',
  n_X SMALLINT(6) NOT NULL DEFAULT '0',
  n_b1 SMALLINT(6) NOT NULL DEFAULT '0',
  n_b2 SMALLINT(6) NOT NULL DEFAULT '0',
  n_b3 SMALLINT(6) NOT NULL DEFAULT '0',
  n_bar SMALLINT(6) NOT NULL DEFAULT '0',
  n_C1O SMALLINT(6) NOT NULL DEFAULT '0',
  n_C2O SMALLINT(6) NOT NULL DEFAULT '0',
  n_CN SMALLINT(6) NOT NULL DEFAULT '0',
  n_XY SMALLINT(6) NOT NULL DEFAULT '0',
  n_r3 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r4 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r5 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r6 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r7 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r8 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r9 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r10 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r11 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r12 SMALLINT(6) NOT NULL DEFAULT '0',
  n_r13p SMALLINT(6) NOT NULL DEFAULT '0',
  n_rN SMALLINT(6) NOT NULL DEFAULT '0',
  n_rN1 SMALLINT(6) NOT NULL DEFAULT '0',
  n_rN2 SMALLINT(6) NOT NULL DEFAULT '0',
  n_rN3p SMALLINT(6) NOT NULL DEFAULT '0',
  n_rO SMALLINT(6) NOT NULL DEFAULT '0',
  n_rO1 SMALLINT(6) NOT NULL DEFAULT '0',
  n_rO2p SMALLINT(6) NOT NULL DEFAULT '0',
  n_rS SMALLINT(6) NOT NULL DEFAULT '0',
  n_rX SMALLINT(6) NOT NULL DEFAULT '0',
  n_rar SMALLINT(6) NOT NULL DEFAULT '0',
  PRIMARY KEY  (mol_id)
  ) ENGINE=MYISAM COMMENT='Molecular statistics';";
}
$dbh->do($createcmd);


# create a new molfgb table
$createcmd="CREATE TABLE IF NOT EXISTS $molfgbtable (mol_id INT(11) NOT NULL DEFAULT '0', 
fg01 INT(11) UNSIGNED NOT NULL,
fg02 INT(11) UNSIGNED NOT NULL,
fg03 INT(11) UNSIGNED NOT NULL,
fg04 INT(11) UNSIGNED NOT NULL,
fg05 INT(11) UNSIGNED NOT NULL,
fg06 INT(11) UNSIGNED NOT NULL,
fg07 INT(11) UNSIGNED NOT NULL,
fg08 INT(11) UNSIGNED NOT NULL,
n_1bits SMALLINT NOT NULL,
PRIMARY KEY mol_id (mol_id)) ENGINE=MYISAM COMMENT='Functional group patterns'";
$dbh->do($createcmd);

  
# create a new molcfp table if not yet present
$createcmd="CREATE TABLE IF NOT EXISTS $molcfptable (
  mol_id INT(11) NOT NULL DEFAULT '0',
$createstr
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
  n_h1bits SMALLINT NOT NULL, PRIMARY KEY (mol_id) 
  ) ENGINE=MYISAM COMMENT='Combined dictionary-based and hash-based fingerprints'";
$dbh->do($createcmd);

# create a new pic2d table
$createcmd="CREATE TABLE IF NOT EXISTS $pic2dtable (
mol_id INT(11) NOT NULL DEFAULT '0',
type TINYINT NOT NULL DEFAULT '1' COMMENT '1 = png',
status TINYINT NOT NULL DEFAULT '0' COMMENT '0 = does not exist, 1 = OK, 2 = OK, but do not show, 3 = to be created/updated, 4 = to be deleted',
svg BLOB NOT NULL,
PRIMARY KEY (mol_id)
) ENGINE=MYISAM CHARACTER SET $mysql_charset COLLATE $mysql_collation COMMENT='Housekeeping for 2D depiction'";
$dbh->do($createcmd);


# Now the tables have been created, if necessary

# Next, disable use of memory-based tables
$updstr = "UPDATE $metatable SET memstatus = 0 WHERE db_id = $db_id";
$dbh->do($updstr);	

# Remove any existing data if $append is set to 0

if ($append eq 0) {
  if ($verbose > 0) {
    print "erasing existing data... \n";
  }
  $delcmd = "TRUNCATE TABLE $molstructable; ";
  $dbh->do($delcmd);
  $delcmd = "TRUNCATE TABLE $moldatatable; ";
  $dbh->do($delcmd);
  $delcmd = "TRUNCATE TABLE $molstattable; ";
  $dbh->do($delcmd);
  $delcmd = "TRUNCATE TABLE $molfgbtable; ";
  $dbh->do($delcmd);
  $delcmd = "TRUNCATE TABLE $molcfptable; ";
  $dbh->do($delcmd);
  $delcmd = "TRUNCATE TABLE $pic2dtable; ";
  $dbh->do($delcmd);
}

# Then, get the next available mol_id number

$entry = 0;
$sth0  = $dbh->prepare("SELECT mol_id FROM $moldatatable ORDER BY mol_id DESC LIMIT 0,1 ");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $entry = $ref0->{'mol_id'};
}

if ($verbose > 1) { print "number of molecules already in the database: $entry \n"; }

$counter  = 0;
$li       = 0;
$buf      = '';
$mol      = '';
$txt      = '';
$lbl      = '';
$ct       = 1;
$badmols  = 0;
$internal_name = "";

#
# MolClass/ChemGRID upload batchlist of compound library into database
#

#insert batch into batchlisttable
foreach (@infocols) {
	$infocolstring = $infocolstring.$_." ";
} 

$sdffilename = $inbase;
$sdffilename =~ s/$id//i; 
$dbh->do("INSERT INTO $batchlisttable (username, filename, tags, mol_type, pmid, info, uploaded) VALUES ('$username', '$sdffilename', '$infocolstring', '$mol_type', '$pmid', '$info', '0' )");
	 $batchnum = $dbh->last_insert_id(undef, undef, qw(a_table a_table_id));

$inchikey = '';
$inchicheck = 0;



# process the input file line by line

while ($line = <SDF>) {
  $line =~ s/\r//g;      # remove carriage return characters (DOS/Win)
  if ((substr($line,0,4) eq '$$$$') || eof(SDF)) {
    $counter ++;
    $entry ++;
    if ($verbose > 1) { 
      print "adding entry $entry\n"; 
    } else {
      if ((($counter % 100) == 0) && ($verbose > 0)) { print "$counter records processed\n"; }
    }
    if ($ct == 1) {
      $mol = $buf;
      $txt = "";
    } else {
      $txt = $buf;
    }
    $internal_name = "";
    if (valid_mol($mol) == 1) {
      $mol =~ s/\"/\\\"/g;        # escape any quote characters
      if (index($mol,"M  END") < 0) {
        $mol = $mol . "M  END\n"; # some SD files are lacking the "M  END" 
      }	
      insert_mol($mol);
      insert_data($txt);
      insert_molstat_molfgb_molcfp($mol);
      # add molecules to Batchmoltable to track molecules and with batchID's
      # molclass/chemgrid addon
      insert_cdk_fp_batch();
      #
      #
      if (($make_bitmaps == 1) && ($bitmapdir ne "")) {
        $fname = $entry;
        while (length($fname) < $bitmapfile_digits) { $fname = "0" . $fname; }
        $subdir = '';
        if ($bitmapfile_subdirdigits > 0) {
          $subdir = substr($fname,0,$bitmapfile_subdirdigits);
          $newdir = $dbbitmapdir . "/" . $subdir;
          if ( ! -d $newdir) {
            if ( !mkdir($newdir)) {
              print "cannot create directory $newdir !";
              $make_bitmaps = 0;
            }
            if ($verbose > 1) { print "created subdirectory: $newdir\n"; }
          }
          if ( ! -W $newdir) {
            print "cannot write to directory $newdir !";
            $make_bitmaps = 0;
          }
          $subdir = $subdir . "/";        
        }
        $fname = $dbbitmapdir . "/" . $subdir . $fname . ".png";
        create_bitmap($mol,$fname,$mol2psopt,$scalingfactor);
        # if successful, insert status "1" in pic2dtable, else status "3"
        if (( -R $fname) && ( -s $fname > 100)) {
          insert_pic2dstatus(1);
        } else {
          insert_pic2dstatus(3);
        }
      } else {
      	insert_pic2dstatus(3);
      }
      insert_svg($mol,$mol2svgopt,$svg_scalingfactor);
    } else {
      $counter --;
      $entry --;
      $badmols ++;
    }
    $buf = "";
    $txt = "";
    $mol = "";
    $ct  = 1;
  } else {
    if (substr($line,0,1) eq '>') { 
      if ($ct == 1) {
        $mol = $buf;
        $buf = $line;
      }
      $ct = 0; 
    }
    $buf = $buf . $line;
  }
}        # end while ($line....

#$dbh->disconnect();

if ($verbose > 0) {
  print "==============================================================================\n";
  print "$counter records processed in total\n";
  print "$badmols records ignored\n\n";
}

#
# Calculate descriptos for a uploaded set of molecules can be used by MolClass and ChemGRID
#
# you need to have ~/wekafiles/props/DatabaseUtils.props
# find it in molclass/prerequisites

#Calculate fingerprint & descriptor
#print "Calculating finger printers...\n\n\n";
$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:MolClass.jar fingerprints.Fingerprinter $batchnum";
system($cmd. " 1>> ./log/output_fingerprinter.log"." 2>> ./log/error_fingerprinter.log"); 


print "Calculating descriptors...\n\n\n";

$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:MolClass.jar  descriptors.AutomaticCalcDriver $batchnum";
system($cmd. " 1>> ./log/output_descriptors.log"." 2>> ./log/error_descriptors.log"); 

# dirty fix:
# run CDK descriptor calculation; weird failure issues workaround to get all calculations (only recalculates missed)
system $cmd;
system $cmd;
system $cmd;
system $cmd;
system $cmd;
system $cmd; # this should be enough for a large molecule library

# Calculate Smiles and InChi's
#$cmd = "java -jar MolClass.jar InChiGenerator $batchnum";
#$cmd = "java -cp lib/cdk-1.4.18.jar:./MolClass.jar fingerprints.InChiGenerator $batchnum";

# get InChi, InChiKeys and Smiles
$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar fingerprints.InChiGenerator  $batchnum";
system($cmd. " 1>> ./log/output_InChiGenerator.log"." 2>> ./log/error_InChiGenerator.log"); 

# generate Murcko Fragments
$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar fingerprints.MurckoFragments $batchnum";
system($cmd. " 1>> ./log/output_Murcko.log"." 2>> ./log/error_Murcko.log"); 

# generate Tanimoto scores for > 0.85
$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar fingerprints.Similarity  $batchnum";
system($cmd. " 1>> ./log/output_Similarity.log"." 2>> ./log/error_Similarity.log"); 


#EMAIL NOTIFICATION (added by Nicholas FitzGerald for chemgrid)
print $email;


$url = $web_server_location."/view_batch_detail.php?batch_id=".$batchnum;
sendEmail($email, "MolClass", "SDF Upload Complete", "Upload of file ".$sdffilename." is complete. You can check uploaded file at $url");
print "Sending email for notifying finish of SDF Upload...\n\n\n";
#Check uploaded
$dbh->do("UPDATE $batchlisttable SET uploaded = '1' WHERE batch_id = $batchnum");

#Make a prediction for uploaded molecules against models build
pred_test();
$url = $web_server_location."/view_batch_detail.php?batch_id=".$batchnum;
sendEmail($email, "MolClass", "Prediction Complete", "Prediction against all existing model in a database is complete. You can check prediction result at $url");
print "Sending email for notifying finish of Prediction...\n\n\n";

#Check Prediction has been made
$dbh->do("UPDATE $batchlisttable SET uploaded = '1' WHERE batch_id = $batchnum");
#Disconnect mysql DB
$dbh->disconnect();






#===================== subroutines =======================================

sub pred_test()
{
  $batch_id = $batchnum;
  $query2 = "SELECT model_id FROM $modeltable";
  $sth2 = $dbh->prepare("$query2");
  $sth2->execute;
  while(@row = $sth2->fetchrow_array())
  {
      $model_id = $row[0];
      $query = "INSERT INTO $predtable (username, batch_id, model_id, pred_name, email) VALUES ('$username', '$batch_id', '$model_id', '$pred_name', '$email_dummy')";
      $sth = $dbh->prepare($query);
      $sth->execute;
      $pred_id = $dbh->last_insert_id(undef, undef, qw(a_table a_table_id));
      #$cmd = "java -cp ".$model_pred_dir."weka.jar:".$model_pred_dir."lib:/usr/share/java/mysql-connector-java.jar:".$model_pred_dir."weka:. nick/test/Predictor ".$pred_id;
      #$cmd = "java -jar MolClass.jar Predictor ".$pred_id;
      #$cmd = "java -cp lib/cdk-git-20110515.jar:lib/weka2.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id";
      #$cmd = "java MolClass.jar Predictor $pred_id";
      #
      $cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/weka2.jar:lib/libsvm.jar:lib/hiddenNaiveBayes.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id";

      #print "\n$cmd\n";
      #system $cmd;
      system($cmd. " 1>> ./log/output_predictors_sdf2moldb.log"." 2>> ./log/error_predictors_sdf2moldb.log"); 

      $sth->finish();
  }
  $sth2->finish();
}

sub sendEmail
{
my ($to, $from, $subject, $message) = @_;
my $sendmail = '/usr/lib/sendmail';
open(MAIL, "|$sendmail -oi -t");
print MAIL "From: $from\n";
print MAIL "To: $to\n";
print MAIL "Subject: $subject\n\n";
print MAIL "$message\n";
close(MAIL);
} 


sub insert_cdk_fp_batch()
{
	 $dbh->do("INSERT INTO $cdkdesctable ( mol_id ) VALUES ( $entry )"); # add molid to cdkstructable
	 $dbh->do("INSERT INTO $fingerprinttable ( mol_id ) VALUES ( $entry )"); # add molid to cdkstructable
         $dbh->do("INSERT INTO $inchikeytable (mol_id, inchi_key, mol_type) VALUES ( $entry, '$inchikey', '$mol_type' )"); # inchi, smiles
	 $dbh->do("INSERT INTO $batchmoltable (batch_id, mol_id) VALUES ( $batchnum, $entry )");
}


sub valid_mol() {
  $testmol = shift;
  $zerolines = 0;
  @xyzline = split(/\n/, $testmol);
  $internal_name = $xyzline[0];
  chomp($internal_name);
  for ($i = 0; $i <= $#xyzline; $i++) {
    $testline = $xyzline[$i];
    $testline =~ s/\ +/:/g;
    @xyz = split(/:/, $testline);
    $xval = $xyz[1];
    $yval = $xyz[2];
    $zval = $xyz[3];
    if ((index($xval,"0.0000") >= 0) && (index($yval,"0.0000") >= 0) && 
       (index($zval,"0.0000") >= 0)) { $zerolines ++; }
  }
  if ($chkzero == 0) { $zerolines = 0; }  # disable check if configured
  if ($zerolines > 1) {
    return 0;
  } else {
    return 1;
  }
}

sub insert_mol() {
  $molecule = shift;
  if ($tweakmolfiles eq "y") {
    if ($ostype eq 2) {
	  $molecule = filterthroughcmd2($molecule,"$CHECKMOL -m -");
	} else {
	  $molecule = filterthroughcmd($molecule,"$CHECKMOL -m -");
	}
  }
  $dbh->do("INSERT INTO $molstructable VALUES ( $entry, \"$molecule\" ) ");
}

sub insert_data() {
  $data = shift;
  $databuf  = "";
  @rec = split (/\n/, $data);
  for ($i = 0; $i <= $#rec+1; $i++) {
    $element  = $rec[$i];
    #$element =~ s/\n//g;
    chomp($element);
    $element =~ s/\ +$//g;
    $lblchars = 0;
    if (substr($element,0,1) eq '>') {
      @lblrec  = split(/\</, $element);
      $lblname = $lblrec[1];
      @lblrec  = split(/\>/, $lblname);
      $lblname = $lblrec[0];
      #print "label: $lblname\n";
    } else {
      if (length($element) > 0) {
        $databuf = $databuf . $element . "\n";  # add new-line
        #print "adding element\n";
      } else {
        for ($j = 0; $j <= $#afield; $j++) {
          $knownlbl = $afield[$j][0];
          if ($lblname eq $knownlbl) { 
            chomp($databuf);   # remove last new-line
            $afield[$j][3] = $databuf;
          }
        }
        $databuf = "";
      }
    }
  }
  $insertcmd = "INSERT INTO $moldatatable VALUES ( $entry";
  $struc_cols = "mol_id";
  $struc_vals = "$entry";
  $plate_cols = "mol_id";
  $plate_vals = "$entry";
  for ($j = 0; $j <= $#afield; $j++) {
    $item = $afield[$j][3];
    $item =~ s/\"/\\\"/g;
    $item =~ s/\'/\\\'/g;
	 if($afield[$j][1] eq 'mol_name'){ #insert into stat if mol_name
		$insertcmd = $insertcmd . ",\n  \"$item\"";
	}
	 elsif($afield[$j][1] eq 'plate_num'|| $afield[$j][1] eq 'plate_row'|| $afield[$j][1] eq 'plate_col') #insert into plateinf if
	 {
		$label = $afield[$j][1];
		$label =~ s/\"/\\\"/g;
 	   $label =~ s/\'/\\\'/g;
		$plate_cols = $plate_cols . ",\n  $label";
		$plate_vals = $plate_vals . ",\n  \"$item\"";
	 }
	 else{ #insert into structure_info if other
		$label = $afield[$j][1];
		$label =~ s/\"/\\\"/g;
 	   $label =~ s/\'/\\\'/g;
		$struc_cols = $struc_cols . ",\n  \`$label\`";
		$struc_vals = $struc_vals . ",\n  \"$item\"";
	}
  }
  $insertcmd = $insertcmd . " )";
 # $insertcmd2 = $insertcmd2 . " )";
  $insertcmd2 =~ s///g;
  $insertcmd2 = "INSERT INTO $strucinfotable ($struc_cols) VALUES ($struc_vals)";
  $insertcmd3 = "INSERT INTO $plateinfotable ($plate_cols) VALUES ($plate_vals)";
  for ($j = 0; $j <= $#afield; $j++) {
    $afield[($j)][3] = "";
  }
  print "$insertcmd2\n";

  $dbh->quote($insertcmd);
  $dbh->quote($insertcmd2);
  $dbh->quote($insertcmd3);

  $dbh->do($insertcmd);
  $dbh->do($insertcmd2);
  $dbh->do($insertcmd3);
}

sub insert_data_orgmoldb() {
  $data = shift;
  $databuf  = "";
  @rec = split (/\n/, $data);
  for ($i = 0; $i <= $#rec+1; $i++) {
    $element  = $rec[$i];
    #$element =~ s/\n//g;
    chomp($element);
    $element =~ s/\ +$//g;
    $lblchars = 0;
    if (substr($element,0,1) eq '>') {
      @lblrec  = split(/\</, $element);
      $lblname = $lblrec[1];
      @lblrec  = split(/\>/, $lblname);
      $lblname = $lblrec[0];
    } else {
      if (length($element) > 0) {
        $databuf = $databuf . $element . "\n";  # add new-line
      } else {
        for ($j = 0; $j <= $#afield; $j++) {
          $knownlbl = $afield[$j][0];
          if ($lblname eq $knownlbl) { 
            chomp($databuf);   # remove last new-line
            $afield[$j][3] = $databuf;
          }
        }
        $databuf = "";
      }
    }
  }
  $insertcmd = "INSERT INTO $moldatatable VALUES ( $entry";
  if ($found_mol_name == 0) {
    $insertcmd = $insertcmd . ", \"$internal_name\"";
  }  
  for ($j = 0; $j <= $#afield; $j++) {
    $item = $afield[$j][3];
    $item =~ s/\"/\\\"/g;
    $insertcmd = $insertcmd . ",\n  \"$item\"";
  }
  $insertcmd = $insertcmd . " )";
  for ($j = 0; $j <= $#afield; $j++) {
    $afield[($j)][3] = "";
  }
  $dbh->do($insertcmd);
}

sub insert_molstat_molfgb_molcfp() {
  $molecule = shift;
  if ($ostype eq 2) {
    $molstatfgbhfp  = filterthroughcmd2($molecule,"$CHECKMOL -aXbH -");   # must be version 0.4 or higher
  } else {
    $molstatfgbhfp  = filterthroughcmd($molecule,"$CHECKMOL -aXbH -");   # must be version 0.4 or higher
  }
  @molstatfgbhfparray = split (/\n/, $molstatfgbhfp);
  $molstat  = $molstatfgbhfparray[0];
  $molfgb   = $molstatfgbhfparray[1];
  $molhfp   = $molstatfgbhfparray[2];
  if ((index($molstat,"unknown") < 0) && (index($molstat,"invalid") < 0)) {
    chomp($molstat);
    $dbh->do("INSERT INTO $molstattable VALUES ( $entry, $molstat )");
    chomp($molfgb);
    $molfgb =~ s/\;/\,/g;
    $dbh->do("INSERT INTO $molfgbtable VALUES ($entry, $molfgb )");
    $moldfp = "";
    for ($k = 0; $k < $n_dict; $k++) {
      $dict = @fpstruc[$k];
      $cand = $molecule . "\n" . '$$$$' ."\n" . $dict;
      $cand =~ s/\$/\\\$/g;
      if ($ostype eq 2) {
  	    $dfpstr = filterthroughcmd2($cand,"$MATCHMOL -F -");
	  } else {
	    $dfpstr = filterthroughcmd($cand,"$MATCHMOL -F -");
	  }
      chomp($dfpstr);
      if ($k > 0) { $moldfp .= ","; }
      $moldfp .= " " . $dfpstr;
    }
    chomp($molhfp);
    $molhfp =~ s/\;/\,/g;
    $dbh->do("INSERT INTO $molcfptable VALUES ($entry, $moldfp, $molhfp)");
  }
}

sub tweak_svg {
  my $testsvg = shift;
  my @svgline = split(/\n/, $testsvg);
  my $xmaxval = "";
  my $yminval = "";
  my $ytrval = "";
  my $scaling = $svg_scalingfactor;
  for ($i = 0; $i <= $#svgline; $i++) {
    my $testline = $svgline[$i];
    chomp($testline);
    if ((index($testline,"\<!-- max_X:") >= 0) && (index($testline,"--\>") >= 0)) {
      my @xline = split(/:/, $testline);
      $xmaxval = $xline[1];
      $xmaxval =~ s/--\>//g;
      chomp($xmaxval);
      $xmaxval =~ s/\ +//g;
      if ($keep_xydata == 0) { $svgline[$i] = ""; }
    }
    if ((index($testline,"\<!-- max_Y:") >= 0) && (index($testline,"--\>") >= 0)) {
      my @yline = split(/:/, $testline);
      $ymaxval = $yline[1];
      $ymaxval =~ s/--\>//g;
      chomp($ymaxval);
      if ($keep_xydata == 0) { $svgline[$i] = ""; }
    }
    if ((index($testline,"\<!-- min_Y:") >= 0) && (index($testline,"--\>") >= 0)) {
      my @yline = split(/:/, $testline);
      $yminval = $yline[1];
      $yminval =~ s/--\>//g;
      chomp($yminval);
      if ($keep_xydata == 0) { $svgline[$i] = ""; }
    }
    if ((index($testline,"\<!-- yshift:") >= 0) && (index($testline,"--\>") >= 0)) {
      my @ytrline = split(/:/, $testline);
      $ytrval = $ytrline[1];
      $ytrval =~ s/--\>//g;
      chomp($ytrval);
      if ($keep_xydata == 0) { $svgline[$i] = ""; }
    }
    if ((index($testline,"\<!-- found XY values for adjusting") >= 0) && (index($testline,"--\>") >= 0)) {
      if ($keep_xydata == 0) { $svgline[$i] = ""; }
    }
  }  # for
  if ((length($xmaxval) > 0) && (length($ymaxval) > 0) && (length($yminval) > 0) && (length($ytrval) > 0)) {
    my $ymaxtotal   = $ymaxval + $ytrval;
    my $ymintotal   = $yminval + $ytrval;
    my $ydiff       = $ymaxtotal - $ymintotal;
    my $ydiffscaled = $ydiff * $scaling;
    my $xmaxscaled  = $xmaxval * $scaling;
    my $ymaxscaled  = $ymaxtotal * $scaling;
    my $yminscaled  = $ymintotal * $scaling;
    $svgline[1] = "<svg width=\"$xmaxscaled\" height=\"$ydiffscaled\" viewbox=\"0 $ymintotal $xmaxval $ydiff\" xmlns=\"http://www.w3.org/2000/svg\">";
  }
  my $twsvg = join("\n",@svgline);
  $twsvg =~ s/\n\n\n//g;
  return $twsvg;
}


sub insert_svg() {
  $structure = shift;
  $mopt = shift;
  $sf = shift;
  $structure =~ s/\"/\\\"/g;
  $structure =~ s/\$/\\\$/g;
  if (index($structure,"M  END") < 0) { $structure = $structure . "M  END\n"; }	
  if ($ostype eq 2) {
    $molsvg = filterthroughcmd2($structure,"$MOL2SVG $mopt - ");
  } else {
    $molsvg = filterthroughcmd($structure,"$MOL2SVG $mopt - ");
  }
  #print "$molsvg\n";
  #$newsvg = tweak_svg($molsvg);   # needed only for mol2svg v0.3x
  $newsvg = $molsvg;   # for mol2svg v0.4 or higher
  $newsvg =~ s/\"/\\\"/g;
  #print "$newsvg\n";
  $insertcmd = "UPDATE $pic2dtable SET svg = \"${newsvg}\" WHERE mol_id = $entry";
  $dbh->do($insertcmd);
}


sub insert_pic2dstatus() {
  $status = shift;
  $dbh->do("INSERT INTO $pic2dtable VALUES ( $entry, 1, $status, '' ) ");
}


sub create_bitmap() {
  $molecule = shift;
  $filename = shift;
  $mopt = shift;
  $sf = shift;
  $gsdevice = "pnggray";
  if (index($mopt,"--color=") >= 0) { $gsdevice = "png256"; }
  if ($ostype eq 2) {
    $molps = filterthroughcmd2($molecule,"$MOL2PS $mopt - ");
    $bb =  filterthroughcmd2($molps,"$GHOSTSCRIPT -q -sDEVICE=bbox -dNOPAUSE -dBATCH  -r300 -g500000x500000 - ");
  } else {
    $molps = filterthroughcmd($molecule,"$MOL2PS $mopt - ");
    $bb =  filterthroughcmd($molps,"$GHOSTSCRIPT -q -sDEVICE=bbox -dNOPAUSE -dBATCH  -r300 -g500000x500000 - ");
  }
  @bbrec =   split(/\n/, $bb);
  $bblores = $bbrec[0];
  $bblores =~ s/%%BoundingBox://g;
  chomp($bblores);
  $bblores = ltrim($bblores);
  @bbcorner = split(/\ /, $bblores);
  $bbleft = $bbcorner[0];
  $bbbottom = $bbcorner[1];
  $bbright = $bbcorner[2];
  $bbtop = $bbcorner[3];
  $xtotal = ($bbright + $bbleft) * $sf;
  $ytotal = ($bbtop + $bbbottom) * $sf;
  if (($xtotal > 0) && ($ytotal > 0)) {
    $molps = $sf . " " . $sf . " scale\n" . $molps;  ## insert the PS "scale" command
    #print "low res: $bblores  .... max X: $bbright, max Y: $bbtop \n";
    #print "$filename  $xtotal x $ytotal pt\n";
  } else {
    $xtotal = 99;
    $ytotal = 55;
    $molps = "%!PS-Adobe
    /Helvetica findfont 14 scalefont setfont
    10 30 moveto
    (2D structure) show
    10 15 moveto
    (not available) show
    showpage\n";
  }	
  # fix a little color problem with mol2ps v0.4
  $molps =~ s/gsave/gsave\ 0\ 0\ 0\ setrgbcolor/g;
  $gsopt1 = " -r300 -dGraphicsAlphaBits=4 -dTextAlphaBits=4 -dDEVICEWIDTHPOINTS=";
  $gsopt1 = $gsopt1 . $xtotal . " -dDEVICEHEIGHTPOINTS=" . $ytotal;
  $gsopt1 = $gsopt1 . " -sOutputFile=" . $filename;
  $gscmd = $GHOSTSCRIPT . " -q -sDEVICE=$gsdevice -dNOPAUSE -dBATCH " . $gsopt1 . " - ";
  if ($ostype eq 2) {
    $dummy = filterthroughcmd2($molps, $gscmd);
  } else {
    system("echo \"$molps\" \| $gscmd");
  }
}

sub filterthroughcmd {
  $input   = shift;
  $cmd     = shift;
  open(FHSUB, "echo \"$input\"|$cmd 2>&1 |");   # stderr must be redirected to stdout
  $res      = "";                               # because the Ghostscript "bbox" device
  while($line = <FHSUB>) {                      # writes to stderr
    $res = $res . $line;
  }
  return $res;
}

sub filterthroughcmd2 {                         # workaround for Windows 
  $input   = shift;
  $cmd     = shift;
  ($tmpfh, $tmpfilename) = tempfile(UNLINK => 1);
  $input =~ s/\\\$/\$/g;
  $input =~ s/\r//g;
  $input =~ s/\n/\r\n/g;
  print $tmpfh "$input\n";
  #open(FHSUB, "type $tmpfilename |$cmd 2>&1 |");   # stderr must be redirected to stdout
  open(FHSUB, "$cmd < $tmpfilename 2>&1 |");   # stderr must be redirected to stdout
  $res      = "";                               # because the Ghostscript "bbox" device
  while($line = <FHSUB>) {                      # writes to stderr
    $res = $res . $line;
  }
  close $tmpfh;
  return $res;
}

sub ltrim() {
  $subline1 = shift;
  $subline1 =~ s/^\ +//g;
  return $subline1;
}

sub rtrim() {
  $subline2 = shift;
  $subline2 =~ s/\ +$//g;
  return $subline2;
}

sub getostype() {
  $os  = "";
  $osresult = 1;
  $os  = uc($ENV{OS});
  if ($os eq "") { $os = uc($ENV{OSTYPE}); }
  if (index($os,"WINDOWS")>=0) {
    $osresult = 2;
  }
  return $osresult;
}
