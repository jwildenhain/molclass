<?php

////////////////////////////////////////////////////////////////////////////////////
require_once "PEAR.php";
require_once 'MDB2.php';
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

$css2 = new HTML_CSS();
$css2->parseFile("css/ddimgtooltip.css");

// create a new HTML page
$p = new HTML_Page2();
$p->setTitle("MolClass");
// it can be added as an object
$p->addStyleDeclaration($css, 'text/css');
$p->setMetaData("Author", "Ryusuke Kimura, Nicholoas Fitzgerald & Jan Wildenhain");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass predicts bioactivity of small molecule by means of Machine Learning method with high throughput screening data.");
$p->setMetaData("Keywords", "highthroughput screening, protein, machine learning");

$p->addScript('js/dw_event.js');
$p->addScript('js/dw_viewport.js');
$p->addScript('js/dw_tooltip.js');
$p->addScript('js/jquery-1.6.4.js');
$p->addScript('js/ddimgtooltip.js');
$p->addScript('js/dw_tooltip_aux.js');

$p->addScriptDeclaration('
dw_Tooltip.defaultProps = {
    content_source: \'class_id\'
}

// Write style rule that hides elements with tipContent class for capable browsers 
dw_Tooltip.writeStyleRule();

');

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

$pred_id = antiInjectie($_GET['pred_id']);

$smarty->assign( 'title', 'Prediction ID : '.$pred_id .' - <a href="display.php?PNG&type=Prediction&id='.$pred_id.'"> show class distribution</a> - download ');

$smarty->assign( 'phpself', "view_prediction.php" );
$smarty->assign( 'pred_id', $pred_id );

//Fetch Test Molecule Information
require_once 'DB.php';
$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db)) 
{
  die($db->getMessage());
}

//Get Prediction
$sql = "SELECT pred_id ,".$predtable.".printout, ".$predtable.".batch_id, model_id, classes, data_type, class_tag, class_scheme FROM ".$predtable." JOIN ".$modeltable." USING (model_id) WHERE pred_id = ".$pred_id;

$res =& $db->query($sql);
if (PEAR::isError($res)) 
{
  die($res->getMessage());
}
$batch_list = array('' => '');
while ($row =& $res->fetchRow()) 
{
  $smarty->append( 'contents', $row );
  $prediction = nl2br($row[1]);
}

$smarty->assign( 'prediction', $prediction );

$db->disconnect();

// render and display form
$p->addBodyContent($smarty->fetch('templates/view_prediction.tpl.php'));

//////////////////////////////////////////////////

/* Instantiate */
$datagrid =& new Structures_DataGrid($settings['root']['config']['ViewPredictionResultsPerPage']); /* 50 rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);

$PREDID = antiInjectie($_GET['pred_id']);
$sql ="SELECT mol_id id, main_class, distribution, lhood, mol_name, inchi_key, concat('molecule_detail.php?mol_id=',`mol_id`) as link, concat('drawllbar.php?llh=',`lhood`) as llhbar FROM ".$settings['root']['config']['database'].".sdftags JOIN ".$settings['root']['config']['database'].".inchi_key USING ( mol_id ) JOIN  ".$settings['root']['config']['database'].".prediction_mols USING ( mol_id ) JOIN ".$settings['root']['config']['database'].".moldb_moldata USING ( mol_id ) where pred_id = $PREDID";

$test = $datagrid->bind($sql,$dboptions);

if (PEAR::isError($test)) 
{
  echo $test->getMessage();
}

$datagrid->generateColumns(); //important!!
$position = 'first';

# lhood is the sorting feature. 
$datagrid->addColumn(
        new Structures_DataGrid_Column('lhood','lhood','lhood', null, null, 'roundlhood(llh=lhood)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('bar','bar','lhood', null, null, 'drawbar(llh=lhood)')
);
# mol_name is the sorting feature
$datagrid->addColumn(
        new Structures_DataGrid_Column('molecule','molecule','mol_name', null, null, 'test_tooltip(type=id)')
);

// Remove columns
// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('inchi_key');
// And we drop that column:
$datagrid->removeColumn($column);
// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('link');
// And we drop that column:
$datagrid->removeColumn($column);

$column =& $datagrid->getColumnByField('lhood');
$datagrid->removeColumn($column);

$column =& $datagrid->getColumnByField('id');
$datagrid->removeColumn($column);

$column =& $datagrid->getColumnByField('mol_name');
$datagrid->removeColumn($column);

$column =& $datagrid->getColumnByField('distribution');
$datagrid->removeColumn($column);

$column =& $datagrid->getColumnByField('llhbar');
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

/* Functions */
function printLink($params, $args = array())
{
    extract($params);
    extract($args);
//    print_r($args);
//    print_r($params);
//    echo($args['type']);
    return '<a href="' . $record['link'] . '">' . $record['mol_name'] . '</a>';
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

function test_tooltip($params, $args = array())
{
    extract($params);
    extract($args);

    $conf = new Config();
    $root =& $conf->parseConfig('molclass.conf.xml', 'XML');
    if (PEAR::isError($root)) {
        die('Error while reading configuration: ' . $root->getMessage());
    }
    $settings = $root->toArray();


   if ($settings['root']['config']['Global_Design_Parameters']['set_structure_table_viewer'] == 1) {
       $buildlink = "<a  href='".$record['link']."' class='showTip e". $record['id']."' > ". $record['mol_name'] ." </a> <p id='e".$record['id']."' class='tipContent' >"."<img src='".$settings['root']['config']['website']."/sdf_chemaxon.php?width=240&amp;height=200&amp;cID=".$record['id']."'/>"."</p>";
   } elseif ($settings['root']['config']['Global_Design_Parameters']['set_structure_table_viewer'] == 2) {
       $buildlink = "<a  href='".$record['link']."' class='showTip e". $record['id']."' > ". $record['mol_name'] ." </a> <p id='e".$record['id']."' class='tipContent' >"."<img src='".$settings['root']['config']['website']."/showsvg.php?id=".$record['id']."'/>"."</p>";
   }
   
   
   return($buildlink);
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
