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

$smarty->assign( 'title', 'Model Creation Wizard' );

$smarty->assign( 'phpself', "model_creation.php" );

/* Check whether upload is complete */



/* Manual */
// http://www.phppro.jp/phpmanual/pear/package.structures.structures-datagrid.intro-and-features.html

/* Database and DataObject setup */
//$config = parse_ini_file('global_config.ini',TRUE);
//foreach($config as $class=>$values) {
//    $options = &PEAR::getStaticProperty($class,'options');
//    $options = $values;
//}

/* Instantiate */
$num_rows = 25;
$datagrid =& new Structures_DataGrid($num_rows); /* $num_rows rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);
$sql = "SELECT concat('view_batch_detail.php?batch_id=',`batch_id`) as batch_link, concat('http://www.ncbi.nlm.nih.gov/pubmed/',`pmid`) as pmid_link, batch_id, info, mol_type, pmid, uploaded FROM ".$batchlisttable." WHERE `mol_type` = 'learn'";

/* Search Pattern */
$check = 0;
$molecule_type = array('Test', 'Learn');
$smarty->assign( 'molecule_type', $molecule_type );

if($_GET['batch_id'] != "")
{
  $batch_id = $_GET['batch_id'];
  $sql = $sql."AND batch_id =".$batch_id;
  $check = 1;
  $search = "Batch ID : ".$batch_id."<br />";
}

if($_GET['who'] != "")
{
  $who = $_GET['who'];
  if($who == "you")
  {
    $relation = " = ";
    $search = $search."User : ".$username."<br />";
  }
  if($who == "other")
  {
    $relation = " <> ";
    $search = $search."User : "."Other User"."<br />";
  }
  $sql = $sql." AND `username`".$relation."'".$username."'";
  $check = 1;
}

if($check == 1)
{
  $smarty->assign( 'search', "It is currently showing <br/>".$search );
}
if($check == 0)
{
  $smarty->assign( 'search', "Create classification model from learning molecule set!!" );
}

$p->addBodyContent($smarty->fetch('templates/form_model_creation_1.tpl.php'));

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
        new Structures_DataGrid_Column('batch_id','batch_id','batch_id', null, null, 'printLink(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('pmid','pmid','pmid', null, null, 'printLink2(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('status','batch_id','batch_id', null, null, 'printLink3(type=id)')
);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('uploaded');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('pmid_link');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('pmid');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('batch_link');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('batch_id');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('mol_type');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('username');
// And we drop that column:
$datagrid->removeColumn($column);

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

function printLink($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['batch_link'] . '">' . $record['batch_id'] . '</a>';
}

function printLink2($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['pmid_link'] . '">' . $record['pmid'] . '</a>';
}

function printLink3($params, $args = array())
{
    extract($params);
    extract($args);

    if($record['uploaded'] >= 1)
    {
    $html = '<form name="model" action="model_creation_2.php" method="post">
  <input type="submit" value="&nbsp;&nbsp;&nbsp;&nbsp;Create Model&nbsp;&nbsp;&nbsp;&nbsp;">
  <input type="hidden" name="batch_id" value="'.$record['batch_id'].'" />
  </form>';
    }
    else
    {
    $html = "Uploading...";
    }
    return $html;
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
