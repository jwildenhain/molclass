<?php
/*
*
*
*
*/

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
//global $CONF;
global $molclass_email;
global $mmolclass_mol_type;

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
  $sdfid = $_GET['sdfid'];
}

//place to upload and basename
$sdf_target = $settings['root']['config']['php_upload'].$sdf_base;

//parse def file and create form
global $def_target;
global $db_target;
$def_target = $settings['root']['config']['php_upload']."sdf2moldb_".$sdf_base.".def";
$db_target = $settings['root']['config']['php_upload']."db_".$sdf_base.".def";
$def = fopen($def_target, "r+");

//Read created def file and create form based on its contents.
require_once 'HTML/QuickForm.php';
require_once 'HTML/QuickForm/group.php';
require_once 'HTML/QuickForm/element.php';
require_once 'HTML/QuickForm/checkbox.php';
require_once 'HTML/QuickForm/Renderer/Array.php';
require_once 'HTML/QuickForm/Renderer/ArraySmarty.php';

$form = new HTML_QuickForm('defForm', 'post');
$renderer =& new HTML_QuickForm_Renderer_ArraySmarty($smarty);

global $defnames;
$defnames = array();
global $defvals;
$sql_types = array("INT(11)"=>"INT(11)", "DOUBLE"=>"DOUBLE", "VARCHAR(10)"=>"VARCHAR(10)", "VARCHAR(20)"=>"VARCHAR(20)", "VARCHAR(40)"=>"VARCHAR(40)", "VARCHAR(80)"=>"VARCHAR(80)", "VARCHAR(160)"=>"VARCHAR(160)", "VARCHAR(255)"=>"VARCHAR(255)", "TEXT"=>"TEXT");
$sdftag = array();

//read in def file
//construct arrays defname and defvals
//feof - Tests for end-of-file on a file pointer
while( !feof($def) )
{
  //fgets — Gets line from file pointer
  $line = fgets($def);
  if( (substr($line,0,1) == '#') || (substr($line,0,11) == 'sdfilename=' ) 
     || (substr($line,0,6) == 'db_id=' )
     || (substr($line,0,8) == 'db_type=' )
     || (substr($line,0,8) == 'db_name=' )
     || (substr($line,0,15) == 'db_description=' )
     || (substr($line,0,10) == 'db_access=' )
     || (substr($line,0,18) == 'bitmapfile_digits=' )
     || (substr($line,0,24) == 'bitmapfile_subdirdigits=' )
    ) //ignore these lines in the .def file
    continue;
  elseif( !(substr($line,0,1) == '') ) // parse definitions
  {
    list($def_name, $db_name, $def_sql_type) = explode(":",$line,3);
    if(substr_count(  $def_sql_type, " " ) > 0)
      $def_sql_type = substr($def_sql_type, 0, strpos($def_sql_type, " "));
      $defvals["$db_name"] = array('def_name'=>"$def_name", 'db_name'=>"$db_name",'def_sql_type'=>"$def_sql_type");
      array_push($defnames, $db_name);
      array_push($sdftag, $db_name);
      $test = $test."/".$db_name."/".$def_name."/".$def_sql_type;
  }
}
fclose($def);

$smarty->assign('sdftag', $sdftag);

//Show Form
$form->addElement('header', 'hdrTesting', 'SDF Upload');

foreach ($sdftag as $name) {
  $def_name = $defvals["$name"]['def_name'];
  $db_name = $defvals["$name"]['db_name'];
  $def_sql_type = $defvals["$name"]['def_sql_type'];

  $options = $sql_types;
  array_unique($options);

  $include = HTML_QuickForm::createElement('checkbox', 'include', 'include?');
  $include->setChecked(true);

  $defname = HTML_QuickForm::createElement('text', 'defname', 'name:');
  $defname->setValue("$db_name");

  $select = HTML_QuickForm::createElement('select', 'sqltype', 'type:');
  $options = $sql_types;
  array_unique($options);
  $select->loadArray($options);
  $select->setValue("$def_sql_type");

  $elements = array($include, $defname, $select);
  $form->addGroup($elements, "$db_name", "$db_name", '', TRUE);
}

//Select box to choose mol_name for moldb
$form->addElement('select', 'INT_mol_name', "Please select most desriptive (ie. IUPAC name)", $defnames);
$form->addRule('INT_mol_name', 'Please select most desriptive (ie. IUPAC name)', 'required', null, 'client');

//Select box to choose mol_type for moltypetable
$mol_types = array(''=>'', 'learn'=>'Learning', 'test'=>'Test');
$form->addElement('select', 'molclass_mol_type', "Please select Learning or Test for uploading molecule", $mol_types);
$form->addRule('molclass_mol_type', 'Please select Learning or Test for uploading molecule', 'required', null, 'client');
//PubMed ID
$form->addElement('text', 'molclass_pmid', 'PubMed ID', array('size'=>40 ));
//Short Info
$form->addElement('textarea', 'molclass_info', 'Short Information', array('cols'=>29, 'rows'=>5));
//Email
$form->addElement('text', 'molclass_email', 'Email', array('size'=>40 ));
//ID
$smarty->assign('sdfid', $sdfid);
// add submit button
$form->addElement('submit', 'btnSubmit', 'Submit');

$smarty->assign( 'sdftag_alert', '*Please choose SDF tags you want to upload' );

$VerifySDFData = 1;

//Validation
if ($form->validate())
{
  //Get values from submision form
  $values = $form->exportValues();

  //Get an array of uploading SDF tags and data type
  $upload_sdf_tags = array();
  $tags_bug = array();
  foreach ($values as $a )
  {
    if(count($a) == 3)
    {
      array_push($upload_sdf_tags,$a);
    }
    //for dirty bug fix
    if(count($a) >= 2)
    {
      array_push($tags_bug,$a);
    }
  }

  //Check there are no same sdf tag
  $same_name = tags_same_name($upload_sdf_tags);
  
  //Extract SDF tags and those type which exist in DB
  require_once 'DB.php';
  $db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
  if (PEAR::isError($db))
  {
    die($db->getMessage());
  }
  $sql = "SHOW COLUMNS FROM $strucinfotable";
  $res =& $db->query($sql);
  if (PEAR::isError($res))
  {
    die($res->getMessage());
  }
  $db_sdf_tags = array();
  while ($row =& $res->fetchRow())
  {
    array_push($db_sdf_tags, $row);
  }

  //Check whether uploading SDF tags exist. if it does, check whther type of uploading SDF is same as type of uploaded SDF tags.
  $exist_tags = tags_exist($db_sdf_tags,$upload_sdf_tags);
  $num_exist_tags = count($exist_tags);

  //Check whether chosen descriptive sdftag is uploaded. It returns $desc = 1 if chosen descriptive sdftag is uploaded, otherwise, $desc = 0
  //Create Changed name and original name array
  $desc_key = array();
  for ($i = 0; $i <count($defnames); $i++)
  {
     $desc_key["$defnames[$i]"] = $tags_bug[$i]['defname'];
  }
  $original_name = $defnames[$values['INT_mol_name']];
  $descriptive = $desc_key["$original_name"];

  $desc = 0;
  for ($i = 0; $i <count($upload_sdf_tags); $i++)
  {
    $test4 = $test4.$descriptive;
    if(strcmp($descriptive,$upload_sdf_tags[$i]['defname']) == 0)
    {
      $desc = 1;
    }
  }

  if($desc == 1 && $num_exist_tags == 0 && $same_name == 0)
  {
 // header("location:index.php?same_name=".$same_name."&num_exist_tags=".$num_exist_tags."&desc=".$desc);
    global $molclass_email;
    global $molclass_mol_type;
    global $molclass_pmid;
    global $sdfid;
    global $molclass_info;

    $sdfid = $_POST['sdfid'];
    $molclass_pmid = $values['molclass_pmid'];
    $molclass_info = $values['molclass_info'];

    $p->addBodycontent('Thank you, SD File will be uploaded.<br />');
    $p->addBodycontent('And email will be sent to the admin email when upload is complete.<br />');
    $p->addBodycontent('This window may be closed without affecting upload.');

    //This function processed the form data, writing the form contents back into the def file in the write format for
    //moldb. Any changes made to the form will now be reflected in the def file, including mol_name.
    $form->process('process_data', false);

    //$username = $a->getUsername();
    //$username = 'rkim001';

    //Call program 
    /**/
    $cmd = "perl ".$toolsdir."sdf_upload.pl '$sdf_target' '$username' '$molclass_email' '$molclass_mol_type' '$sdfid' '$molclass_pmid' '$molclass_info' > /dev/null";
    /**/
    // for debug purposes:
    /*
    $cmd = "perl ".$toolsdir."sdf_upload.pl '$sdf_target' '$username' '$molclass_email' '$molclass_mol_type' '$sdfid' '$molclass_pmid' '$molclass_info' 1>> ./log/output_PHP_upload_sdf2moldb.log"." 2>> ./log/error_PHP_upload_sdf2moldb.log";
    //print $cmd ."/n";
    //echo $cmd ."/n";
    */

    $ps = run_in_background($cmd);
  }
  else
  {
    if($desc == 0)
    {
      $p->addBodycontent('Please make sure that SDF tag for descriptive name has been checked for upload.<br />');
    }
    if($num_exist_tags >= 1)
    {
      $p->addBodycontent('Check following SDF tags and thier data type. You need to modify tag name or data type for upload.<br />');
      foreach($exist_tags as $a)
      {
        $p->addBodycontent($a[0]."  ".$a[1]."<br />");
      }
    }
    if($same_name == 1)
    {
      $p->addBodycontent('Please make sure that there are no same names for uploading SDF tags.<br />');
    }
    $p->addBodycontent('Please go back to previous page by Back button');
  }
  //goto submitted;
  $VerifySDFData = 0;
}

if ($VerifySDFData) {

//Showing existing colum
$smarty->assign( 'struc_info', 'Current columns in structure info table' );
require_once 'DB.php';
$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
if (PEAR::isError($db))
{
  die($db->getMessage());
}
$sql = "SHOW COLUMNS FROM $strucinfotable";
$res =& $db->query($sql);
if (PEAR::isError($res))
{
  die($res->getMessage());
}
while ($row =& $res->fetchRow())
{
  $smarty->append( 'contents', $row );
}

$db->disconnect();


// link form to renderem
$form->accept($renderer);

// get form as array
$formdata = $renderer->toArray();

// assign template variables from form array
foreach ($formdata as $k => $v) {
    $smarty->assign($k, $v);
}

$smarty->assign('form_data', $formdata);

$p->addBodyContent($smarty->fetch('templates/form_sdf_2moldb.tpl.php'));

}
//
// Template bottom of page
//
$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->display();

//////////////////
// FUNCTIONS   //
////////////////
/*
*
*
*
*/
function tags_same_name($upload_sdf_tags)
{
  $same_name = 0;
  for ($i = 0; $i <count($upload_sdf_tags); $i++)
  {
    for ($j = $i + 1; $j <count($upload_sdf_tags); $j++)
    {
      if($upload_sdf_tags[$i]['defname'] == $upload_sdf_tags[$j]['defname'])
      {
        $same_name = 1;
      }
    }
  }
  return $same_name;
}
/*
*
*
*
*/
function tags_exist($db_sdf_tags,$upload_sdf_tags)
{
  $exist_tags = array();
  for ($i = 0; $i <count($upload_sdf_tags); $i++)
  {
    foreach($db_sdf_tags as $b)
    {
      if($upload_sdf_tags[$i]['defname'] == $b[0])
      {
        if(strtolower($upload_sdf_tags[$i]['sqltype']) != $b[1])
        {
          array_push($exist_tags,$b);
        }
      }
    }
  }
  return $exist_tags;
}

/*
*
*
*
*/
function process_data ($values)
{

  global $defnames;
  global $defvals;
  global $def_target;
  global $db_target;
  global $sdf_base;
  global $none_val;
  global $basename;
  global $molclass_email;
  global $molclass_mol_type;

  $molclass_mol_type = $values['molclass_mol_type'];
  $molclass_email = $values['molclass_email'];

  $def = fopen($def_target, "w");
  fwrite($def, "sdfilename=".$sdf_target."\n");

fwrite($def, "#\n");
fwrite($def, "# database definitions:\n");
fwrite($def, "db_id=1\n");
fwrite($def, "db_type=1\n");
fwrite($def, "db_name='moldb'\n");
fwrite($def, "db_description='This data collection was imported from an SDF file'\n");
fwrite($def, "db_access=1\n");    # 0 = disabled, 1 = read-only, 2 = add/update, 3 = full access
fwrite($def, "bitmapfile_digits=8\n");
fwrite($def, "bitmapfile_subdirdigits=4\n");
fwrite($def, "#\n");

  //write mol_name definitions to file for sdf_check
  $mol_name_ind = $values['INT_mol_name'];
  /*                      
  $mol_name_key = $defnames[$mol_name_ind];
  $mol_name_string_elements = $defvals[$mol_name_key];
  $mol_name_string_elements['db_name']='mol_name';
  $mol_name_string_elements['def_sql_type']='VARCHAR(255) NOT NULL:';
  $mol_name_string = implode(":",$mol_name_string_elements);
  fwrite($def, "sdfilename=./uploads/$sdf_base\n");
  fwrite($def, $mol_name_string);
  fwrite($def, "\n");
  */

  writeStandardDef($def, $mol_name_ind, 'mol_name', 'VARCHAR(255) NOT NULL:');
  /*
  $num_ind = $values['INT_plate_num'];
  $row_ind = $values['INT_plate_row'];
  $col_ind = $values['INT_plate_col'];
  if(!($num_ind==$none_val)){writeStandardDef($def, $num_ind, 'plate_num', 'INT(3) NOT NULL:');}
  if(!($row_ind==$none_val)){writeStandardDef($def, $row_ind, 'plate_row', 'VARCHAR(1) NOT NULL:');}
  if(!($col_ind==$none_val)){writeStandardDef($def, $col_ind, 'plate_col', 'INT(3) NOT NULL:');}
  */
  //write other definitions to file for db
  $db = $def;

  foreach ($values as $key=>$value)
  {
    if($key=='INT_mol_name'||$key=='INT_plate_num'||$key=='INT_plate_row'||$key=='INT_plate_col')
    {

    }
    else
    {

      if($value['defname']=='mol_name'||$value['defname']=='mol_id')
      {
        $prob = $value['defname'];
        echo "<meta http-equiv=\"refresh\" content=\"0; url=sdfcheck.php?sdf=$sdf_base&mol_id_err=$prob\">";
        exit;
      }

      $string_elements = array($defvals[$key]['def_name'],$value['defname'],$value['sqltype']);;
      $string = implode(":",$string_elements);
      $string = $string.":::::";

      if(isset($value['include']))
      {
        fwrite($db, $string);
        fwrite($db, "\n");
      }
    }
  }
  fwrite($db, "\n");
  fclose($db);
}

/*
*
*
*
*/

function writeStandardDef($def, $key, $db_name, $def_sql_type)
{
  global $defnames;
  global $defvals;
  global $def_target;
  global $db_target;
  global $sdf_base;
  global $none_val;

  //write mol_name definitions to file for sdf_check
  $mol_name_key = $defnames[$key];
  $mol_name_string_elements = $defvals[$mol_name_key];
  $mol_name_string_elements['db_name']=$db_name;
  $mol_name_string_elements['def_sql_type']=$def_sql_type;
  $mol_name_string = implode(":",$mol_name_string_elements);

  fwrite($def, $mol_name_string);
  fwrite($def, "\n");
}
/*
* Run in Background
*
*
*/
function run_in_background($Command, $Priority = 0)
{
  if($Priority)
    $PID = shell_exec("nohup nice -n $Priority $Command 2> /dev/null & echo $!");
// for debug purposes only: 
//$PID = shell_exec("nohup nice -n $Priority $Command");
  else
    $PID = shell_exec("nohup $Command 2> /dev/null & echo $!");
// for debug purposes only: 
//    $PID = shell_exec("nohup $Command");
  return($PID);
}

/*
* Run in Background
*
*
*/
function is_process_running($PID)
{
  exec("ps $PID", $ProcessState);
  return(count($ProcessState) >= 2);
}


?>
