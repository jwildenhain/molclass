#!/usr/bin/perl
#
# inchikey2sdf.pl   
# Ryusuke Kimura, University of Edinburgh, 2010-2011
# 	R.Kimura@sms.ed.ac.uk
#
# This script adds inchikey to each molecule in SDF file
# Firstly, it executes inchi program to calculate inchikey for each molecule in SDF file
# Those inchikeys are written output.txt file.
# Then, it extracts those inchikeys and write them back to SDF file 
######################################################################

use File::Basename;

# Get SDF File Name
$sdffile = $ARGV[0];

# Execute InChI and produce output.txt which contains InChI Key
$cmd = "tools/sdftools/inchi-1 -Key $sdffile $sdffile.output.txt uploads/logfile.log";
system $cmd;

# Write InChI key to SDF file
$inchikeyfile = "$sdffile.output.txt";

$sdffile = "$sdffile";
open(SDF, "<$sdffile") || die ("cannot open sdf file $sdffile!");

$sdfinchifile = "$sdffile.inchi";
open(SDFINCHI, ">$sdfinchifile") || die ("cannot open sdfinchikey file $sdfinchifile!");

$countmol = 1;

while($line = <SDF>)
{
    $delimiter = substr($line, 0, 4);
    if($delimiter =~ /\$\$\$\$/)
    {
        open (INCHI, "<$inchikeyfile") || die ("cannot open inchikey file $inchikeyfile!");
	$countinchi = 1;
	while($line2 = <INCHI>)
	{ 
  	    $keycheck = substr($line2, 0, 8);
    	    if($keycheck =~ /InChIKey/)
    	    {
		if($countmol==$countinchi)
		{
                    $inchikey = substr($line2, 9, 36);
		}
	        $countinchi++;	
    	    }
	}
	close(INCHI);
        print SDFINCHI "> <InChI Key MolClass>\n";
        print SDFINCHI "$inchikey\n\n";
        print SDFINCHI "$delimiter\n";
	$countmol++;
    }
    else
    {
        print SDFINCHI $line;
    }
}

$cmd = "chmod 755 $sdffile";
system $cmd;

$cmd = "mv $sdfinchifile $sdffile"; 
system $cmd;




