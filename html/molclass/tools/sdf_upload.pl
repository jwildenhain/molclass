#!/usr/bin/perl
##
## sdf_upload.pl   
## Ryusuke Kimura, University of Edinburgh, 2010-2011
##       R.Kimura@sms.ed.ac.uk
##
#
#  update jw July 2013
#
# perl ./tools/sdftools/sdf_upload.pl './uploads/Model_2_PMID_15446816_MolClass_ds_nb.sdf6296756211375392549' 'root@localhost.org' 'jan.wildenhain@gmail.com' 'learn' '6296756211375392549' '7' '435' 1>> ./log/output_PHP_upload_sdf2moldb.log 2>> ./log/error_PHP_upload_sdf2moldb.log
#
## This program executes programs which is related to SDF uploads.
#######################################################################

#$configfile = "/home/jw/public_html/molclass/tools/sdftools/xmlreader.pl";
$configfile = "./tools/sdftools/xmlreader.pl";


$return     = do $configfile;
if (!defined $return) {  die ("cannot read configuration file $configfile!\n");
}

$sdf_target = $ARGV[0];
$username = $ARGV[1];
$email = $ARGV[2];
$mol_type = $ARGV[3];
$id = $ARGV[4];
$pmid = $ARGV[5];
$info = $ARGV[6];

# inchikey2sdf.pl - This Script calculates inchikey and writes it back to SDF file
#$cmd = "perl ".$toolsdir."inchikey2sdf.pl ".$sdf_target;
#system $cmd;

# sdf2moldb_alt.pl - This script reads an SDF file which was previously analyzed 
# by the script "sdfcheck.pl" and adds its content (structures and data) into a MySQL-based MolDB database.
$cmd = "/usr/bin/perl ".$toolsdir."sdf2moldb.pl '$sdf_target' '$username' '$email' '$mol_type' '$pmid' '$info' '$id'";
system "echo ".$cmd." >> ./log/call_sdf2moldb.log";
system $cmd;

# Remove all uploaded files
#$cmd = "rm ./uploads/*".$id."*";
#system $cmd;

