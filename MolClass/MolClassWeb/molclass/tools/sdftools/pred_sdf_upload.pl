#!/usr/bin/perl

use DBI();

$configfile = "./tools/sdftools/xmlreader.pl";

$return     = do $configfile;
if (!defined $return) {
  die ("cannot read configuration file $configfile!\n");
}

$mol_type = $ARGV[0];
$username = $ARGV[1];
$batch_id = $ARGV[2];
$pred_name = $ARGV[3];
$email = $ARGV[4];

### Connect to DB ###

$user     = $rw_user;    # from configuration file
$password = $rw_password;

$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",$user, $password,
                    { RaiseError => 1}
                    ) || die ("database connection failed: $DBI::errstr");


#if($mol_type eq 'test')
#{
  $query2 = "SELECT model_id FROM $modeltable";
  $sth2 = $dbh->prepare("$query2");
  $sth2->execute;
  while(@row = $sth2->fetchrow_array())
  {
    $model_id = $row[0];
    $query = "INSERT INTO $predtable (username, batch_id, model_id, pred_name, email) VALUES ('$username', $batch_id, $model_id, '$pred_name', '$email')";
    $sth = $dbh->prepare($query);
    $sth->execute;
    $pred_id = $dbh->last_insert_id(undef, undef, qw(a_table a_table_id));
    #$cmd = "java -cp ".$model_pred_dir."weka.jar:".$model_pred_dir."lib:/usr/share/java/mysql-connector-java.jar:".$model_pred_dir."weka:. nick/test/Predictor ".$pred_id;
    #$cmd = "java -jar MolClass.jar Predictor ".$pred_id;
    #$cmd = "java -cp lib/cdk-git-20110515.jar:lib/weka2.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id 1>> ./cache/error_predictor_sdfupload.log";
    #$cmd = "java -cp lib/cdk-1.4.5.jar:lib/weka2.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id 1>> ./cache/error_predictor_sdfupload.log";
    $cmd = "java -cp lib/cdk-1.4.18.jar:lib/weka2.jar:lib/libsvm.jar:lib/hiddenNaiveBayes.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id 1>> ./log/error_predictor_sdfupload.log";
    print "$cmd\n";
    system $cmd;
    
    $sth->finish( );
  }
  $sth2->finish();
#}

