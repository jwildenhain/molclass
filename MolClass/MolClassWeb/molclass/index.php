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

//ini_set('display_errors', '1');

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

////////////////////////////////////////////////////////////////////////////////////

$smarty->assign( 'title', 'MolClass' );
$smarty->assign( 'sub_title', 'Rapid Molecule Classification Based on Structure and Activity' );
$smarty->assign( 'abstract', 'MolClass generates computational models from small molecule datasets using structural features identified in hit and non-hit molecules. In contrast to existing experimental resources like PubChem and Chembank, MolClass aims to present the user with a likelihood value for each molecule entry. This creates an activity fingerprint that currently includes models for <a href="http://www.ncbi.nlm.nih.gov/pubmed/19702240">Ames mutagenicity</a>, <a href="http://www.ncbi.nlm.nih.gov/pubmed/16180914"> blood brain barrier penetration</a>, CaCo2 penetration <a href="http://www.ncbi.nlm.nih.gov/pubmed/15446816">(derived from Hou <i>et al</i>.)</a>, stem cell neurosphere proliferation <a href="http://www.ncbi.nlm.nih.gov/pubmed/17417631"> (derived from Diamandis <i>et al</i>.)</a>, Autofluorescence Model <a href="http://chembank.broadinstitute.org/">(derived from ChemBank data)</a>, Flucanozole synergy predictive model <a href="http://www.ncbi.nlm.nih.gov/pubmed/21694716">(derived from Spitzer <i>et al</i>.)</a> and <a href="http://www.ncbi.nlm.nih.gov/pubmed/19702240">a toxcicity benchmark</a>.<br>
In addition we uploaded some example datasets build on experimental data from Pubchem to build a <i>P. falicarum</i> Sensitivity data model <a href="http://www.ncbi.nlm.nih.gov/pubmed/19734910"> (derived from Yuan <i> et al</i>.)</a> and a <a href="http://www.ncbi.nlm.nih.gov/sites/entrez?db=pcassay&term=595"> Hsp90 co-chaperone disrupter screen</a>.
The second source is the NCI funded database ChemBank, here we incorporated from a <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1000423"> Cell Cycle Inhibitor Screen</a>, <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1001644">a Beta Cell Transdifferentiation model</a>, <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1000359"><i>Xenopus</i> Actin Polymerization dataset</a> and a Thrombin Acitivity Predictive Model <a href="https://www.ebi.ac.uk/chembldb/target/inspect/CHEMBL204"> (derived from ChEMBL data)</a>.<br>
Those and future models can help to guide compound selection for follow up screens and library design. Most computer-aided ventures overlook promiscuous binding to off-target proteins that results in side effect of a drug. Those compounds will be visible in the approach we have taken. We hope that our portlet will help to guide scientists in the systems- and chemical biology community.' );

$p->addBodyContent($smarty->fetch('templates/index.tpl.php'));

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
