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

$p->setBodyAttributes( array("onLoad"=>"readJMECookie()","onUnload"=>"saveJMECookie()"));
//


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

include_once("moldbconf.php");


$smarty->assign( 'title', 'Structure Search' );
$smarty->assign( 'phpself', "structure_search.php" );


// set a couple of variables for moldb5r
//require_once("functions.php");
//require_once("rxnfunct.php");

$enable_svg     = "y";   # first choice (overridden by settings in moldb5conf.php)
$enable_bitmaps = "y";   # second choice (overridden by settings in moldb5conf.php)
$enable_jme     = "y";   # structure editor; fallback for 2D display

/**
 * set to a value between 0.0 and 1.0 for the relative weight
 * of functional similarity in a similarity search
 */
$fsim = 0.5; 
$ssim_wt     = 1 - $fsim;
$fsim_wt     = $fsim;


$db_id = 1;

if($_GET['molerr']==1)
{
  $p->addBodycontent('Invalid molecule: Query structure must contain at least 3 atoms.<br />');
}
elseif($_GET['quererr']==1)
{
  $cands = $_GET['cand'];
  $p->addBodycontent('Too many candidate structures ('.$cands.')!<br />');
  $p->addBodycontent('Please enter a more specific query.<br />');
}
elseif($_GET['emperr']==1)
{
  $cands = $_GET['cand'];
  $p->addBodycontent('No matches found!<br />');
}
elseif($_GET['numerr']==1)
{
  $maxhits = $_GET['maxhits'];
  $p->addBodycontent('Too many hits! Please refine your query molecule.<br />');

}

$defaultmode  = "1";                # search options
$strict = "n";
$stereo = "n";
$maxhits = $settings['root']['config']['StructureSearchMaxHits'];                // maximum number of hits we want to allow
$maxcand = $settings['root']['config']['StructureSearchMaxCandidates'];               // maximum number of candidate structures we want to allow


$textsearch = $_POST['structuretextsearch'];
$smiles  = $_POST['smiles'];
$jme     = $_POST['jme'];
$mol     = $_POST['mol'];
$rinfo   = $_POST['rinfo'];
$rmode   = $_POST['mode'];
$strict  = $_POST['strict'];
$stereo  = $_POST['stereo'];

$fsim = $_POST["fsim"];
if (isset($fsim) && is_numeric($fsim)) {
  if (($fsim >= 0) && ($fsim <= 1)) {
    $fsim_wt = $fsim;
    $ssim_wt = 1 - $fsim_wt;   // the sum must be 1  
  }
}
$fsim10  = intval(10*$fsim_wt);
$fsim100 = intval(100*$fsim_wt);

$STS     = $_POST['structuretextsearch'];

$usebfp  = 'y';
$usehfp  = 'y';

$mode = $defaultmode;
if (!isset($rmode)) { $mode   = $defaultmode; }       # 1 = exact, 2 = substructure, 3 = similarity
if ($rmode == 1) { $mode = 1; }
if ($rmode == 2) { $mode = 2; }
if ($rmode == 3) { $mode = 3; }

if ($mode == 1) {
  $exact = "y";
}

if ($exact == 'y') {
  $usebfp  = 'n';
  $usehfp  = 'n';
}

  $options = '';
  if ($strict == 'y') 
  {
    $options = 'ais'; // 'a' for charges, 'i' for isotopes (checkmol v0.3p)
  }
  if ($exact == 'y') 
  {
    $options = $options . 'x';
  }
  if ($stereo == 'y') 
  {
    $options = $options . 'gG';
  }

  if (strlen($options) > 0) {
    $options = '-' . $options;
  }


// remove CR if present (IE, Mozilla et al.) and add it again (for Opera)
$mol = str_replace("\r\n","\n",$mol);
$mol = str_replace("\n","\r\n",$mol);

//$safemol = escapeshellcmd($mol);
$safemol = str_replace(";"," ",$mol);


if ($mol !='' && $STS == '') 
{
  $time_start = getmicrotime();

if ($mode < 3) {
  include("incss.php");
} else {
  include("incsim.php");
}


} // string or structure query

////////////////////////////////////////////////////////////////////////////////////

function getmicrotime()
{
  list($usec, $sec) = explode(" ", microtime());
  return ((float)$usec + (float)$sec);
}


function getMolName($id) 
{
  global $moldatatable;
  $result2 = mysql_query("SELECT mol_name FROM $moldatatable WHERE mol_id = $id") or die("Query failed! getMolName");
  while ($line2 = mysql_fetch_array($result2, MYSQL_ASSOC)) 
  {
    $txt = $line2["mol_name"];
  }
  mysql_free_result($result2);
  return $txt;
}

////////////////////////////////////////////////////////////////////////////////////

/* add textfield to search for Name, InChiKey and Smiles */


  if(isset($_POST['structuretextsearch']) && strlen($STS))
  {
      
        //$smiles  = 'ccc';
        // query
	require_once 'DB.php';
	$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
	if (PEAR::isError($db))
	{
		  die($db->getMessage());
	}

	// Fetch Molecule id 
	$sql = "SELECT mol_id FROM moldb_moldata JOIN inchi_key USING (mol_id) WHERE inchi_key like '".$STS."' or smiles like '".$STS."' or inchi like '".$STS."' or mol_name like '".$STS."'";
        //print_r($sql);
	$res =& $db->query($sql);
	if (PEAR::isError($res))
	{
		  die($res->getMessage());
	}

         $mol_id_set = array();
	while ($row =& $res->fetchRow()) 
	{
                  array_push($mol_id_set, $row[0]);
	}
	// pass on to hit_molecules
        //print_r($mol_id_set);

        if (count($mol_id_set) > 0 && count($mol_id_set) <= $settings['root']['config']['StructureSearchMaxHits']) 
        {
		$_SESSION['arraytable'] = $mol_id_set;
		header("location:hit_molecules.php");
      
  	} else 
        {
		 header("location:structure_search.php?emperr=1");
        }
  }



$p->addBodyContent($smarty->fetch('templates/structure_search.tpl.php'));

////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->addScript("jsme/jsme.nocache.js");
$p->addScript(("../lisa/libjs/google_webtracker_part1_jstag.js"));
$p->addScript(("../lisa/libjs/google_webtracker_part2_jstag.js"));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
