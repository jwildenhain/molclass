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
$p->setMetaData("Author", "Jan Wildenhain");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass predicts bioactivity using Machine Learning methods from high throughput screening data.");
$p->setMetaData("Keywords", "cheminformatics, highthroughput screening, protein, machine learning");

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

require_once 'HTML/QuickForm.php';
require_once 'HTML/QuickForm/Renderer/Array.php';
require_once 'HTML/QuickForm/Renderer/ArraySmarty.php';

$form = new HTML_QuickForm('search','post', 'model_creation_3.php');
$renderer =& new HTML_QuickForm_Renderer_ArraySmarty($smarty);

$smarty->assign( 'description', 'Select the data type, class scheme, classifier and email address to create the model.');

//Fetch values from database
require_once 'DB.php';
$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db)) 
{
  die($db->getMessage());
}

//Get an array of data type
$sql = "SELECT data_type FROM ".$datatypetable;
$res =& $db->query($sql);
if (PEAR::isError($res)) 
{
  die($res->getMessage());
}
$data_type = array('' => '');
while ($row =& $res->fetchRow()) 
{
  $temp = $row[0];
  $data_type["$temp"] = $temp;
}

//Get an array of class scheme
$sql = "SELECT class_scheme FROM ".$classschemetable;
$res =& $db->query($sql);
if (PEAR::isError($res)) 
{
  die($res->getMessage());
}
$class_schemes = array('' => '');
while ($row =& $res->fetchRow()) 
{
  $temp = $row[0];
  $class_schemes["$temp"] = $temp;
}

//Get an array of classifier
$batch_id = $_POST['batch_id'];
$sql = "SELECT tags FROM ".$batchlisttable." WHERE batch_id = ".$batch_id;
$res =& $db->query($sql);
if (PEAR::isError($res)) 
{
  die($res->getMessage());
}
$classifier = array('' => '');
while ($row =& $res->fetchRow()) 
{
  $tags = $row[0];
}
$tagexplode = explode(" ", $tags);
foreach ($tagexplode as &$value)
{
  $classifier["$value"] = $value;
}

//Database Disconnect
$db->disconnect();

// Variable for creating user form
$form->addElement('header', 'hdrTesting', 'Model Creation Wizard');
$form->addElement('select', 'data_type', 'Data Type', $data_type);
$form->addElement('select', 'class_schemes', 'Class Scheme', $class_schemes);
$form->addElement('select', 'classifier', 'Classifier', $classifier);
$form->addElement('text', 'email', 'Email', array('size'=>30 ));
//$form->addElement('hidden', 'batch_id', $batch_id);
$smarty->assign('batch_id', $batch_id);


// add submit button:
$form->addElement('submit', 'btnSubmit', 'Confirm');

// set input validation rules
$form->addRule('data_type', 'ERROR: Missing Value', 'required');
$form->addRule('class_schemes', 'ERROR: Missing Value', 'required');
$form->addRule('classifier', 'ERROR: Missing Value', 'required');
$form->addRule('email', 'ERROR: Missing Value', 'required');

//Validation
if($form->validate())
{
  $form->freeze();
}

// link form to renderer
$form->accept($renderer);

// get form as array
$formdata = $renderer->toArray();

// assign template variables from form array
foreach ($formdata as $k => $v) {
    $smarty->assign($k, $v);
}

$smarty->assign('form_data', $formdata);


// render and display form
$p->addBodyContent($smarty->fetch('templates/form_model_creation_2.tpl.php'));

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
