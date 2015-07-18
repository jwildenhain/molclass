#!/usr/bin/perl
#
# author: Jan Wildenhain
# 
# script modified to use with MolClass/ChemGRID
#


use DBI();
use Time::localtime;

$use_fixed_fields = 0;   # 0 or 1, should be 1 for older versions of checkmol


$configfile = "./tools/sdftools/xmlreader.pl";
$verbose    = 0;  # 0 = silent operation, 
                  # 1 = report data collection, 


$return     = do $configfile;
if (!defined $return) {
  die("ERROR: cannot read configuration file $configfile!\n");
}	

$user     = $rw_user;    # from configuration file
$password = $rw_password;

$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",
                    $user, $password,
                    {'RaiseError' => 1});

# read prediction table and find out which data sets werent processed

$ndb = 0;
@db = [""];
$sth0 = $dbh->prepare("SELECT pred_id FROM prediction_list WHERE printout is NULL ORDER BY pred_id");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $pred_id = $ref0->{'pred_id'};
  $ndb++;
  @db[($ndb-1)] = $pred_id;
}
$sth0->finish;

for ($i = 0; $i < $ndb; $i++) {
  
  $dbnum = @db[$i];
  if ($verbose > 0) {
    print "processing data Prediciton ID $dbnum: \n";
  }
  
  system("java -jar MolClass.jar Predictor $dbnum");
  print(timestamp."/n");
  $sql = "update prediction_list set pred_name = '".timestamp()."' WHERE pred_id = ".$dbnum.";";
  $rs = $dbh->prepare( $sql );
  $rs->execute() || &die_clean("Couldn't execute\n$sql\n".$dbh->errstr."\n" );
 
  #($sql_update_result) = $rs->fetchrow;
  # $dbh->do("update prediction_list set pred_name = ? where pred_id = $dbnum", timestamp());
  
}


 # drop molstat table if it exists already


 
$dbh->disconnect();

sub timestamp {
  my $t = localtime;
  return sprintf( "%04d-%02d-%02d_%02d-%02d-%02d",
                  $t->year + 1900, $t->mon + 1, $t->mday,
                  $t->hour, $t->min, $t->sec );
}

#============================================================



