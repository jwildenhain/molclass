#!/usr/bin/perl
#
# smiles2sdf.pl   
# Ryusuke Kimura, University of Edinburgh, 2010-2011
# 	R.Kimura@sms.ed.ac.uk
#
# This script adds smiles to each molecule in SDF file
# Firstly, it executes open babel program to calculate smiles for each molecule in SDF file
# Those smiles are written to output file.
# Then, it extracts those smiles and write them back to SDF file 
######################################################################

use File::Basename;

# Get SDF File Name
$sdffile = $ARGV[0];

# Execute open babel and produce output file which contains smiles
$cmd = "obabel -isdf $sdffile -O $sdffile.smi";
system $cmd;

# Write InChI key to SDF file
$smilesfile = "$sdffile.smi";

$sdffile = "$sdffile";
open(SDF, "<$sdffile") || die ("cannot open sdf file $sdffile!");

$sdfsmilesfile = "$sdffile.smiles";
open(SDFSMILES, ">$sdfsmilesfile") || die ("cannot open sdfinchikey file $sdfsmilesfile!");

$countmol = 1;

while($line = <SDF>)
{
    $delimiter = substr($line, 0, 4);
    if($delimiter =~ /\$\$\$\$/)
    {
        open (SMILES, "<$smilesfile") || die ("cannot open inchikey file $smilesfile!");
	$countsmiles = 1;
	while($line2 = <SMILES>)
	{ 
	    @array = split (/\s+/, $line2);
	    if($countmol==$countsmiles)
	    {
                $smiles = $array[0];
	    }
	    $countsmiles++;	
	}
	close(SMILES);
        print SDFSMILES "> <SMILES MolClass>\n";
        print SDFSMILES "$smiles\n\n";
        print SDFSMILES "$delimiter\n";
	$countmol++;
    }
    else
    {
        print SDFSMILES $line;
    }
}

$cmd = "sudo chmod 755 $sdffile";
system $cmd;

$cmd = "sudo mv $sdfsmilesfile $sdffile"; 
system $cmd;




