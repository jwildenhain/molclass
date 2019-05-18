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
include("moldbconf.php");

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
$p->setMetaData("Author", "Jan Wildenhain, Nicholas FitzGerald & Ryusuke Kimura");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass predicts bioactivity from machine learning methods using high throughput screening data.");
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

//require_once "login_check.php";

////////////////////////////////////////////////////////////////////////////////////
// get batch link from mediawiki
 $match ="";
 $batch_id = antiInjectie($_GET['batch_id']);
 $url = $settings['root']['config']['wikilink']; 
 $input = @file_get_contents($url) or die("Could not access file: $url"); 
 $regexp = "<a\s[^>]*href=(\"??)([^\" >]*?)\\1[^>]*>(.*)<\/a>"; 
 $regexp = "<a\s[^>]*href=(\"??)#Batch_ID_".$batch_id."_([^\" >]*?)\\1[^>]*>"; 
 preg_match_all("/$regexp/siU", $input, $matches); 
 //print_r($matches[2]);
 $batchwikilink = '#Batch_ID_'.$batch_id.'_'.$matches[2][0];
 //print_r($batchwikilink);
////////////////////////////////////////////////////////////////////////////////

$smarty->assign( 'title', 'Batch ID : '.$batch_id );

$smarty->assign( 'phpself', "view_batch_detail.php" );
$smarty->assign( 'batch_id', $batch_id );

//Fetch Test Molecule Information
require_once 'DB.php';
$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db)) 
{
  die($db->getMessage());
}

//Get a model statistics
$smarty->assign( 'chosen', 'It is currently showing SDF file of Batch ID '.$batch_id );
$sql = "SELECT * FROM ".$batchlisttable." WHERE batch_id = ".$batch_id;

$res =& $db->query($sql);
if (PEAR::isError($res)) 
{
  die($res->getMessage());
}
$batch_list = array('' => '');
while ($row =& $res->fetchRow()) 
{
  $smarty->append( 'contents', $row );
  $who = $row[1];
  $mol_type = $row[4];
  $uploaded = $row[7];
  $batchinfo = $row[6];
}

$smarty->assign( 'who', $who );
$smarty->assign( 'mol_type', $mol_type );
$smarty->assign( 'uploaded', $uploaded );
$smarty->assign( 'username', $username );
$smarty->assign( 'batchinfo',$batchinfo );
$smarty->assign( 'batchwikilink',$batchwikilink );
$smarty->assign( 'wikilink' ,$settings['root']['config']['wikilink']);

$db->disconnect();

/* Manual */
// http://www.phppro.jp/phpmanual/pear/package.structures.structures-datagrid.intro-and-features.html

/* Database and DataObject setup */
//$config = parse_ini_file('global_config.ini',TRUE);
//foreach($config as $class=>$values) {
//    $options = &PEAR::getStaticProperty($class,'options');
//    $options = $values;
//}

/* Instantiate */
$num_rows = $settings['root']['config']['ViewBatchDetailTable'];
$datagrid =& new Structures_DataGrid($num_rows); /* $num_rows rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);
$sql = "SELECT prediction_list.batch_id, concat('view_prediction.php?pred_id=',`pred_id`) as pred_link, concat('view_model_detail.php?model_id=',`model_id`) as model_link, `pred_id`, `model_id`, `classes`, `data_type`, `class_tag`, `class_scheme` FROM `prediction_list` JOIN `class_models` USING (model_id) WHERE prediction_list.batch_id = ".$batch_id;

/* Search Pattern */
$check = 0;
if($_GET['pred_id'] != "")
{
  $pred_id = antiInjectie($_GET['pred_id']);
  $sql = $sql." AND pred_id =".$pred_id;
  $search = "Prediction ID : ".$pred_id."<br />";
  $check = 1;
}

if($_GET['model_id'] != "")
{
  $model_id = antiInjectie($_GET['model_id']);
  $sql = $sql." AND model_id =".$model_id;
  $search = "Model ID : ".$model_id."<br />";
  $check = 1;
}

if($check == 1)
{
  $smarty->assign( 'search', "It is currently showing <br/>".$search );
}
if($check == 0)
{
  $smarty->assign( 'search', "Search prediction of your interest!!" );
}

$p->addBodyContent($smarty->fetch('templates/view_batch_detail.tpl.php'));

$first = 1;
$test = $datagrid->bind($sql,$dboptions);

if (PEAR::isError($test))
{
  echo $test->getMessage();
}

//print_r($datagrid);  
$datagrid->generateColumns(); //important!!
$position = 'first';
$datagrid->addColumn(
        new Structures_DataGrid_Column('pred_id','pred_id','pred_id', null, null, 'printLink1(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('model_id','model_id','model_id', null, null, 'printLink2(type=id)')
);


// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('pred_link');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('model_link');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('pred_id');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('model_id');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('batch_id');
// And we drop that column:
$datagrid->removeColumn($column);

/* Get a reference to the Renderer object */
/* Get a reference to the Renderer object */
$renderer =& $datagrid->getRenderer();

//print_r($renderer);  

/* For the <table> element : */
$renderer->setTableAttribute("class", "simpletype");

/* For every odd <tr> elements */
$renderer->setTableOddRowAttributes(array ("class" => "odd"));


/* Get and output HTML links */
$pagingHtml = $renderer->getPaging();
$p->addBodyContent("<p class=\"paging\">Pages : $pagingHtml</p><br />");

/* Output */
$p->addBodyContent($datagrid->getOutput());

/* Get and output HTML links */
$pagingHtml = $renderer->getPaging();
$p->addBodyContent("<br /><p class=\"paging\">Pages : $pagingHtml</p>");

/* Functions */
function printLink1($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['pred_link'] . '">' . $record['pred_id'] . '</a>';
}

function printLink2($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['model_link'] . '">' . $record['model_id'] . '</a>';
}
////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->addScript(("../lisa/libjs/google_webtracker_part1_jstag.js"));
$p->addScript(("../lisa/libjs/google_webtracker_part2_jstag.js"));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
