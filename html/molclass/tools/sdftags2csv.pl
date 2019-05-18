#!/usr/bin/perl
# usage: store all the tag information into a tab separated file
# author: Jan Wildenhain
# date: June 13th 2006

use strict;
use FileHandle;

my $fh = new FileHandle;
my $outfh = new FileHandle;

my @sdf_stack;
my %multilinetaginfo; # store all multi line tags and occurence.
my @dataset;
my %attribute_hash;

my $verbose = 0;   # change this into 1 for verbose mode

if ($#ARGV < 0) {
  print "Usage: sdftags2csv.pl <inputfile> <outputfile>\n";
  exit;
}

my $ifile = "$ARGV[0]" or die "no inputfile given\n";
my $ofile = "$ARGV[1]" or die "no outputfile given\n";
my $addLIBTAG = "";
my $multitaglineseparator = ";"; 
my $stdlineseparator = "\t";
if ($ARGV[2]) { $addLIBTAG = $stdlineseparator.$ARGV[2]; }

my $nextmol =0; # count through datasets
my $colcount = 0; # output file columns
my $linecount = 0; # check all lines
my $intag ="";
my $max_intag_line_count = 0; # check for multiline TAGs
my $emptylinecount;

&findColumnIdentifier($ifile,$ofile);

sub findColumnIdentifier{
	
	(my $FILENAME, my $FHTable) = @_;
	$fh->open("< $FILENAME");

	
	my $intag_line_count = 0;
	
	while( my $line = <$fh> ) {
	    $linecount++;
		chomp($line);
	    if ($line =~ m/$multitaglineseparator/) { print "Warning, your tabbing char ($multitaglineseparator) is used in SDF file use another one!\n"; print "$linecount: $line\n";}
		 # get tag information and store it
		
		if ($intag =~ m/\w+/ && $attribute_hash{$intag} && $line !~ m/^>/ && $line !~ m/^\$\$\$\$/) {
			#$fh->getline;
			#print $line."\n";
			$intag_line_count++;
			if (length($line) && $intag_line_count >= 2) {
			       $multilinetaginfo{$linecount} = $intag;
			       $dataset[$nextmol][$attribute_hash{$intag}] = $dataset[$nextmol][$attribute_hash{$intag}] .$multitaglineseparator. $line;
			       if ($intag_line_count > $max_intag_line_count) { $max_intag_line_count = $intag_line_count; }
		    } elsif (length($line) == 0) {
		           $emptylinecount++;
		           if ($emptylinecount >= 2) { print "Error. Unexpected empty line: $linecount\n"; } #exit 1; }
			} else {
				   $emptylinecount = 0;
				   $dataset[$nextmol][$attribute_hash{$intag}] = $dataset[$nextmol][$attribute_hash{$intag}] . $line;
			}
		}
		
		 # get the tags 
		 if ( $line =~ m/^>\s*<(.+)>/ ) {
			#print $line . "";
			#$line =~ m/<(.+)>/;
			$intag = $1;
			$intag_line_count = 0;
			if ($attribute_hash{$intag}) {
				next;
			} else {
				# start with an empty dataset entry
				$colcount++;
				$attribute_hash{$intag} = $colcount;
				$dataset[$nextmol][$attribute_hash{$intag}] = "";
			}
		
		 }
         if ($line =~ m/^\$\$\$\$/) {
            $nextmol++;
			$intag ="";
		 }
	}
}   

print "------------------------------------------------------------\n";
print "Number of Structures: $nextmol\n";
print "Multi Line Tag:";
if ($max_intag_line_count >= 2) { print " Yes, character used for tabbing ($multitaglineseparator)"; } else {print " No"; }
print "\n";
my $multitagcounts = 0;
if (%multilinetaginfo) {
     while ((my $key, my $value) = each(%multilinetaginfo))  {
           #print $key.", ".$value."\n";
           $multitagcounts++;
     }
}
print "Multi Line Tag counts:$multitagcounts\n";
print "SDF tag(s):\n";
my @sortedheader;
while ((my $key, my $value) = each(%attribute_hash)){
       print $key.", ".$value."\n";
       $sortedheader[$value-1] = $key; 
}
my $header ="";
foreach my $num (@sortedheader) {
	   $header = $header.$num.$stdlineseparator;
}
if ($addLIBTAG) { $header = $header."db".$stdlineseparator; }


$outfh->open("> $ofile");
$header =~ s/$stdlineseparator$//;
$outfh->print("$header\n");
for (my $i = 0;$i < $nextmol;$i++) {
       for (my $j = 1;$j <= $colcount-1;$j++) {
			$outfh->print("$dataset[$i][$j]\t");
	   }
            
	   $outfh->print("$dataset[$i][$colcount]".$addLIBTAG."\n");
}
print "------------------------------------------------------------\n";
