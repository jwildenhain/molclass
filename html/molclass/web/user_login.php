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

// Display a template using the assigned values.
$p->addBodyContent($tpl->fetch('templates/savant_header.tpl.php'));
$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_top.tpl.php'));

// set Smarty directories
$smarty->template_dir = './templates';
$smarty->compile_dir  = './templates_c';

require_once 'Smarty.class.php';
$smarty = new Smarty;

////////////////////////////////////////////////////////////////////////////////////

require_once "Auth/Auth.php";
require_once 'HTML/QuickForm.php';
require_once 'HTML/QuickForm/Renderer/ArraySmarty.php';

function controlLogin()
{
    global $a;
    return $a->getAuth();
}

$loginForm = new HTML_QuickForm('loginForm','post','','','',true);

$params = array(
                "dsn" => $settings['root']['config']['DB_DataObject']['dsn'],
                "table" => "auth",
                "usernamecol" => "r_email",
                "passwordcol" => "r_pw"
          );
          
 
          

$a = new Auth('DB', $params, 'controlLogin()', false);
$a-> setAdvancedSecurity(true);
$a-> setSessionName("MolClass");


   require_once 'MDB2.php';
 
 $options = array(
    'debug' => 2,
    'result_buffering' => false,
 );
//print_r($params['dsn']);
$mdb2 =& MDB2::factory($params['dsn'], $options);
if (PEAR::isError($mdb2)) {
    die($mdb2->getMessage());
}

$res =& $mdb2->query("SELECT btnSubmit FROM auth where r_email like '".$_POST['username']."'");

// Always check that result is not an error
if (PEAR::isError($res)) {
    die($res->getMessage());
}

//print_r($res);

$row = $res->fetchRow();
// returns 2 if the result set contains at least 2 rows

$test_unique_e = 0;
if ($res->rowCount() == 1) {
 	$test_unique_e = 1;
}  

$test_active_e = 0;
if ($row[0] == 'Active') {
  	$test_active_e = 1;
} 

//print_r($row[0]);

$mdb2->disconnect();      


// Detection, if user is logged in. Otherwise the login form is being displayed. 
 
if(!$test_active_e) {
   $a->logout();
   $a->start();
}  

$a->start();

if($a->checkAuth() && isset($_GET['logout']) && $_GET['logout'] == '1') 
{
   $a->logout();
   $a->start();
}



if($a->checkAuth()) 
{
  $tpl->assign('loginForm_logout', '<a href="index.php?action=login&logout=1">logout</a>');
}

if(!$a->checkAuth())
{
  $loginForm->applyFilter('__ALL__','trim');
  $loginForm->addElement('header', null, 'Login');
  $loginForm->addElement('text','username', 'email address', array('size'=>40,'maxlength'=>200,'class'=>'login_field'));
  $loginForm->addElement('password','password', 'password', array('size'=>20,'maxlength'=>40,'class'=>'login_field'));
  $loginForm->addElement('submit', btn_login, 'To login click here', array('id'=>'login_button'));
  $loginForm->addRule('username', 'Please enter your email address.', 'required');
  $loginForm->addRule('password', 'Please enter your password.', 'required');
  $loginForm->registerRule('controlLogin', 'function', 'controlLogin');

  if ($loginForm->isSubmitted() && $loginForm->validate()) 
  {
    $loginForm->freeze();
  } 
  else if (($loginForm->isSubmitted() && !($loginForm->validate())) && (!($_GET['logout'] == '1')))
  {
    $smarty->assign('login_error', 'Your login attempt was not successful.');
  }

  $smarty->assign( 'newuser', 'Create MolClass Account');
}

$loginFormRenderer =& new HTML_QuickForm_Renderer_ArraySmarty($smarty);

$loginForm->accept($loginFormRenderer);

$smarty->assign('loginForm_data', $loginFormRenderer->toArray());

$p->addBodyContent($smarty->fetch('templates/form_login_smarty.tpl.php'));

////////////////////////////////////////////////////////////////////////////////////

if ($a->getAuth())
{
  require_once 'DB.php';
  $db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);
  if (PEAR::isError($db)) 
  {
    die($db->getMessage());
  }
  $db->setFetchMode(DB_FETCHMODE_ASSOC);
  $res =& $db->getRow("SELECT * FROM auth where r_email like '".$a->getUsername()."'");
  if (PEAR::isError($res)) 
  {
    die($res->getMessage());
  }
  $username = $a->getUsername();
  $a->setSessionName($res['r_uid']);
  $p->addBodyContent("Welcome to MolClass <b>".$res['r_fn']."</b>!<br>\n");
  $p->addBodyContent("<br> As a registered user you can access to uploaded rich high-throughput screening data and make a prediction.<br>");
  $p->addBodyContent("<br> We plan to extend the features and tools for registered users. More registered and active users will encourage feature development.");
  $p->addBodyContent("<br> If you as an user have ideas what we should add or if you want to contribute code or workflows that follow common interest of users we are happy to incorporate those.<br><br>");
  $p->addBodyContent("<b><a href='http://".$settings['root']['config']['url']."/sdf_upload.php'> Start uploading new testing/learning molecules</a>");
  $p->addBodyContent(" | ");
  $p->addBodyContent("<a href='http://".$settings['root']['config']['url']."/model_creation.php'> Make a prediction model</a>");
  $p->addBodyContent(" | ");
  $p->addBodyContent("<a href='http://".$settings['root']['config']['url']."/prediction_list.php'> View prediction list</a>");
  $p->addBodyContent(" | ");
  $p->addBodyContent("<a href='http://".$settings['root']['config']['url']."/user_login.php?logout=1'> Logout</a></b>");
}

////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
