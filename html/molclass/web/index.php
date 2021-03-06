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
$p->setTitle("MolClass version 1.62 (release April 2015)");
// it can be added as an object
$p->addStyleDeclaration($css, 'text/css');
$p->setMetaData("Author", "Jan Wildenhain");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass predicts bioactivity of small molecules using machine learning and experimental data.");
$p->setMetaData("Keywords", "cheminformatics, murcko, tanimoto, HTS, prediction, toxcicity, protein, machine learning");

// Add template for Website Header - later on also for the Footer
// include class
// create object
// set template directory
// Load the Savant2 class file and create an instance.
$tpl =  new Savant3();

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

$smarty->assign( 'title', 'MolClass version 1.62 (April 2015 release)' );
$smarty->assign( 'sub_title', 'Rapid Molecule Classification Based on Structure and Activity' );
$smarty->assign( 'news', '<b>Update December 16th 2015</b><br> New models have been added to the service. Please check the <a href="http://sysbiolab.bio.ed.ac.uk/wiki/index.php/Current_Datasets_in_MolClass_version_1.5"> the wiki entry for MolClass v1.5</a> for details. The <a href="http://sysbiolab.bio.ed.ac.uk/molclass_myed"> previous version of MolClass </a> is available with <a href="http://sysbiolab.bio.ed.ac.uk/wiki/index.php/Current_Datasets_in_MolClass_version_1"> the following Models </a>.<br><br> ');

$smarty->assign( 'abstract', '<b>Update November 19th 2013</b><br> The new release has undergone some major improvements including a much faster structure search, new fingerprints, new machine learning algorithms, an 85% similarity match and likelihood score distribution display. For more information please go to Details.<br>
In addition to the publication release we added models to cover the <a href="http://www.plosone.org/article/info%3Adoi%2F10.1371%2Fjournal.pone.0028966">metabolikeness</a> of molecules, <a href="http://www.ncbi.nlm.nih.gov/pubmed/22625864">compound aggregation</a> effects, <a href="http://www.ncbi.nlm.nih.gov/pubmed/20014752">liver toxcicity (DILI</a>) and interference with <a href="http://pubchem.ncbi.nlm.nih.gov/assay/assay.cgi?aid=485275#aDescription">drug pumps</a> as well as a model to predict the interference with <a href="http://pubchem.ncbi.nlm.nih.gov/assay/assay.cgi?aid=1362#aDescription">mitochondrial fusion</a> an evolutionary conserved process.<br> 
Those and future models can help to guide compound selection for follow up screens and library design. Most computer-aided ventures overlook promiscuous binding to off-target proteins that results in side effect of a drug. Those compounds will be visible in the approach we have taken. We hope that our portlet will help to guide scientists in the systems- and chemical biology community. The current dataset contains more than 78000 molecules with predictions for 18 experimental datasets.
<br><br>
MolClass generates computational models from small molecule datasets using structural features identified in hit and non-hit molecules. In contrast to existing experimental resources like PubChem and Chembank, MolClass aims to present the user with a likelihood value for each molecule entry. This creates an activity fingerprint that currently includes models for <a href="http://www.ncbi.nlm.nih.gov/pubmed/19702240">Ames mutagenicity</a>, <a href="http://www.ncbi.nlm.nih.gov/pubmed/16180914"> blood brain barrier penetration</a>, CaCo2 penetration <a href="http://www.ncbi.nlm.nih.gov/pubmed/15446816">(derived from Hou <i>et al</i>.)</a>, stem cell neurosphere proliferation <a href="http://www.ncbi.nlm.nih.gov/pubmed/17417631"> (derived from Diamandis <i>et al</i>.)</a>, Autofluorescence Model <a href="http://chembank.broadinstitute.org/">(derived from ChemBank data)</a>, Flucanozole synergy predictive model <a href="http://www.ncbi.nlm.nih.gov/pubmed/21694716">(derived from Spitzer <i>et al</i>.)</a> and <a href="http://www.ncbi.nlm.nih.gov/pubmed/19702240">a toxcicity benchmark</a>.<br>
In addition we uploaded some example datasets build on experimental data from Pubchem to build a <i>P. falicarum</i> Sensitivity data model <a href="http://www.ncbi.nlm.nih.gov/pubmed/19734910"> (derived from Yuan <i> et al</i>.)</a> and a <a href="http://www.ncbi.nlm.nih.gov/sites/entrez?db=pcassay&term=595"> Hsp90 co-chaperone disrupter screen</a>.
The second source is the NCI funded database ChemBank, here we incorporated from a <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1000423"> Cell Cycle Inhibitor Screen</a>, <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1001644">a Beta Cell Transdifferentiation model</a>, <a href="http://chembank.broadinstitute.org/assays/view-project.htm?id=1000359"><i>Xenopus</i> Actin Polymerization dataset</a> and a Thrombin Acitivity Predictive Model <a href="https://www.ebi.ac.uk/chembldb/target/inspect/CHEMBL204"> (derived from ChEMBL data)</a>.<br>

' );

$p->addBodyContent($smarty->fetch('templates/index.tpl.php'));

////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
//$p->addScript(("../lisa/libjs/google_webtracker_part1_jstag.js"));
//$p->addScript(("../lisa/libjs/google_webtracker_part2_jstag.js"));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
