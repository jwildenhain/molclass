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

//require_once "login_check.php";

////////////////////////////////////////////////////////////////////////////////////

/* Pear DB */
require_once 'DB.php';
$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db))
{
  die($db->getMessage());
}

$mol_id = '';

if (isset($_GET['compound'])) {

	$mol_id = $_GET['compound'];
	/* Fetch Molecule Name */
	$sql = "SELECT mol_id FROM moldb_moldata JOIN inchi_key USING (mol_id) JOIN sdftags USING (mol_id) WHERE mol_id ='".$mol_id."' or inchi_key like '".$mol_id."' or inchi like '".$mol_id."' or compound_name like '".$mol_id."' or smiles like '".$mol_id."' or mol_name like '".$mol_id."' limit 1";
        //echo $sql;
	$res =& $db->getOne($sql);
	if (PEAR::isError($res))
	{
	  die($res->getMessage());
	}

        $mol_id = $res;
        //echo $mol_id;
}

if (isset($_GET['mol_id'])) {
	$mol_id = $_GET['mol_id'];
}
if ($settings['root']['config']['Global_Design_Parameters']['set_structure_viewer'] == 1) {
    $smarty->assign( 'picture', $settings['root']['config']['website']."/sdf_chemaxon.php?width=240&amp;height=200&amp;cID=".$mol_id );
} elseif ($settings['root']['config']['Global_Design_Parameters']['set_structure_viewer'] == 2) {
    $smarty->assign( 'picture', $settings['root']['config']['website']."/showsvg.php?id=".$mol_id );
}

$smarty->append( 'mol_id', $mol_id );
$smarty->assign( 'title', "Detail of Molecular ID ".$mol_id );
$array = $_SESSION['arraytable'];


/* Fetch Molecule Name */
$sql = "SELECT mol_name, inchi_key, MW, nRotB, nHBAcc, nHBDon, ROUND(XLogP,6) XLogP,LipinskiFailures, apol,bpol,smiles FROM moldb_moldata JOIN inchi_key USING (mol_id) JOIN cdk_descriptors USING (mol_id) WHERE mol_id =".$mol_id;
//echo $sql;
$res =& $db->query($sql);
if (PEAR::isError($res))
{
  die($res->getMessage());
}
$batch_list = array('' => '');
while ($row =& $res->fetchRow())
{
  $smarty->append( 'name', $row[0] );
  $smarty->append( 'inchi_key', $row[1] );
  $smarty->append( 'MW', $row[2] );
  $smarty->append( 'nRotB', $row[3] );
  $smarty->append( 'nHBAcc', $row[4] );
  $smarty->append( 'nHBDon', $row[5] );
  $smarty->append( 'XLogP', $row[6] );
  $smarty->append( 'LipinskiFailures', $row[7] );
  $smarty->append( 'apol', $row[8] );
  $smarty->append( 'bpol', $row[9] );
  $smarty->append( 'smiles', $row[10] );
}

$db->disconnect();

$p->addBodyContent($smarty->fetch('templates/molecule_detail.tpl.php'));


/* allow Model ID subselection */
$MoldelIDSubselection = '';
if (isset($_GET['models'])) {
	$MoldelIDSubselection = ' and model_id in ('.$_GET['models'].')';
}

/* Manual */
// http://www.phppro.jp/phpmanual/pear/package.structures.structures-datagrid.intro-and-features.html

/* Instantiate */
$num_rows = $settings['root']['config']['ViewModelsPerMolecule'];
$datagrid =& new Structures_DataGrid($num_rows); /* $num_rows rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);

$sql = "SELECT info, concat('view_batch_detail.php?batch_id=',CEIL(batchlist.batch_id)) as batch_link, concat('drawllbar.php?llh=',`lhood`) as llhbar, concat('view_model_detail.php?model_id=',`model_id`) as model_link, concat('view_prediction.php?pred_id=',`pred_id`) as pred_link, model_id, pred_id, main_class, lhood FROM prediction_mols JOIN prediction_list USING (pred_id) JOIN ( class_models JOIN batchlist USING (batch_id) ) USING (model_id) WHERE prediction_mols.mol_id = ".$mol_id.$MoldelIDSubselection;
$test = $datagrid->bind($sql,$dboptions);

if (PEAR::isError($test)) 
{
  echo $test->getMessage();
}

//print_r($datagrid);  
$datagrid->generateColumns(); //important!!
$position = 'first';

$datagrid->addColumn(
        new Structures_DataGrid_Column('lhood','lhood','lhood', null, null, 'roundlhood(llh=lhood)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('bar','bar','lhood', null, null, 'drawbar(llh=lhood)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('model_id','model_id','model_id', null, null, 'printLink1(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('pred_id','pred_id','pred_id', null, null, 'printLink2(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('info','info','info', null, null, 'printLink3(type=id)')
);

// Remove columns
$column =& $datagrid->getColumnByField('lhood');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('llhbar');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('batch_link');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('model_link');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('pred_link');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('model_id');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('pred_id');
$datagrid->removeColumn($column);
$column =& $datagrid->getColumnByField('info');
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
$p->addBodyContent("<br/><p class=\"paging\">Pages : $pagingHtml</p><br />");

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
    return '<a href="' . $record['model_link'] . '">' . $record['model_id'] . '</a>';
}

function printLink2($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['pred_link'] . '">' . $record['pred_id'] . '</a>';
}

function printLink3($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['batch_link'] . '">' . $record['info'] . '</a>';
}

function drawBar($params, $args = array())
{
    extract($params);
    extract($args);
    return '<img src="' . $record['llhbar'] . '"/>';
}

function roundlhood($params, $args = array())
{
    extract($params);
    extract($args);
    $round = number_format($record['lhood'],3);
    return $round;
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
