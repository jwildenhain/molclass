<?php
/* Produce nice looking molecule pictures
 * author: Jan Wildenhain
 * last update: 3rd September 2011
 * version: 0.001 
*/
//ini_set('display_errors',   1);

require_once "DB.php";
require_once('Cache.php');
require_once('Config.php');

// lead configuration file website
$conf = new Config();
$root =& $conf->parseConfig('global_config.ini', 'IniCommented');
if (PEAR::isError($root)) {
    die('Error while reading configuration: ' . $root->getMessage());
}
$settings = $root->toArray();

// getting a subarray tree from the configfile
$container = $root->getItem('section', 'Navigation_Board_Open');

$testingsubtree =  $container->toArray();
//print_r($testingsubtree);



/* Database and DataObject setup */
/*
$config = parse_ini_file('global_config.ini',TRUE);
foreach($config as $class=>$values) {
    $options = &PEAR::getStaticProperty($class,'options');
    $options = $values;
}
*/

if(!isset($_GET['cID'])) {
      $_GET['cID'] = 002;
}

$cache = new Cache('file', array('cache_dir' => 'cache/')); 

$id = $cache->generateID($_GET['cID']);

$db = DB::connect($settings['root']['DB_DataObject']['database']);
if (PEAR::isError($db)) 
{
  die($db->getMessage());
}

      $sql = "SELECT * FROM inchi_key WHERE mol_id = ".$_GET['cID']."";
      $result_one = $db->query($sql);
      $row = $result_one->fetchRow(DB_FETCHMODE_ASSOC);
      $SDF = $row['smiles'];
      $NAME = $row['mol_id'];

//print($settings['root']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['Global_Design_Parameters']['tool_mol2png_options']." -s '".$SDF."' -o ".$settings['root']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.png");

$emessage = system($settings['root']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['Global_Design_Parameters']['tool_mol2png_options']." -s '".$SDF."' -o ".$settings['root']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png");
#print($emessage);


$fp = fopen($settings['root']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png", "rb");
while(!feof($fp))
{
  $data .= fread($fp, 1024);
}
fclose($fp);


header("Content-Type: image/png\n");
header("Content-Transfer-Encoding: binary\n");
header("Content-length: " . strlen($data) . "\n");

//send file contents
print($data);


?>
