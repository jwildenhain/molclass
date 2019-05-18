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
//error_reporting(E_ALL & ~E_NOTICE);
//PEAR::setErrorHandling(PEAR_ERROR_DIE); 

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

if (!isset($_GET['tanimoto'])  && !isset($_GET['murcko'])) {
    $smarty->assign( 'title', 'Molecular Search Results' );
} else {
    if (!isset($_GET['murcko'])) {
    
    $smarty->assign( 'title', 'Similar molecules in MolClass' );
    // sql query to get similar molecules to
    $getmID = antiInjectie($_GET['tanimoto']);
    
/*        require_once 'DB.php';
	$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
	if (PEAR::isError($db))
	{
		  die($db->getMessage());
	}
 */
 
        $mol_id_set = array();
        array_push($mol_id_set, $getmID);
        /*
	// Fetch Molecule id 
	$sql = "SELECT mol_id2 FROM tanimoto WHERE mol_id1 = ".$getmID;
        //print_r($sql);
	$res =& $db->query($sql);
	if (PEAR::isError($res))
	{
		  die($res->getMessage());
	}      
	while ($row =& $res->fetchRow()) 
	{
                  array_push($mol_id_set, $row[0]);
	}      
        */
	// pass on to hit_molecules
        //print_r($mol_id_set);
        $_SESSION['arraytable'] = $mol_id_set;
    } else {
        $smarty->assign( 'title', 'Molecules with common Murcko fragment in MolClass' );
        // sql query to get similar molecules to
        $getmID = antiInjectie($_GET['murcko']);
 
        $mol_id_set = array();
        array_push($mol_id_set, $getmID);
   
        $_SESSION['arraytable'] = $mol_id_set;
    
    }
}
$array = $_SESSION['arraytable']; 
$p->addBodyContent($smarty->fetch('templates/hit_molecules.tpl.php'));

/* Manual */
// http://www.phppro.jp/phpmanual/pear/package.structures.structures-datagrid.intro-and-features.html

/* Instantiate */
$num_rows = $settings['root']['config']['ViewHitMoleculesPerPage'];
$datagrid =& new Structures_DataGrid($num_rows); /* $num_rows rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);

$hit_molecule_set = implode(',',array_values($array));

if (!isset($_GET['tanimoto']) && !isset($_GET['murcko'])) {
     $sql = "SELECT mol_name, concat('http://www.ncbi.nlm.nih.gov/sites/entrez?db=pccompound&term=',`inchi_key`) as linkpubchem, mol_id id, mol_id, MW, nRotB, nHBAcc, nHBDon,  ROUND(XLogP,6) XLogP, LipinskiFailures, concat('molecule_detail.php?mol_id=',`mol_id`) as link FROM cdk_descriptors JOIN inchi_key USING (mol_id) JOIN moldb_moldata USING ( mol_id ) WHERE mol_id IN (".$hit_molecule_set.")";
} else {
     if (!isset($_GET['murcko'])) {
          $sql = "SELECT mol_name, concat('http://www.ncbi.nlm.nih.gov/sites/entrez?db=pccompound&term=',`inchi_key`) as linkpubchem, mol_id id, mol_id, MW, nRotB, nHBAcc, nHBDon,  ROUND(XLogP,6) XLogP, LipinskiFailures, ext, kr, concat('molecule_detail.php?mol_id=',`mol_id`) as link FROM cdk_descriptors JOIN inchi_key USING (mol_id) JOIN moldb_moldata USING ( mol_id ) JOIN tanimoto on mol_id = mol_id2 WHERE  mol_id1 = ".$getmID." group by inchi_key";   
         
     } else {
           require_once 'DB.php';
	$db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
	if (PEAR::isError($db))
	{
		  die($db->getMessage());
	}
         $getmurkoID = 0;         
         $sql = "select murcko_id from murcko_mol where mol_id =".$getmID; 
         //print_r($sql);
   	 $res =& $db->query($sql);
	 if (PEAR::isError($res))
	 {
		  die($res->getMessage());
	 }      
	 while ($row =& $res->fetchRow()) 
	 {
                  $getmurkoID = $row[0];
	 }
         
         // display Murcko-Fragment as image
         // pull out Murcko smile
         
         
         
                 
         $sql = "SELECT mol_name, concat('http://www.ncbi.nlm.nih.gov/sites/entrez?db=pccompound&term=',`inchi_key`) as linkpubchem, mol_id id, mol_id, MW, nRotB, nHBAcc, nHBDon,  ROUND(XLogP,6) XLogP, LipinskiFailures, concat('molecule_detail.php?mol_id=',`mol_id`) as link FROM cdk_descriptors JOIN inchi_key USING (mol_id) JOIN moldb_moldata USING ( mol_id ) JOIN murcko_mol USING ( mol_id ) WHERE  murcko_id = ".$getmurkoID." group by inchi_key";
     }
     
}
/*
$first = 1;
foreach( $array as $mol_id )
{
  if($first == 1)
  {
    $sql = $sql."mol_id = ".$mol_id;
    $first = 0;
  }
  else
  {
    $sql = $sql." OR mol_id = ".$mol_id;
  }
}
*/

$test = $datagrid->bind($sql,$dboptions);

if (PEAR::isError($test)) 
{
  echo $test->getMessage();
}

//print_r($datagrid);  
$datagrid->generateColumns(); //important!!
$position = 'first';
$datagrid->addColumn(
        new Structures_DataGrid_Column('mol_pic','mol_pic','mol_id', null, null, 'test_tooltip(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('descriptors','descriptors','mw', null, null, 'printDesc(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('mol_id','mol_id','mol_id', null, null, 'printLink(type=id)')
);
$datagrid->addColumn(
        new Structures_DataGrid_Column('linkpubchem','linkpubchem','mol_name', null, null, 'printLink2(type=id)')
);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('mol_name');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('mol_id');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('link');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('linkpubchem');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('id');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('mw');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('nrotb');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('nhbacc');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('nhbdon');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('xlogp');
// And we drop that column:
$datagrid->removeColumn($column);

// We want to remove the ID field, so we retrieve a reference to the Column:
$column =& $datagrid->getColumnByField('lipinskifailures');
// And we drop that column:
$datagrid->removeColumn($column);
if (isset($_GET['tanimoto'])) {
    $column =& $datagrid->getColumnByField('ext');
    $datagrid->removeColumn($column);
    $column =& $datagrid->getColumnByField('kr');
    $datagrid->removeColumn($column);
}
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
    return '<a href="' . $record['link'] . '">' . $record['mol_id'] . '</a>';
}

function printLink2($params, $args = array())
{
    extract($params);
    extract($args);
    return '<a href="' . $record['linkpubchem'] . '">' . $record['mol_name'] . '</a>';
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


   $buildlink = "<p id='e".$record['id']."' class='tipContent' >"."<img src='".$settings['root']['config']['website']."/sdf_chemaxon.php?width=240&amp;height=200&amp;cID=".$record['id']."'/>"."</p>";

   return($buildlink);
}

function printDesc($params, $args = array())
{
    extract($params);
    extract($args);
    $descriptors = "MW : " . $record['mw'] . "<br/>";
    $descriptors = $descriptors . "Rotatable Bonds : " . $record['nrotb'] . "<br/>";
    $descriptors = $descriptors . "HBond Acceptors : " . $record['nhbacc'] . "<br/>";
    $descriptors = $descriptors . "HBond Donors : " . $record['nhbdon'] . "<br/>";
    $descriptors = $descriptors . "XLogP : " . $record['xlogp'] . "<br/>";
    $descriptors = $descriptors . "Lipinski Failures: " . $record['lipinskifailures'] . "<br/>";
    if (isset($_GET['tanimoto'])) {
        $descriptors = $descriptors . "ECFP Similarity<br>Tanimoto : " . $record['ext'] . "<br/>";
        $descriptors = $descriptors . "Klekota Roth <br>Tanimoto : " . $record['kr'] . "<br/>";
    }
    return($descriptors);
}
////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
