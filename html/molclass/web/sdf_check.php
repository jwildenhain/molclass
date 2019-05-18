<?php

////////////////////////////////////////////////////////////////////////////////////
require_once "PEAR.php";
require_once 'HTML/Page2.php';
require_once 'HTML/CSS.php';
require_once('Config.php');
require_once('functions.php');
require_once 'Savant3.php';
// Add Database Table + DataGrid to display content in table
require_once "DB/DataObject.php";
require_once "Structures/DataGrid.php";  

// create session variable to trace gene selections
session_start();

// show all errors
error_reporting(E_ALL & ~E_NOTICE);
PEAR::setErrorHandling(PEAR_ERROR_DIE); 

// lead configuration file website
$conf = new Config();
$root =& $conf->parseConfig('molclass.conf.xml', 'XML');
if (PEAR::isError($root)) {
    die('Error while reading configuration: ' . $root->getMessage());
}
$settings = $root->toArray();
$mainheader = convertNavigationBarArray('Navigation_Board_Open',$settings['root']['config']);

// create/load Template and start basic html layout
$css = new HTML_CSS();
$css->parseFile("css/tyersstylesheet001.css");

// create a new HTML page
$p = new HTML_Page2();
$p->setTitle("MolClass");
// it can be added as an object
$p->addStyleDeclaration($css, 'text/css');
$p->setMetaData("Author", "Jan Wildenhain & Ryusuke Kimura");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass predicts bioactivity of small molecule by means of Machine Learning method with high throughput screening data.");
$p->setMetaData("Keywords", "highthroughput screening, protein, machine learning");

// Add template for Website Header - later on also for the Footer
// include class
// create object
// set template directory
// Load the Savant2 class file and create an instance.
$tpl =& new Savant3();

// Assign values to the Savant instance.
$tpl->assign('mainheader', $mainheader);
$tpl->assign('maintableimage',$settings['root']['config']['site_header_image']);
$tpl->assign('url',$settings['root']['config']['url']);

// Display a template using the assigned values.
$p->addBodyContent($tpl->fetch('templates/savant_header.tpl.php'));
$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_top.tpl.php'));

// set Smarty directories
$smarty->template_dir = './templates';
$smarty->compile_dir  = './templates_c';

require_once 'Smarty.class.php';
$smarty = new Smarty;

require_once "login_check.php";

////////////////////////////////////////////////////////////////////////////////////

//include_once('global_conf.inc.php');
include_once("moldbconf.php");
global $sdf_base;
global $CONF;
global $email;
global $mol_type;
$hitArray = array();
$cntnum;

// run sdfcheck on sdf
if(isset($_GET['sdf']))
{
  $sdf_base = $_GET['sdf'];
  $_SESSION['sdf_base'] = $sdf_base;
  $sdfid = $_GET['sdfid'];
}
else
{
  $sdf_base=$_SESSION['sdf_base'];
}

//place to upload and basename
$sdf_target = $settings['root']['config']['php_upload'].$sdf_base;

$smarty->assign( 'title', 'Upload SDF File' );
$smarty->assign( 'message', 'Please wait while the sdf definitions are analysed...' );

$p->addBodyContent($smarty->fetch('templates/form_sdf_check.tpl.php'));

if(isset($_GET['sdf']))
{

  // Analyse the the sdf file with sdfcheck_alt.pl (in tools/moldb5)
  $cmd = "perl ".$toolsdir."sdfcheck.pl ".$sdf_target." > /dev/null";
  $ps = run_in_background($cmd);

/*
  while(is_process_running($ps))
  {
    echo str_pad('',256);
    ob_flush();
    flush();
    sleep(1);
  }
*/
  sleep(5);
  // Check whether uploaded SDF file is valid
  $def_target = $settings['root']['config']['php_upload']."sdf2moldb_".$sdf_base.".def";
  $cmd = "perl ".$toolsdir."sdf_validation.pl ".$def_target;
  exec($cmd, $out);

  if($out[0])
  {
    header("location:sdf_2moldb.php?sdf=".$sdf_base."&sdfid=".$sdfid);
  }
  else
  //If SDF file is invalid, it removes temp directory for uploading sdf file
  {
    $cmd = "rm ".$settings['root']['config']['php_upload'].$sdf_base;
    exec($cmd);
    $cmd = "rm ".$settings['root']['config']['php_upload']."sdf2moldb_".$sdf_base.".def";
    exec($cmd);
    header("location:sdf_upload.php?validation=false");
  }
}
///// Sub Routine ////

function run_in_background($Command, $Priority = 0)
{
  if($Priority)
    $PID = shell_exec("nohup nice -n $Priority $Command 2> /dev/null & echo $!");
  else
    $PID = shell_exec("nohup $Command 2> /dev/null & echo $!");
  return($PID);
}

function is_process_running($PID)
{
  exec("ps $PID", $ProcessState);
  return(count($ProcessState) >= 2);
}

////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->display();
////////////////////////////////////////////////////////////////////////////////////

?>
