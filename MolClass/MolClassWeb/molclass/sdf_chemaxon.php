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
$root =& $conf->parseConfig('molclass.conf.xml', 'XML');
if (PEAR::isError($root)) {
    die('Error while reading configuration: ' . $root->getMessage());
}
$settings = $root->toArray();

if(!isset($_GET['cID'])) {
      $_GET['cID'] = 002;
}

$cache = new Cache('file', array('cache_dir' => 'cache/')); 

$id = $cache->generateID($_GET['cID']);

$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db)) 
{
  die($db->getMessage());
}


    $sql = "SELECT * FROM inchi_key WHERE mol_id = ".$_GET['cID']."";
      $result_one = $db->query($sql);
      $row = $result_one->fetchRow(DB_FETCHMODE_ASSOC);
      $SDF = $row['smiles'];
      $NAME = $row['mol_id'];

if (strlen($SDF) > 4) { 
//print($settings['root']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['Global_Design_Parameters']['tool_mol2png_options']." -s '".$SDF."' -o ".$settings['root']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.png");

$emessage = system($settings['root']['config']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['config']['Global_Design_Parameters']['tool_mol2png_options']." -s '".$SDF."' -o ".$settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png");
#print($emessage);

// make sure if upload folder for png's does not exist create one otherwise it apache2.log will go hell!
if (!file_exists($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/")) {
    mkdir($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/", 0777, true);
}
if (!file_exists($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."mol/")) {
    mkdir($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."mol/", 0777, true);
}

$fp = fopen($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png", "rb");
while(!feof($fp))
{
  $data .= fread($fp, 1024); // removed. before .=
}
fclose($fp);


header("Content-Type: image/png\n");
header("Content-Transfer-Encoding: binary\n");
header("Content-length: " . strlen($data) . "\n");

//send file contents
print($data);

} else {



      $sql = "SELECT * FROM moldb_molstruc WHERE mol_id = ".$_GET['cID']."";
      $result_one = $db->query($sql);
      $row = $result_one->fetchRow(DB_FETCHMODE_ASSOC);
      $SDF = $row['struc'];
      $NAME = $row['mol_id'];
      $cache->save($id, $SDF,86400);
      $fp = fopen($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.mol", "w");
      // Write the data to the file
      fwrite($fp, $SDF);
      // Close the file
      fclose($fp);

//print($settings['root']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['Global_Design_Parameters']['tool_mol2png_options']." ".$settings['root']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.mol -o ".$settings['root']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.png");

$emessage = system($settings['root']['config']['Global_Design_Parameters']['tool_mol2png_application']." ".$settings['root']['config']['Global_Design_Parameters']['tool_mol2png_options']." ".$settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."mol/$id.mol -o ".$settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png");
//print($emessage);


$fp = fopen($settings['root']['config']['Global_Design_Parameters']['tool_upload_folder']."png/$id.png", "rb");
while(!feof($fp))
{
  $data .= fread($fp, 1024); // removed . before .=
}
fclose($fp);

header("Content-Type: image/png\n");
header("Content-Transfer-Encoding: binary\n");
header("Content-length: " . strlen($data) . "\n");

//send file contents
print($data);

}
?>
