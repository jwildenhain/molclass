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
$p->setMetaData("Author", "Jan Wildenhain");
$p->setMetaData("Content-Type", "text/html; charset=iso-8859-1");
$p->setMetaData("Description", "MolClass contact details and installation instructions. Download a MolClass virtual machine for your laboratory.");
$p->setMetaData("Keywords", "MolClass, contact details, VMware, VBOX, virtual image, software, download");

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

$getWikiLink = $settings['root']['config']['www_server'];


// add google analytics and see how that goes :)
$p->addBodyContent($tpl->fetch('analyticstracking.php'));
// Display a template using the assigned values.
$p->addBodyContent($tpl->fetch('templates/savant_header.tpl.php'));
$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_top.tpl.php'));

// set Smarty directories
$smarty->template_dir = './templates';
$smarty->compile_dir  = './templates_c';

require_once 'Smarty.class.php';
$smarty = new Smarty;

////////////////////////////////////////////////////////////////////////////////////

$smarty->assign( 'addresstitle', 'Details about MolClass: Who to contact, How to cite, MolClass wiki and File download' );
$smarty->assign( 'address', 'Office<br />
Jan Wildenhain<br /> 
School of Biological Sciences<br /> 
Darwin Building<br /> 
Room 303<br /> 
University of Edinburgh Mayfield Road<br />
Edinburgh EH9 3JR<br /> 
Scotland, UK<br/>
<br/>
If you did not get a response to an email please do not hesitate to resend your email. We are very much interested in your feedback and we are sorry for any inconvenience that this might have caused. Please <a href=http://tyerslab.bio.ed.ac.uk/molclass/user.php>register</a> if you would like to be kept informed about new features and improvements.<br>
If you use this software for your work, please cite: Bioinformatics. 2012 Aug 15;28(16):2200-1 Wildenhain J, Fitzgerald N, Tyers M.');

$smarty->assign( 'MolClassWiki', ' 
    With the current version 1.5, the structure search got faster and supports a similarity search. 
    Biologically more relevant Klekota-Roth fingerprints have been added to the learner. The data is preclustered by 
    Murcko-Fragments, enhanced connectivity fingerprints (ECFP) and Klekota-Roth fingerprints. The list of machine learners 
    got extended including Bayesian and neuronal networks and Ensemble learning.
    Further details are described in the <a href="'.$getWikiLink .'wiki/index.php/MolClass">MolClass Wiki</a>.' );


$smarty->assign( 'downloadtitle', '<h4>Download MolClass version 1.5 (last update 8th of October 2013)</h4>
If you would like to give MolClass a try without going through an installation process, please use our virtual 
machine. You can <a href=http://www.vmware.com/products/player/>download VMPlayer</a> for your system and load
the image. If you like to set it up on a server as an ESXI or use it with Oracle Virtual Box or other VM engines 
please<a href=http://www.vmware.com/products/converter/> download VMware converter</a> for this . It is free, but
you need to register with VMware. The image contains Netbeans 7.3 and the source code if you would like to help
to contribute new features. The source code is also available on <a href="https://code.google.com/p/molclass/"> Google code</a>.
Please note that external tools and libraries are not included, please download the installation package for those.<br><br>
</b>When you run MolClass as virtual machine, login with the account Zahir and password M07c7A22.<br><br>');




$smarty->assign( 'downloadfile', 'http://tyerslab.bio.ed.ac.uk/molclass/download/VMfusionMolClass.tar.gz' );
$smarty->assign( 'downloadinfo', '<b>MolClass version 1.5 VMware Fusion 5 image (tar.gz:5GB will use a virtual image of 32GB)');

//$smarty->assign( 'downloadfile', $settings['root']['config']['website'].'/download/molclass_v1.1.tar.gz' );
//$smarty->assign( 'downloadinfo', 'MolClass version 1.1');
$smarty->assign( 'downloadsourcecodefile', 'http://tyerslab.bio.ed.ac.uk/molclass/download/molclass_v1.5.tar.gz' );
$smarty->assign( 'downloadsourcecodeinfo', 'MolClass version 1.5 with installation instructions');
$smarty->assign( 'tutorialfile', $settings['root']['config']['website'].'/download/molclass_tutorial_v1.0.pdf' );
$smarty->assign( 'tutorialinfo', 'MolClass tutorial version 1');

$smarty->assign( 'examplesdf', $settings['root']['config']['website'].'/download/Model_2_PMID_15446816_MolClass_ds_nb.sdf' );
$smarty->assign( 'examplesdfinfo', 'Example SDF file');


$p->addBodyContent($smarty->fetch('templates/contact.tpl.php'));

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
