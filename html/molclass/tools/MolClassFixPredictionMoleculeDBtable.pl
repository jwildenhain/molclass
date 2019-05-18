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

$startFrom = $ARGV[0];
$goTo = "";
if (defined($ARGV[1])) { $goTo = $ARGV[1]; } else { $goTo = $ARGV[0]; }


$ndb = 0;
@db = [""];
@dbm = [""];
@dbprint = [""];

$sth0 = $dbh->prepare("SELECT pred_id, model_id, printout FROM prediction_list WHERE pred_id between $startFrom and $goTo");
$sth0->execute();
while ($ref0 = $sth0->fetchrow_hashref()) {
  $pred_id = $ref0->{'pred_id'};
  $printout = $ref0->{'printout'};	
  $ndb++;
  @db[($ndb-1)] = $pred_id;
  @dbm[($ndb-1)] = $ref0->{'model_id'};
  @dbprint[($ndb-1)] = $printout;
  
  
}
$sth0->finish;

for ($i = 0; $i < $ndb; $i++) {
  
  $dbnum = @db[$i];
  $dbmodel = @dbm[$i];
  if ($verbose > 0) {
    print "processing data Prediction ID $dbnum: \n";
  }
  
  $skip = 1;
  $str = @dbprint[$i];
  while($str =~ /([^\n]+)\n?/g){
  

  		unless($skip) {
             #print "LINE: $1\n";
             $varTemp = $1;

             $varTemp =~ tr/\*//d;
             @stuff = split('\s+',$varTemp);

             #$setClass = $stuff[0];
             #if ($stuff[2] =~ m/\*/) { $skip = 0; }

             
             print($dbnum."-".$dbmodel." Found:".$stuff[0]." ".$stuff[1]." ".$stuff[2]." ".$stuff[3]." ".logIt($stuff[2],0.001)."\n");
             
             #$stmt = "DELETE FROM prediction_mols WHERE mol_id = ? AND pred_id = ?";
             #$sth  = $dbh->prepare($stmt);
             #$sth->bind_param( 1, $stuff[0] ); 
             #$sth->bind_param( 2, $dbnum );            
             #$sth->execute() || &die_clean("Couldn't execute\n".$dbh->errstr."\n" ) ;
             
             #print(" deleted.\n");
             
             #print($stuff[0]." ".$stuff[1]." ".$stuff[2]." ".$stuff[3]." "."\n");
             # update prepare statement
             $stmt = "INSERT INTO prediction_mols (mol_id, pred_id, main_class, distribution, lhood) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE mol_id = ? , pred_id = ?";
             $sth  = $dbh->prepare($stmt);
             $sth->bind_param( 1, $stuff[0] );
             $sth->bind_param( 2, $dbnum );
             $sth->bind_param( 3, $stuff[1] );
             $sth->bind_param( 4, $stuff[2]."\t".$stuff[3]."\t" );
             $sth->bind_param( 5, logIt($stuff[2],0.001) );
             $sth->bind_param( 6, $stuff[0] ); 
             $sth->bind_param( 7, $dbnum );            
             $sth->execute() || &die_clean("Couldn't execute\n".$dbh->errstr."\n" ) ;
             
        }
        if ($1 =~ m/mol_id/) { $skip = 0; }
}
  
  #$e_molid
  #$e_class
  #$e_a
  #$e_b
  #stmt = new String("INSERT INTO " + predmoltable
#		+ "(mol_id, pred_id, main_class, distribution, lhood) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE mol_id = ? , pred_id = ?");
  
  
  #$sql = "update prediction_list set pred_name = '".timestamp()."' WHERE pred_id = ".$dbnum.";";
  #$rs = $dbh->prepare( $sql );
  #$rs->execute() || &die_clean("Couldn't execute\n$sql\n".$dbh->errstr."\n" );
 
  #($sql_update_result) = $rs->fetchrow;
  # $dbh->do("update prediction_list set pred_name = ? where pred_id = $dbnum", timestamp());
  
}


 # drop molstat table if it exists already


 
$dbh->disconnect();

sub logIt {
      $p = @_[0];
      $offset = @_[1];
      $loglike = log( ($p + $offset) / (1 + $offset - $p) );
      return($loglike);
 }

sub timestamp {
  my $t = localtime;
  return sprintf( "%04d-%02d-%02d_%02d-%02d-%02d",
                  $t->year + 1900, $t->mon + 1, $t->mday,
                  $t->hour, $t->min, $t->sec );
}

#============================================================



