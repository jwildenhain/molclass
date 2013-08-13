#!/usr/bin/perl
#
# updatesvg.pl      Norbert Haider, University of Vienna, 2012-2013
#                   norbert.haider@univie.ac.at
#
# This script is part of the MolDB5R package. Last change: 2013-06-12
#
# Example script which (re-)generates the SVG data for 2D depiction
# of the molecules in the MolDB5R database.

# Requirements: mol2ps/mol2svg v0.3 (or higher) must be installed.

use DBI();

$check      = 1;   # 0 = no checks at all
                   # 1 = check for missing pic2d tables and records and for missing svg columns

$verbose    = 1;   # 0 = silent operation, 
                   # 1 = report each data collection + principal action
                   # 2 = report each molecule

$keep_xydata  = 0; # 0 = delete comment lines containing max_X, max_Y, min_Y, ytrans from original SVG file
                   # 1 = leave these lines unchanged


# define some defaults, may be overridden by conf file

$MOL2SVG        = "/usr/local/bin/mol2svg";
$mol2svgopt     = "--rotate=auto3Donly --hydrogenonmethyl=off --color=/usr/local/etc/color.conf"; # options for mol2svg, e.g. "--showmolname=on"
#$mol2svgopt     = "--rotate=auto3Donly --hydrogenonmethyl=off"; # options for mol2svg, e.g. "--showmolname=on"
$mol2svgopt_rxn = "-R --rotate=auto3Donly --hydrogenonmethyl=off"; # options for mol2svg in reaction mode
$svg_scalingfactor = 1.0;           # 1.0  gives good results
$svg_scalingfactor_rxn = 0.75;      # 0.75 is a good compromise for reactions
$enablerxnbitmaps = "y";            # for backward compatibility

$configfile = "./tools/sdftools/xmlreader.pl";
#$configfile = "/data/moldb/moldb5r/moldb5.conf";  # better use absolute path when
                                                   # running this script via cron

if ($#ARGV >= 0) {
  for ($a = 0; $a <= $#ARGV; $a++) {
    $aline = $ARGV[$a];
    if (($aline eq '-h') || ($aline eq '-?') || ($aline eq '--help')) {
      print "Usag: updatesvg.pl [--check=<n>]    (with <n> as 0 or 1)\n";
      exit;
    }
    if (index($aline,"--check=") >= 0) {
      $checkval = getval($aline);
      if ($checkval eq "0") { $check = 0; }
      if ($checkval eq "1") { $check = 1; }
    }
  }
}


$return     = do $configfile;
if (!defined $return) {
  die("ERROR: cannot read configuration file $configfile!\n");
}	

# assuming mol2svg version 0.4 or higher: scaling is added to the options, 
# if not already there
if (index($mol2svgopt, '--scaling=') < 0) {
  $mol2svgopt .= " --scaling=" . $svg_scalingfactor; 
}
if (index($mol2svgopt_rxn, '--scaling=') < 0) {
  $mol2svgopt_rxn .= " --scaling=" . $svg_scalingfactor_rxn; 
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


$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",
                    $user, $password,
                    {'RaiseError' => 1});


# read moldb_meta table and find out which data collections are to be processed
$ndb = 0;
@db = [""];
$sth0 = $dbh->prepare("SELECT db_id, type FROM $metatable ORDER BY db_id");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $db_id   = $ref0->{'db_id'};
  $db_type = $ref0->{'type'};
  $ndb++;
  @db[($ndb-1)] = [ $db_id, $db_type ];
}
$sth0->finish;

$badmols = 0;
$counter = 0;


for ($idb = 0; $idb < $ndb; $idb++) {
  $dbnum  = $db[$idb][0];
  $dbtype = $db[$idb][1];
  if ($dbtype == 1) {
    $idname = "mol_id";
    $structable = $molstructable;
    $options = $mol2svgopt;
    $scale = $svg_scalingfactor;
  }
  if ($dbtype == 2) {
    $idname = "rxn_id";
    $structable = $dbprefix . $rxnstrucsuffix;
    if ( (!defined $mol2svgopt_rxn) || ($mol2svgopt_rxn eq "")) {
      $options = "-R " . $mol2svgopt . " --scaling=" . $svg_scalingfactor_rxn;  
    } else {
      $options = $mol2svgopt_rxn;
    }
    if ( (!defined $svg_scalingfactor_rxn) || ($svg_scalingfactor_rxn eq "")) {
      $scale = $svg_scalingfactor;
    } else {
      $scale = $svg_scalingfactor_rxn;
    }
  }
  
  if ($check > 0) {
    pic2d_check();
  }
  
  # read all pending molecules from mol/rxn-structable and pipe the MDL molfiles
  # through mol2ps and Ghostscript to produce the PNG graphics files
  #
  
  $sth0 = $dbh->prepare("SELECT COUNT($idname) AS itemcount FROM $pic2dtable ");
  $sth0->execute();
  while ($ref0 = $sth0->fetchrow_hashref()) {
    $itemcount = $ref0->{'itemcount'};
  }
  $sth0->finish();
  if ($verbose > 0) {
    print "number of entries in data collection $dbnum to be processed: $itemcount \n";
    print "  database type: $dbtype, scaling factor: $scale\n";
  }
  $nchunks = int( (($itemcount + 99) / 100) );
  $li  = 0;
  $buf = "";
  $mol = "";
  $txt = "";
  $lbl = "";
  $ct  = 1;
  if ($itemcount > 0) {
    
    for ($ii = 0; $ii < $nchunks; $ii++) {
      $offset = $ii * 100;
      #$offset = 0;    # no offset as the searched item (status) is permanently updated
      $qstr = "SELECT ${structable}.$idname, ${structable}.struc FROM ";
   #   $qstr .= "$structable, $pic2dtable WHERE (status = 3) AND ";
      $qstr .= "$structable  LIMIT $offset, 100";
    #  $qstr .= "(${structable}.$idname = ${pic2dtable}.$idname) LIMIT $offset, 100";
      $sth = $dbh->prepare($qstr);
      $sth->execute();
      while ($ref = $sth->fetchrow_hashref()) {
        $item_id   = $ref->{"$idname"};
        $struc     = $ref->{'struc'};
        $counter ++;
        $isvalid = 0;
        if ( $dbtype == 1) { $isvalid = valid_mol($struc); }
        if ( $dbtype == 2) { $isvalid = valid_rxn($struc); }
        if ($isvalid eq 1) {
          if ($verbose > 1) { print "    processing $idname = $item_id from data collection $dbnum (type = $dbtype)\n";  }
          process_struc($struc,$options,$scale);
        }
      }                 # end while ($ref...
      $sth->finish;
    }                   # end "for" loop
  }   # if ...    


} # for ($idb ....


$dbh->disconnect();

if ($verbose > 0) {
  print "$counter records processed in total\n";
  print "$badmols records ignored\n";
}

#============================================================

sub valid_mol() {
  $testmol = shift;
  $zerolines = 0;
  @xyzline = split(/\n/, $testmol);
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
  if ($zerolines > 1) {
    return 0;
  } else {
    return 1;
  }
}

sub valid_rxn() {
  my $testrxn = shift;
  my $result = 0;
  if ((index($testrxn,'$RXN') == 0) && (index($testrxn,'$MOL') > 0) && (index($testrxn,'M  END') > 0)) {
    $result = 1;
  }
  return($result);
}

sub tweak_svg {
  my $testsvg = shift;
  my @svgline = split(/\n/, $testsvg);
  my $xmaxval = "";
  my $yminval = "";
  my $ytrval = "";
  my $scaling = $scale;
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


sub process_struc() {
  $structure = shift;
  $mopt = shift;
  $sf = shift;
  $structure =~ s/\"/\\\"/g;
  $structure =~ s/\$/\\\$/g;
  if (index($structure,"M  END") < 0) { $structure = $structure . "M  END\n"; }	

  $molsvg = filterthroughcmd($structure,"$MOL2SVG $mopt - ");
  
  #print "$molsvg\n";
  #$newsvg = tweak_svg($molsvg);  # was necessary for mol2svg v0.3x
  $newsvg = $molsvg;  # for mol2svg v0.4 or higher
  $newsvg =~ s/\"/\\\"/g;
  #print "$newsvg\n";
  $insertcmd = "UPDATE $pic2dtable SET svg = \"${newsvg}\" WHERE $idname = $item_id";
  $dbh->do($insertcmd);
}

sub pic2d_check() {
  if ($verbose > 0) { 
    print " checking for $pic2dtable in data collection $dbnum (type = $dbtype)\n";
  }
  $createcmd = "CREATE TABLE IF NOT EXISTS $pic2dtable (
  `$idname` INT(11) NOT NULL DEFAULT '0',
  `type` TINYINT NOT NULL DEFAULT '1' COMMENT '1 = png',
  `status` TINYINT NOT NULL DEFAULT '0' COMMENT '0 = does not exist, 1 = OK, 2 = OK, but do not show, 3 = to be created/updated, 4 = to be deleted',
  `svg` TEXT CHARACTER SET BINARY NOT NULL,
  PRIMARY KEY ($idname)
  ) ENGINE = MYISAM CHARACTER SET $mysql_charset COLLATE $mysql_collation COMMENT='Housekeeping for 2D depiction'";
  #print "$createcmd\n";
  $dbh->do($createcmd);
  # next, check existing pic2dtables if svg column already exists and create if not
  $sth0 = $dbh->prepare("DESCRIBE $pic2dtable");
  $sth0->execute();
  $foundsvg = 0;
  while ($ref0 = $sth0->fetchrow_hashref()) {
    $field = $ref0->{'Field'};
    if (index($field,'svg')==0) { $foundsvg = 1; }
  }
  $sth0->finish();
  if ($foundsvg == 1) {
    if ($verbose > 0) { print "  table $pic2dtable is OK\n"; }
  } else {
    if ($verbose > 0) {  print "  table $pic2dtable needs to be fixed\n"; }
    $altercmd = "ALTER TABLE `$pic2dtable` ADD `svg` BLOB NOT NULL;";
    #print "      $altercmd\n";
    $dbh->do($altercmd);
  }
  
  # next, check if number of records in structable and pic2dtable matches
  $sth0 = $dbh->prepare("SELECT COUNT($idname) AS itemcount FROM $structable");
  $sth0->execute();
  while ($ref0 = $sth0->fetchrow_hashref()) {
    $scount = $ref0->{'itemcount'};
  }
  $sth0->finish();
  $s_nchunks = int( (($scount + 99) / 100) );
  $sth0 = $dbh->prepare("SELECT COUNT($idname) AS itemcount FROM $pic2dtable");
  $sth0->execute();
  while ($ref0 = $sth0->fetchrow_hashref()) {
    $pcount = $ref0->{'itemcount'};
  }
  $sth0->finish();
  $p_nchunks = int( (($scount + 99) / 100) );
  #print "structures: $scount  pic2d entries: $pcount\n";
  if ($scount != $pcount) {
    for ($j = 0; $j < $s_nchunks; $j++) {
      $s_offset = $j * 100;
      $sth2 = $dbh->prepare("SELECT $idname FROM $structable LIMIT $s_offset,100");
      $sth2->execute();
      while ($ref2 = $sth2->fetchrow_hashref()) {
        $my_id   = $ref2->{"$idname"};
        $sth3 = $dbh->prepare("SELECT COUNT($idname) AS mycount FROM $pic2dtable WHERE $idname = $my_id");
        $sth3->execute();
        $my_count = 1;
        while ($ref3 = $sth3->fetchrow_hashref()) {
          $my_count   = $ref3->{"mycount"};
        }
        $sth3->finish();
        if ($my_count == 0) {
          # now add the missing record
          if ($verbose > 1) { print "   adding missing record ($my_id)\n"; }
          $updstr = "INSERT INTO $pic2dtable VALUES ($my_id, \"1\", \"3\" )";
          $dbh->do($updstr);
        }
      }  # while ..
      $sth2->finish();
    }   # for ..
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


sub ltrim() {
  $subline1 = shift;
  $subline1 =~ s/^\ +//g;
  return $subline1;
}

sub getval() {
  my $argline = shift;
  chomp($argline);
  my @argpart = split(/=/, $argline);
  my $argval = "";
  if ($#argpart > 0) { $argval = $argpart[1]; }
  return $argval;
}
