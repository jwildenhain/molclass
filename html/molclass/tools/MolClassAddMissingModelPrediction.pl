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
$verbose    = 1;  # 0 = silent operation, 
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

# get batches with data


$ndb2 = 0;
@dbb = [""];
$sth0 = $dbh->prepare("SELECT batch_id FROM batchlist WHERE uploaded = 1 ORDER BY batch_id");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $batch_id = $ref0->{'batch_id'};
  $ndbb++;
  @dbb[($ndbb-1)] = $batch_id;
}
$sth0->finish;

# get models with/without data

$ndb = 0;
@dbm = [""];
$sth0 = $dbh->prepare("SELECT model_id FROM class_models ORDER BY model_id");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $pred_id = $ref0->{'model_id'};
  $ndbm++;
  @dbm[($ndbm-1)] = $pred_id;
}
$sth0->finish;


for ($i = 0; $i < $ndbb; $i++) {
  for ($j = 0; $j < $ndbm; $j++) {  
  
     $batchnum = @dbb[$i];
     $modelnum = @dbm[$j];
     if ($verbose > 0) {
        print "processing Batch Model predictions $batchnum - $modelnum \n";
     }
     
     $sql = "select pred_id from prediction_list where model_id like ".$modelnum." and batch_id like ".$batchnum;
     ($value) = $dbh->selectrow_array($sql);
     
     if ($value) 
        { print "Found.\n"; } 
     else 
        { 
          print "insert pair on ". timestamp."\n";
          $sql = "insert into prediction_list (pred_name, model_id, batch_id, username, email) values ('add emtpy on ".timestamp()."',".$modelnum.",".$batchnum.",'MolClass','admin');";
          $rs = $dbh->prepare( $sql );
          $rs->execute() || &die_clean("Couldn't execute\n$sql\n".$dbh->errstr."\n" );    
        
        }
     
     #system("java -jar MolClass.jar Predictor $dbnum");
     #$dbh->do("update prediction_list set pred_name = '", timestamp() ,"' where pred_id = $dbnum");
  }
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



