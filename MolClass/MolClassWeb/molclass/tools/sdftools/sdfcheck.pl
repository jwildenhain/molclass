#!/usr/bin/perl
#
# sdfcheck.pl    Norbert Haider, University of Vienna, 2005-2010
#                norbert.haider@univie.ac.at
#
# This script is part of the MolDB5R package. Last change: 2010-06-15
#
# This script reads and analyzes an SD file in order to
# generate a definition for the corresponding MySQL tables
# (as part of a MolDB5R database).
#
# The resulting definition file is then used (after manual
# editing, if necessary) by the script "sdf2moldb.pl" in 
# order to import the contents of the SD file.
# 
# update 2013 for MolClass and ChemGRID - jw
# change outfile to stay within folder/upload restrictions
# fix default values to be conform with every upload
#

$maxrecords       = 10000;    # how many records should we inspect?
$default_dbid     = 1;        # integer number, starting from 1
$default_dbname   = "moldb";
$default_dbaccess = 1;        # 0 = disabled, 1 = read-only, 2 = read/write

$outfile = "sdf2moldb.def"; # do not change

if ($#ARGV < 0) {
  print "Usage: sdfcheck.pl <inputfile>\n";
  exit;
}

use File::Basename;

$infile = $ARGV[0];
$inbase = basename($infile);
$indir = dirname($infile);

$outfile = "$indir/sdf2moldb_".$inbase.".def"; # do not change

open (SDF, "<$infile") || die ("cannot open SD file $infile!");

open (DEF, ">$outfile") || die ("cannot create definition file $outfile!");

$counter  = 0;
$li       = 0;
$buf      = '';
$mol      = '';
$txt      = '';
$lbl      = '';
$ct       = 1;
$nfields  = 0;

print "analyzing SD file '$infile', please be patient...\n";

while ($line = <SDF>) {
  $line =~ s/\r//g;
  if (substr($line,0,4) eq '$$$$') {
    $ct  = 1;
    $txt = $buf;
    $counter ++;
    process_data($txt);
    $buf = "";
  } else {
    if (substr($line,0,1) eq '>') { 
      if ($ct eq 1) {
        $mol = $buf;
        $buf = "";
      }
      $ct = 0; 
    }
    $buf = $buf . $line;
  }
  if ($counter > $maxrecords) { 
    #show_result();
    write_outfile();
    exit; 
  }
}        # end while ($line....

#show_result();
write_outfile();

#===================== subroutines =======================================

sub process_data() {
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
      @lblrec = split(/\</, $element);
      $lblname = $lblrec[1];
      @lblrec = split(/\>/, $lblname);
      $lblname = $lblrec[0];
      #print "label: $lblname\n";
    } else {
      if (length($element) > 0) {
        $databuf = $databuf . $element;
        #print "adding element\n";
      } else {
        $lblchars = length($databuf);
        # print "\tentering new record:\n\t\t$lblname\n\t\t$databuf\n\t\t($lblchars)\n";
        # some more checking here....
        $datatype = lbltype($databuf); 
        # debug
        # print "\t\t$datatype\n\n";

        $databuf = "";
        if ($counter == 1) {
          $nfields++;
          @afield[($nfields-1)] = [ $lblname, $lblchars, $datatype ];
        } else {
          $newfield = 1;
          for ($j = 0; $j <= $#afield; $j++) {
            $knownlbl = $afield[$j][0];
            if ($lblname eq $knownlbl) { 
              $newfield = 0; 
              $lblindex = $j;
            }
          }
          if ($newfield > 0) {
            $nfields++;
            @afield[($nfields-1)] = [ $lblname, $lblchars, $datatype ];
          } else {
            $maxlblchars = $afield[$lblindex][1];
            if ($lblchars > $maxlblchars) {
              $afield[$lblindex][1] = $lblchars;
            }
            $prevdatatype = $afield[$lblindex][2];
            if (! $prevdatatype eq $datatype) {
              print "CHANGING DATA TYPE!\n";	
            }
          }
        }
      }
    }
  }
}

sub lbltype() {
  $intstr   = "+-0123456789";
  $floatstr = "+-.0123456789eE";
  $lblstr = $_[0];
  $tmptype = "string";
  $intOK   = 1;
  $floatOK = 1;
  if (length(lblstr) eq 0) {
    $intOK   = 0;
    $floatOK = 0;
    $tmptype = "string";
  } else {
    $signcount = 0;
    $ecount    = 0;
    for ($ii = 0; $ii < length($lblstr); $ii++) {
      $testchar = substr($lblstr,$ii,1);
      if (index($intstr,$testchar) < 0)   { $intOK   = 0; }
      if (index($floatstr,$testchar) < 0) { $floatOK = 0; }
      if (uc($testchar) eq "E") { $ecount++; }
      if ($floatOK eq 1) {
        if (($testchar eq "-") || ($testchar eq "+")) { 
          $signcount++;
      	  $validsign = 1;
      	  if (($ii eq 0) || (($ii > 0) &&  (uc(substr($lblstr,($ii-1),1)) eq "E" ))) {
      	    $validsign = 1;
      	  } else {
      	    $validsign = 0;	
      	  }
      	  if ($signcount > 2) {
      	    $validsign = 0;
      	  }
      	  if (($ecount > 1) || ($validsign eq 0)) {
      	    $floatOK = 0;
      	    $intOK   = 0;
      	  }
        }  #  + or -
      }
    }	
  }
  if ($intOK   eq 1) { $tmptype = "int";   } else {
    if ($floatOK eq 1) { $tmptype = "float"; } 
  }
  return $tmptype;	
}

sub show_result() {
  $inspected = $counter - 1;
  print "records inspected: $inspected\n";
  print "nfields: $nfields\n";
  print "=================================\n";
  for ($j = 0; $j <= $#afield; $j++) {
    $l1 = $afield[$j][0];
    $l2 = $afield[$j][1];
    $l3 = $afield[$j][2];
    print " $l1  (max. $l2 characters) type $l3\n";
  }
}

sub write_outfile() {
  $inspected = $counter - 1;
  print DEF "# This file was created automatically by sdfcheck.pl.\n";
  print DEF "# It contains the mapping of SDF data fields and the\n";
  print DEF "# corresponding MySQL field names + proposed field types.\n";
  print DEF "#\n";
  print DEF "# Please inspect the definition lines below and, if necessary,\n";
  print DEF "# edit the MySQL field names and/or types. If a particular\n";
  print DEF "# field is not to be imported into the MySQL table, simply\n";
  print DEF "# delete the corresponding definition line.\n";
  print DEF "#\n";
  print DEF "# records inspected: $inspected\n";
  print DEF "# number of data fields found: $nfields\n";
  print DEF "#\n";
  print DEF "sdfilename=$infile\n";
  print DEF "#\n";
  print DEF "# database definitions:\n";
  print DEF "db_id=$default_dbid\n";
  print DEF "db_type=1\n";
  print DEF "db_name=\"$default_dbname\"\n";
  print DEF "db_description=\"This data collection was imported from an SDF file.\"\n";
  print DEF "db_access=$default_dbaccess    # 0 = disabled, 1 = read-only, 2 = add/update, 3 = full access\n";
  print DEF "bitmapfile_digits=8\n";
  print DEF "bitmapfile_subdirdigits=4\n";
  print DEF "#\n";
  print DEF "# Format of definition lines:\n";
  print DEF "# SDF_field_name:MySQL_field_name:MySQL_field_type:HTML_field_name:HTML_format:::comment\n";
  print DEF "#\n";
  print DEF "# VERY IMPORTANT:\n";
  print DEF "# a) Please change the MySQL field name of the most descriptive\n";
  print DEF "#    data field into 'mol_name' (without the quotes).\n";
  print DEF "# b) Please make sure that there is no MySQL field name 'mol_id'\n";
  print DEF "#    as this name is reserved for internal use by the system.\n";
  print DEF "#\n";
  for ($j = 0; $j <= $#afield; $j++) {
    $l1  = $afield[$j][0];
    $l2  = $afield[$j][1];
    $l3  = $afield[$j][2];
    $l1a = lc($l1);
    # replace dots by underscores
    $l1a =~ s/\./_/g;
    print DEF "$l1:$l1a:";
    if ($l3 eq "int")   { print DEF "INT(11) NOT NULL:"; }
    if ($l3 eq "float") { print DEF "DOUBLE NOT NULL:"; }
    if ($l3 eq "string") {
      if ($l2 <  5)  { print DEF "VARCHAR(10) NOT NULL:"; } else {
      if ($l2 < 10)  { print DEF "VARCHAR(20) NOT NULL:"; } else {
      if ($l2 < 25)  { print DEF "VARCHAR(50) NOT NULL:"; } else {
      if ($l2 < 50)  { print DEF "VARCHAR(100) NOT NULL:"; } else {
      if ($l2 < 100)  { print DEF "VARCHAR(200) NOT NULL:"; } else {
      if ($l2 < 200) { print DEF "VARCHAR(255) NOT NULL:"; } else {
                       print DEF "TEXT NOT NULL:"; }}}}}}
    }
    print DEF "::::";  # empty fields for HTML label name and format, reserved options
    if ($l3 eq "string" ) {print DEF "max. $l2 characters"; }
    print DEF "\n";
  }
  print "Definition was written to file '$outfile', please inspect and edit it\n";
  print "before you use it for data import with sdf2moldb.pl!\n";
}
