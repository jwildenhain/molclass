#!/usr/bin/perl
##
## sdf_validation.pl   
## Ryusuke Kimura, University of Edinburgh, 2010-2011
##       R.Kimura@sms.ed.ac.uk
##
## This checks validation of SDFile.
## Validation can be done by checking number of column in definition file.
#######################################################################

# Get Definition File Name
$deffile = $ARGV[0];

# Variable for validation
$validation = 1;

open(DEF, "<$deffile") || die ("cannot open definition file");

while($line = <DEF>) 
{
  $check = substr($line, 2, 27);
  $num = substr($line, 31, 1);
  if($check =~ /Number of data fields found/)
  {
    if($num =~ /0/)
    {
      $validation = 0;
    }
  }
}

print "$validation\n";

