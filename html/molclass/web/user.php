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

//Post to Activate.php
$_POST_PHP_SCRIPT = "activate.php";

require_once 'HTML/QuickForm.php';
require_once 'HTML/QuickForm/Renderer/Array.php';
require_once 'HTML/QuickForm/Renderer/ArraySmarty.php';

$form = new HTML_QuickForm('search','post',''); // dirty skip of post doc $_POST_PHP_SCRIPT
$renderer =& new HTML_QuickForm_Renderer_ArraySmarty($smarty);

// Variable for creating user form
$form->addElement('header', 'hdrTesting', 'MolClass User Registration');
$form->addElement('select', 'r_title', 'Title', array(
  'Mr' => 'Mr.',
  'Mrs' => 'Mrs.',
  'Miss' => 'Miss.',
  'Dr' => 'Dr.'
));
$form->addElement('text', 'r_fn', 'First name*', array('size'=>20 ));
$form->addElement('text', 'r_ln', 'Last name*', array('size'=>40 ));
$form->addElement('text', 'r_email', 'Email*', array('size'=>40 ));
$form->addElement('text', 'r_as', 'Institution', array('size'=>40 ));
$form->addElement('text', 'r_loc', 'Country', array('size'=>30 ));
$form->addElement('password', 'r_pw', 'Password', array('size'=>20 ));
$form->addElement('password', 'r_pw_conf', 'P. Confirmation', array('size'=>20 ));

// add submit button
$form->addElement('submit', 'btnSubmit', 'Submit');

// set input validation rules
$form->addRule('r_fn','ERROR: Missing Value','required');
$form->addRule('r_ln', 'ERROR: Missing Value', 'required');
$form->addRule('r_email', 'ERROR: Missing Value', 'required');


// validate input 
if ($form->validate()) 
{

    // if valid, freeze the form
    $form->freeze();

    session_start();
    // retrieve submitted data as array
    $data = $form->exportValues();
    $data['r_uid'] = md5(uniqid(session_id().time().$data['r_fn'].$data['r_ln'].$data['r_as'].$data['r_loc']));

    // if Password empty or different create new passwd.
    $pw_email = '';


    $data['r_ip'] = VisitorIP();

    if ((strcmp($data['r_pw'],$data['r_pw_conf']) == 0) && strlen($data['r_pw']) > 0) 
    {
        $pw_email = $data['r_pw'];
        $data['r_pw'] = md5($data['r_pw']);
        $data['r_pw_conf'] = md5($data['r_pw_conf']);
    } 
    else 
    {
        include 'Text/Password.php';
        // create password
        $tp = new Text_Password();
        $secpasswd =  $tp->create(10, 'unpronounceable');
        $data['r_pw'] = md5($secpasswd);
        $data['r_pw_conf'] = md5($secpasswd);
        $pw_email = $secpasswd;
        $data['r_ip'] = VisitorIP();

    }

    require_once 'DB.php';
    $db = DB::connect($settings['root']['config']['DB_DataObject']['dsn']);

    $db->setFetchMode(DB_FETCHMODE_ASSOC);
    $table_name = 'auth';
    print_r($data);
    $res = $db->autoExecute($table_name, $data, DB_AUTOQUERY_INSERT);
    print_r($res);
    if (PEAR::isError($res)) 
    {
        print_r($res);  
	die($res->getMessage());
    }


    include('Mail.php');
    include('Mail/mime.php');

    $headers = array( "From"    => $settings['root']['config']['molclassemail'],
                      "Subject" =>"Registration MolClass"
                    );
    $text = "Thank you for using MolClass. Please click the following link to activate your account.";
    $html = '<html><body>Hello '.$data['r_fn'].',<br>thank you for using MolClass. Please click the following link to activate your account: <a href="http://'.$settings['root']['config']['url']."/".$_POST_PHP_SCRIPT."?key=".$data['r_uid']."&status=active".'"> Activate User</a>.<br><br>Your Login:'.$data['r_email'].'<br>Your Password:'.$pw_email .'<br><br> Sincerely, <br><br> Your MolClass team</body></html>';
    $crlf = "\n";

    $mime = new Mail_mime($crlf);
    $mime->setTXTBody($text);
    $mime->setHTMLBody($html,false);
    $body = $mime->get();
    $hdrs = $mime->headers($headers);

    $mail =& Mail::factory('mail');
    $mail->send($data['r_email'], $hdrs, $body);

    // process input
    header ("Location:".$_POST_PHP_SCRIPT."?submit");
    exit;
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
$p->addBodyContent($smarty->fetch('templates/form_user_registration_smarty.tpl.php'));

////////////////////////////////////////////////////////////////////////////////////

$p->addBodyContent($tpl->fetch('templates/savant_incenter_table_bottom.tpl.php'));
// Display a template using the assigned values.
$tpl->assign('footertablenote',$settings['root']['config']['Global_Design_Parameters']['footer_table_note']);
$p->addBodyContent($tpl->fetch('templates/savant_footer.tpl.php'));
$p->display();

////////////////////////////////////////////////////////////////////////////////////

?>
