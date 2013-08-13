<?php

require_once "Auth/Auth.php";
function controlLogin()
{
    global $a;
    return $a->getAuth();
}
$params = array(
                "dsn" => $settings['root']['config']['DB_DataObject']['dsn'],
                "table" => "auth",
                "usernamecol" => "r_email",
                "passwordcol" => "r_pw"
          );
$a = new Auth('DB', $params, 'controlLogin()', false);
$a-> setAdvancedSecurity(true);
$a-> setSessionName("MolClass");
$a->start();
if(!$a->getAuth())
{
  header("location:user_login.php");
}

$username = $a->getUsername();

?>
