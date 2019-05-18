#!/usr/bin/perl
#
# version 0.1 alpha
# author Jan Wildenhain
# reason: add unique ID to each molecule in the sdf file

use strict;
use FileHandle;
use DBI;
use POSIX qw(ceil floor);
my $fh = FileHandle->new;
my $fhout = FileHandle->new;
my $molcount =0;

my $verbose = 0;   # change this into 1 for verbose mode

if ($#ARGV < 0) {
  print "Usage: sdf_add_unique_id.pl <inputfile> <outputfile> <sdf file label> <unique id tag>\n";
  exit;
}

my $FILENAME = "$ARGV[0]";
my $FOUT = "$ARGV[1]";
my $DISTR = "$ARGV[2]";
my $UIDTAG = "$ARGV[3]";

$fh->open("< $FILENAME");
$fhout->open("> $FOUT");

while ( my $line = <$fh> ) {
   $fhout->print($line);
   if ($line =~ m/^M  END/) {
       $molcount++;
       $fhout->print("> <$UIDTAG>\n");
       $fhout->print("$DISTR $molcount\n\n");
   }
}

$fh->close();
$fhout->close();
