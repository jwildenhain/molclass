<?php
/* Includes */   

require_once "PEAR.php";
//define("DB_DATAOBJECT_NO_OVERLOAD",true); /* This is needed for some buggy versions of PHP4 */
require_once 'HTML/Page2.php';
require_once 'HTML/CSS.php';
require_once('Config.php');
require_once('functions.php');
require_once 'Savant3.php';
// Add Database Table + DataGrid to display content in table
require_once "DB/DataObject.php";
require_once "Structures/DataGrid.php";  

// show all errors
error_reporting(E_ALL & ~E_NOTICE);
#ini_set('display_errors', '5'); 
#error_reporting(E_ALL);
ini_set('display_errorS', 1);
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
$p->setMetaData("Description", "MolClass display of classifier distribution.");
$p->setMetaData("Keywords", "structure activity relationships, SAR, highthroughput screening, machine learning, toxcicity");

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

// set Smarty directories
$smarty->template_dir = './templates';
$smarty->compile_dir  = './templates_c';

require_once 'Smarty.class.php';
$smarty = new Smarty;

if (isset($_GET['SVG'])) {

      $tmpstring = "blah";
      $tpl->assign('q1', http_build_query($_GET));

}
$p->addBodyContent($tpl->fetch('templates/savant_display_top.tpl.php'));



// <A HREF="javascript:history.go(-1)">
//print_r($_GET);
//print_r(http_build_query($_GET));
if (isset($_GET['large_img'])) {
        $p->addBodyContent("<a href=\"javascript:history.go(-1)\"><img class=\"imagecenter\" border=\"0\" src=\"".$_GET['large_img']."\" alt=\"testing testing\" width=\"".$_GET['w']."\"></a>");
} elseif (isset($_GET['SVG'])) {

      $p->addBodyContent("<object data =\""."plot.php"."?".http_build_query($_GET)."\" width = \"670\" name=\"myiFrame\" height = \"550\" type = \"image/svg+xml\" > </object>");
        
} else {
$p->addBodyContent("<table><tr><td>");
        $p->addBodyContent("<img class=\"imagecenter\" align=\"middle\" width = \"330\" src=\""."plot.php"."?view=density&".http_build_query($_GET)."\" >");
$p->addBodyContent("</td><td>");
$p->addBodyContent("<img class=\"imagecenter\" align=\"middle\" width = \"330\" src=\""."plot.php"."?view=count&".http_build_query($_GET)."\" >");
$p->addBodyContent("</td></tr></table>");
}


$p->addBodyContent($tpl->fetch('templates/savant_display_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->addScript(("../lisa/libjs/google_webtracker_part1_jstag.js"));
$p->addScript(("../lisa/libjs/google_webtracker_part2_jstag.js"));
$p->display();
?>
