<?php 
// showsvg.php      Norbert Haider, University of Vienna, 2013
// part of MolDB5R  last change: 2013-01-04

/**
 * @file showsvg.php
 * @author Norbert Haider
 * 
 * This script sends an SVG image to the browser in the appropriate
 * MIME type for display by a browser that supports SVG, but is not
 * capable of handling inline SVG (older Opera, Safari) With the mode 
 * parameter set to "txt", contents will be sent as plain text.
 */
 

$myname = $_SERVER['PHP_SELF'];
include("moldbconf.php");  // contains $uid and $pw of a proxy user 
                            // with read-only access to the moldb database
require_once("functions.php");

//$configfile = "moldb5conf.php";

//$conf = `cat $configfile`;    # read content of configuration file
//eval($conf);                  # and treat as valid PHP statements

if (config_quickcheck() > 0) { die(); }
set_charset($charset);

$user     = $ro_user;         # from configuration file
$password = $ro_password;

if ($user == "") {
  die("no username specified!\n");
}

$mode = "mol";   // can be "mol" or "txt"

@$item_id  = $_REQUEST['id'];
@$db_id    = 1;
@$mode     = $_REQUEST['mode'];

$link = mysql_pconnect($hostname,"$user", "$password")
  or die("Could not connect to database server!");
mysql_select_db($database)
  or die("Could not select database!");    
mysql_query("SET NAMES $mysql_charset");

if ((!isset($db_id)) || (!is_numeric($db_id)) || ($db_id < 1)) {
  $db_id = 1;
}

if (!isset($mode)) {
  $mode = "svg";
}

if ($mode != "txt") {
  $mode = "svg";
}


$qstr01 = "SELECT * FROM $metatable WHERE (db_id = $db_id)";

$result01 = mysql_query($qstr01)
  or die("Query failed (#1)!");    
while($line01   = mysql_fetch_array($result01)) {
  $db_id        = $line01['db_id'];
  $dbtype       = $line01['type'];
  $dbname       = $line01['name'];
  $usemem       = $line01['usemem'];
  //$bitmapdir    = $line01['bitmapdir'];
  //$bitmapURLdir = $line01['bitmapURLdir'];
  $digits       = $line01['digits'];
  $subdirdigits = $line01['subdirdigits'];
}
mysql_free_result($result01);

if (!isset($digits) || (is_numeric($digits) == false)) { $digits = 8; }
if (!isset($subdirdigits) || (is_numeric($subdirdigits) == false)) { $subdirdigits = 0; }
if ($subdirdigits < 0) { $subdirdigits = 0; }
if ($subdirdigits > ($digits - 1)) { $subdirdigits = $digits - 1; }


if ($usemem == 'T') {
  $molstattable  = $molstattable . $memsuffix;
  $molcfptable   = $molcfptable  . $memsuffix;
}

$safe_id = escapeshellcmd($item_id);

if ($dbtype == 1) { $id_name = "mol_id"; }
if ($dbtype == 2) { $id_name = "rxn_id"; }

if ($safe_id !='') { 
  $result2 = mysql_query("SELECT svg FROM $pic2dtable WHERE $id_name = $safe_id") or die("Query failed!");    
  while ($line2 = mysql_fetch_array($result2, MYSQL_ASSOC)) {
    $svg = $line2["svg"];
  }
  mysql_free_result($result2);
  if ($mode == "svg") {
    header("Content-Type: image/svg+xml");
    header("Content-Disposition: filename=${safe_id}.svg");
  } else {
    header("Content-Type: text/plain");
    header("Content-Disposition: filename=${safe_id}.txt");
  }
  print stripFirstLine($svg)."\n";
}

function stripFirstLine($text)
{        
  return substr( $text, strpos($text, "\n")+1 );
}

?>
