#!/usr/bin/perl

#
# adjust the following lines to your server settings
#

#$configfile = "/home/jw/public_html/molclass/tools/sdftools/xmlreader.pl";
$configfile = "./tools/sdftools/xmlreader.pl";

#
# Please do not make changes below if you are not sure what you need to do
#

use DBI();
use Cwd;
$curdir = getcwd;

$return     = do $configfile;
if (!defined $return) {
  die ("cannot read configuration file $configfile! from $curdir :\n $@");
}

$username = $ARGV[0];
$model_id = $ARGV[1];
$pred_name = $ARGV[2];
$email = $ARGV[3];

### Create Model ###
$cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/weka2.jar:lib/libsvm.jar:lib/hiddenNaiveBayes.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.ModelBuilder ".$model_id." 1>> ./log/output_modelbuilder.log"." 2>> ./log/error_modelbuilder.log";

system $cmd;
#system $cmd;

### Send Email After Creation of Model ###
$url = $web_server_location."/view_model_detail.php?model_id=".$model_id;
sendEmail($email, "MolClass", "Model Creation Complete", "Your model creation is complete. You can check created model at $url");

### Connect to DB ###

$user     = $rw_user;    # from configuration file
$password = $rw_password;

$dbh = DBI->connect("DBI:mysql:database=$database;host=$hostname",$user, $password,
                    { RaiseError => 1}
                    ) || die ("database connection failed: $DBI::errstr");

### Calculate Prediction for each test molecule set ###

$query2 = "SELECT batch_id, mol_type FROM $batchlisttable";
print "$query2\n";
$sth2 = $dbh->prepare("$query2");
$sth2->execute;
while(@row = $sth2->fetchrow_array())
{
  $batch_id = $row[0];
  $batchtype = $row[1];
  $pred_name = $pred_name.$batch_id;
  $query = "INSERT INTO $predtable (username, batch_id, model_id, pred_name, email) VALUES ('$username', $batch_id, $model_id, '$pred_name', '$email_dummy')";
  $sth = $dbh->prepare($query);
  $sth->execute;
  $pred_id = $dbh->last_insert_id(undef, undef, qw(a_table a_table_id));
  $cmd = "java $setHeapSize -cp lib/cdk-1.4.18.jar:lib/weka2.jar:lib/libsvm.jar:lib/hiddenNaiveBayes.jar:lib/mysql-connector-java-5.1.17-bin.jar:MolClass.jar  nick.test.Predictor $pred_id 1>> ./log/output_predictor.log"." 2>> ./log/error_predictor.log";



  print "$cmd\n";
  #system $cmd;
  system $cmd;
  $sth->finish( );
}
$sth2->finish();

### Send Email After Prediction against existing test molecules ###
$url = $web_server_location."/view_batch_detail.php?batch_id=".$batch_id;
sendEmail($email, "MolClass", "Prediction Complete", "The Prediction for model $model_id against all existing molecules in a database has been completed. For further details please visit $url");

##################
### Subroutine ###
##################

# Simple Email Function
# # ($to, $from, $subject, $message)
sub sendEmail
{
  my ($to, $from, $subject, $message) = @_;
  my $sendmail = '/usr/lib/sendmail';
  open(MAIL, "|$sendmail -oi -t");
  print MAIL "From: $from\n";
  print MAIL "To: $to\n";
  print MAIL "Subject: $subject\n\n";
  print MAIL "$message\n";
  close(MAIL);
}

