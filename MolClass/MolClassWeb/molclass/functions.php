<?php

/**
* Additional Functions.
*/



   function VisitorIP()
    {
    if(isset($_SERVER['HTTP_X_FORWARDED_FOR']))
        $TheIp=$_SERVER['HTTP_X_FORWARDED_FOR'];
    else $TheIp=$_SERVER['REMOTE_ADDR'];

    return trim($TheIp);
    }



   function convertNavigationBarArray($navID,$navArray)  {   
        $data = array(); 
        foreach ($navArray[$navID] as $key => $val) {

           $data[] = array(
               'link' => $val,
               'name' => $key
           );
        }
        return $data;
   }

/**
* MolDB structure search functions 
*/

function filterthroughcmmm($input,$commandline) {
  global $socket;
  $input = $commandline . "\n" . $input . "####" . "\n";
  socket_write ($socket, $input, strlen ($input));
  $output = '';
  $a = '';
  while (($a = socket_read($socket, 250, PHP_NORMAL_READ)) && (strpos($a,'####') === false)) {
    if (strpos($a,'####') === false) {
      $output = $output . $a;
    }
  }
  return $output;
}

function filterthroughcmd($input, $commandLine) {
  $pipe = popen("echo \"$input\"|$commandLine" , 'r');
  if (!$pipe) {
    print "pipe failed.";
    return "";
  }
  $output = '';
  while(!feof($pipe)) {
    $output .= fread($pipe, 1024);
  }
  pclose($pipe);
  return $output;
}


function search_mol_exact($db_id,$mol) {
  global $prefix;
  global $molstrucsuffix;
  global $molstatsuffix;
  global $molcfpsuffix;
  global $CHECKMOL;
  global $MATCHMOL;
  global $use_cmmmsrv;
  global $cmmmsrv_addr;
  global $cmmmsrv_port;
  global $ostype;
  global $socket;

  $mmopt = "xsgGaid";  // x = exact, s = strict, g = E/Z, G = R/S, a = charges, i = isotopes, d = radicals
  $dbprefix      = $prefix . "db" . $db_id . "_";
  $molstructable = $dbprefix . $molstrucsuffix;
  $molstattable  = $dbprefix . $molstatsuffix;
  $molcfptable   = $dbprefix . $molcfpsuffix;
  $mmcmd = "$MATCHMOL -${mmopt} -";

  //$use_cmmmsrv = "n";  // for testing only

  if ($use_cmmmsrv == 'y') {
    /* create a TCP/IP socket */
    $socket = socket_create(AF_INET, SOCK_STREAM, SOL_TCP);
    if ($socket < 0) {
      //echo "socket_create() failed.\nreason: " . socket_strerror ($socket) . "\n";
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol -->\n";
      $use_cmmmsrv = "n";
    }
    $result = socket_connect ($socket, $cmmmsrv_addr, $cmmmsrv_port);
    if ($result === FALSE) {
      //echo "socket_connect() failed.<p />\n";
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol -->\n";
      $use_cmmmsrv = "n";
    }
  }
  if ($use_cmmmsrv == 'y') {
    $a = socket_read($socket, 250, PHP_NORMAL_READ);
    //echo "the socket says: $a<br />\n";
    $pos = strpos($a,"READY");
    if ($pos === false) {
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol -->\n";
      $use_cmmmsrv = "n";
    }
  }
 
  $res = array();
  $ndup = 0;
  $safemol = str_replace(";"," ",$mol);

  if ($use_cmmmsrv == 'y') {
    $chkresult = filterthroughcmmm("$safemol", "#### checkmol:axH");   // "a" for charges
  } else {
    $chkresult = filterthroughcmd("$safemol", "$CHECKMOL -axH - ");
  }
  if (strlen($chkresult) < 2) {
    echo "no response from checkmol (maybe a server configuration problem?)\n</body></html>\n";
    exit;
  }

  // first part of output: molstat, second part: hashed fingerprints
  
  $myres = explode("\n", $chkresult);
  $chkresult1 = $myres[0];
  $chkresult2 = $myres[1];
  
  // strip trailing "newline"
  $chkresult1 = str_replace("\n","",$chkresult1);
  $len = strlen($chkresult1);
  // strip trailing semicolon
  if (substr($chkresult1,($len-1),1) == ";") {
    $chkresult1 = substr($chkresult1,0,($len-1));
  }  

  $chkresult2 = str_replace("\n","",$chkresult2);
  if (strpos($chkresult2,";") !== false) {
    $chkresult2 = substr($chkresult2,0,strpos($chkresult2,";"));
  }
  $hfp = explode(",",$chkresult2);

  // now assemble the pre-selection query string
  $ms_qstr = str_replace(";"," AND ",$chkresult1);
  $ms_qstr = str_replace("n_","${molstattable}.n_",$ms_qstr);
  //$op = ">=";
  $op = "=";
  $ms_qstr = str_replace(":",$op,$ms_qstr);
  $hfp_qstr = "";
  for ($h = 0; $h < count($hfp); $h++) {
    $number = $h + 1;
    while (strlen($number) < 2) { $number = "0" . $number; }
    if (strlen($hfp_qstr) > 0) { $hfp_qstr .= "AND "; }
    $hfp_qstr .= "${molcfptable}.hfp$number = $hfp[$h] ";
  }
  $qstr = "SELECT ${molstructable}.mol_id,${molstructable}.struc FROM $molstructable,$molstattable,$molcfptable";
  $qstr .= " WHERE " . $ms_qstr . " AND " . $hfp_qstr;
  $qstr .= " AND (${molstructable}.mol_id = ${molstattable}.mol_id)";
  $qstr .= " AND (${molstructable}.mol_id = ${molcfptable}.mol_id)";

  $bs = 50;
  if ($use_cmmmsrv == "n") { $bs = 8; }
  $sqlbs = 10 * $bs;
  $nqueries = 0;
  do {
    $offset = $nqueries * $sqlbs;
    $offsetstr = " LIMIT ${offset}, $sqlbs";
    $qstrlim = $qstr . $offsetstr;
    $result = mysql_query($qstrlim)
      or die("Query failed! (search_mol_exact #1)");    
    $n_cand  = mysql_num_rows($result);     // number of candidate structures
    if ($n_cand > 0) {
      $qstruct = $safemol . "\n\$\$\$\$\n";;
      $n = 0;
      while ($line = mysql_fetch_array($result, MYSQL_ASSOC)) {
        $mol_id = $line["mol_id"];
        $haystack = $line["struc"];
        $qstruct = $qstruct . $haystack  . "\n\$\$\$\$ #${mol_id}\n";    
        $n ++;
        if (($n == $bs) || ($n == $n_cand)) {
          if ($use_cmmmsrv == "n") {
            $qstruct = str_replace("\$","\\\$",$qstruct);
          }
          if ($use_cmmmsrv == "y") {
            $matchresult = filterthroughcmmm("$qstruct", "#### matchmol:${mmopt}"); 
          } else {
            $matchresult = filterthroughcmd("$qstruct ", "$mmcmd"); 
          }
          $br = explode("\n", $matchresult);
          foreach ($br as $reply) {
           if (strpos($reply,":T") > 0) {
             $reply = chop($reply);
             $hr = explode("#",$reply);
             $h_id = $hr[1];
             if (strlen($h_id) > 0) {
               $res[$ndup] = $h_id;
               $ndup++;
             }
           }
          }  // foreach...
          $qstruct = $safemol . "\n\$\$\$\$\n";
          $n = 0;
        }
      }  // while ...
    }  // if n_cand > 0...    
    $nqueries++;
  } while (($n_cand == $sqlbs) && ($nqueries < 100000));
  if ($use_cmmmsrv == "y") {
    socket_write($socket,'#### bye');
    socket_close($socket);
  }
  return $res;
}


function showHit($id,$s) {      // version for SVG output
  global $enable_svg;
  global $enable_bitmaps;
  global $enable_jme;
  global $bitmapURLdir;
  global $molstructable;
  global $moldatatable;
  global $digits;
  global $subdirdigits;
  global $db_id;
  global $pic2dtable;
  global $codebase;
  global $svg_mode;
  
  $result2 = mysql_query("SELECT mol_name FROM $moldatatable WHERE mol_id = $id")
    or die("Query failed! (showHit)");
  while ($line2 = mysql_fetch_array($result2, MYSQL_ASSOC)) {
    $txt = $line2["mol_name"];
  }
  mysql_free_result($result2);

  echo "<tr>\n<td class=\"highlight\" width=\"10%\">\n";
  print "<a href=\"details.php?mol=${id}&db=${db_id}\" target=\"_blank\">$db_id:$id</a></td>\n";
  echo "<td class=\"highlight\"> <b>$txt</b>";
  if ($s != '') {
    echo " $s";
  }
  echo "</td>\n</tr>\n";
  
  $whatstr = "status";
  if ($svg_mode == 1) { $whatstr = "status, svg"; }
  $svg = "";

  $qstr = "SELECT $whatstr FROM $pic2dtable WHERE mol_id = $id";
  $result2 = mysql_query($qstr)
    or die("Query failed! (pic2d)");
  while ($line2 = mysql_fetch_array($result2, MYSQL_ASSOC)) {
    $status = $line2["status"];
    @$svg    = $line2["svg"];
  }
  mysql_free_result($result2);
  //if (($status != 1) || ($svg_mode > 0)) { $usebmp = false; } else { $usebmp = true; }
  if ($status == 1) { $usebmp = TRUE; } else { $usebmp = FALSE; }

  echo "<tr>\n<td colspan=\"2\">\n";

  $struc_shown = FALSE;

  if ($enable_svg == "y") {  
    if ((strlen($svg) > 0) && ($svg_mode == 1)) {
      print "$svg\n";
      $struc_shown = TRUE;
    } elseif ($svg_mode == 2) {
      echo "<img src=\"showsvg.php?id=$id&db=$db_id\" alt=\"hit structure\">\n";
      $struc_shown = TRUE;
    }
  }

  if (($enable_bitmaps == "y") && ($struc_shown == FALSE)) {  
    if ((isset($bitmapURLdir)) && ($bitmapURLdir != "") && ($usebmp == true)) {
      while (strlen($id) < $digits) { $id = "0" . $id; }
      $subdir = '';
      if ($subdirdigits > 0) { $subdir = substr($id,0,$subdirdigits) . '/'; }
      print "<img src=\"${bitmapURLdir}/${db_id}/${subdir}${id}.png\" alt=\"hit structure\">\n";
      $struc_shown = TRUE;
    } 
  }
  
  if (($enable_jme == "y") && ($struc_shown == FALSE)) {  
    // if no bitmaps are available, we must invoking another instance of JME 
    // in "depict" mode for structure display of each hit
    $qstr = "SELECT struc FROM $molstructable WHERE mol_id = $id";
    $result3 = mysql_query($qstr) or die("Query failed! (struc)");    
    while ($line3 = mysql_fetch_array($result3, MYSQL_ASSOC)) {
      $molstruc = $line3["struc"];
    }
    mysql_free_result($result3);
  
    // JME needs MDL molfiles with the "|" character instead of linebreaks
    $jmehitmol = tr4jme($molstruc);
    echo "<applet code=\"JME.class\" archive=\"JME.jar\" $codebase\n";
    echo "width=\"250\" height=\"120\">";
    echo "<param name=\"options\" value=\"depict\"> \n";
    echo "<param name=\"mol\" value=\"$jmehitmol\">\n";
    echo "</applet>\n";
    $struc_shown = TRUE;
  }
  echo "</td>\n</tr>\n";
}


function get_svgmode() {
  global $debug;
  $dummy = getagent();
  $ua_name = $dummy["name"];
  $ua_ver  = $dummy["version"];
  #echo "more specifically: $ua_name, version $ua_ver<br>\n";
  if ($debug > 0) { debug_output("browser: $ua_name, version $ua_ver"); }
  $svgmode = 0; // default: SVG not supported
  if (($ua_name === "MSIE") && ($ua_ver >= 9)) { $svgmode = 1; }
  if (($ua_name === "Firefox") && ($ua_ver >= 4)) { $svgmode = 1; }
  if (($ua_name === "Chrome") && ($ua_ver >= 7)) { $svgmode = 1; }  
  if (($ua_name === "Opera") && ($ua_ver >= 8)) { $svgmode = 2; }   // ?? inline SVG ab 11.6
  if (($ua_name === "Opera") && ($ua_ver >= 11.6)) { $svgmode = 1; }   // ?? inline SVG ab 11.6
  if (($ua_name === "Safari") && ($ua_ver >= 500)) { $svgmode = 2; }   // ?? inline SVG ab 5.1
  if (($ua_name === "Safari") && ($ua_ver >= 510)) { $svgmode = 1; }   // ?? inline SVG ab 5.1
  // currently, display of scaled external SVGs does not work properly, so disable mode 2
  if ($svgmode == 2) { $svgmode = 0; }
  return($svgmode);
}


function config_quickcheck() {
  global $database;
  global $ro_user;
  global $ro_password;
  $result = 0;
  if ((!isset($database)) || (strlen($database) == 0)) { $result = 1; }
  if ((!isset($ro_user)) || (strlen($ro_user) == 0)) { $result = 1; }
  if ((!isset($ro_password)) || (strlen($ro_password) == 0)) { $result = 1; }
  if ($result > 0) {
    echo "<h3>Attention! Missing, invalid, or unreadable configuration file!</h3>\n";
  }
  return($result);
}

function set_charset($confcs) {
  global $html_charset;
  global $mysql_charset;
  global $mysql_collation;
  
  // set defaults
  $html_charset    = "ISO-8859-1";
  $mysql_charset   = "latin1";
  $mysql_collation = "latin1_swedish_ci";

  // derive from configuration value
  if ($confcs == "latin2") {
    $html_charset    = "ISO-8859-2";
    $mysql_charset   = "latin2";
    $mysql_collation = "latin2_general_ci";
  }
  if ($confcs == "utf8") {
    $html_charset    = "UTF-8";
    $mysql_charset   = "utf8";
    $mysql_collation = "utf8_unicode_ci";
    // quoted from http://forums.mysql.com/read.php?103,187048,188748#msg-188748
    // "So when you need better sorting order - use utf8_unicode_ci,
    // and when you utterly interested in performance - use utf8_general_ci."
  }

  // Cyrillic character sets (thanks to Konstantin Tokarev)
  if ($confcs == "cp1251") {
    $html_charset    = "windows-1251";
    $mysql_charset   = "cp1251";
    $mysql_collation = "cp1251_general_ci";
  }
  if ($confcs == "koi8r") {
    $html_charset    = "KOI8-R";
    $mysql_charset   = "koi8r";
    $mysql_collation = "koi8r_general_ci";
  }
  if ($confcs == "koi8u") {
    $html_charset    = "KOI8-U";
    $mysql_charset   = "koi8u";
    $mysql_collation = "koi8u_general_ci";
  }

  /* Add some more, if desired
   Documentation:
     Valid MySQL charset names: http://dev.mysql.com/doc/refman/5.5/en/charset-charsets.html
     Valid HTML charset names:  http://www.iana.org/assignments/character-sets
  */
}



function exist_db($db_id) {
  global $metatable;
  $numdb = 0;
  if (is_numeric($db_id)) {
    $result1 = mysql_query("SELECT COUNT(db_id) AS numdb FROM $metatable WHERE db_id = $db_id")
      or die("Query failed! (exist_db)");
    $line1 = mysql_fetch_row($result1);
    mysql_free_result($result1);
    $numdb = $line1[0];
  }
  if ($numdb > 0) { $result = TRUE; } else { $result = FALSE; }
  return($result);
}

function check_db($id) {
  global $metatable;
  $db_id = -1;
  if (is_numeric($id)) {
    $result = mysql_query("SELECT db_id, name FROM $metatable WHERE (db_id = $id) AND (access > 0)")
      or die("Query failed! (check_db)");
    while ($line = mysql_fetch_array($result, MYSQL_ASSOC)) {
      $db_id = $line["db_id"];
    }
    mysql_free_result($result);
  }
  if ($db_id == -1) {   // check if there is any data collection at all
    $result = mysql_query("SELECT COUNT(db_id) AS dbcount FROM $metatable")
      or die("Query failed! (check_db)");
    $dbcount = 0;
    while ($line = mysql_fetch_array($result, MYSQL_ASSOC)) {
      $dbcount = $line["dbcount"];
    }
    mysql_free_result($result);
    if ($dbcount == 0) { $db_id = 0; }
  }
  return($db_id);
}

?> 
