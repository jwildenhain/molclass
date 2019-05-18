<?php 
// incsim.php       Norbert Haider, University of Vienna, 2009-2011
// part of MolDB5R  last change: 2011-03-31

/**
 * @file incsim.php
 * @author Norbert Haider
 * 
 * This script is included by moldbsss.php (which performs
 * substructure and similarity searches), it is responsible
 * for the similarity part.
 */

$maxhits = 250;            //!< maximum number of hits we want to display
$maxcand = 5000;           //!< maximum number of candidate structures we want to allow
$default_threshold = 0.50; //!< minimum similraity (according to Tanimoto index)
$max_diffsum = 300;        //!< cutoff value for molstat-based preselection

$use_dfp     = 1;          //!< use also dictionary-based fingerprints
$use_funct   = 1;          //!< use also functional group patterns
$fpoptions   = "-F";
$use_diffsum = 1;          // include the value of (max_diffsum - diffsum)/max_diffsum 
                           // as another factor (between 0 and 1) in the similarity index

echo "<h3>Found similar structures:</h3>\n";


function count1bits($number) {
  if (!is_numeric($number)) {
    return 0;
  }
  $n1bits = 0;
  $number = $number + 0;  // dirty trick to force PHP to use 32-bit unsigned integers, see
  // http://at.php.net/manual/en/language.types.integer.php#language.types.integer.casting
  for ($i = 0; $i < 32; $i++) {
    $testnum = $number >> $i;
    if ($testnum & 1) {
      $n1bits++;
    }
  }
  return $n1bits;
}

function count1bits_mysql($numberstring) {   //  just use MySQL to do the bitcount
  $bc = 0;
  $bcqstr   = "SELECT BIT_COUNT($numberstring) AS n1";
  $bcresult = mysql_query($bcqstr)
      or die("Query failed (bit_count)!");    
  while($bcline = mysql_fetch_array($bcresult)) {
    $bc  = $bcline['n1'];
  }
  mysql_free_result($bcresult);
  return $bc;
}

$hit[0][0] = 0;       // mol_id
$hit[0][1] = 0.000;   // s
$hit[0][2] = 0;       // db

function insertHit($id,$s,$db) {
  global $maxhits;
  global $hits;
  global $hit;
  $hits++;
  if ($hits == 1) {
    $hit[0][0] = $id;
    $hit[0][1] = $s;
    $hit[0][2] = $db;
  } else {
    @$s_test = $hit[($hits - 1)][1];
    if ($s <= $s_test) {
      if ($hits <= $maxhits) {
        $hit[$hits][0] = $id;
        $hit[$hits][1] = $s;
        $hit[$hits][2] = $db;
      } else { $hits--; }
    } else {
      $newpos = 0;
      for ($j = 0; $j < $hits; $j++) {
        @$s_test = $hit[$j][1];
        if ($s <= $s_test) { $newpos = $j + 1; }
      }
      for ($k = $hits; $k > $newpos; $k--) {
        @$hit[$k][0] = $hit[($k-1)][0];
        @$hit[$k][1] = $hit[($k-1)][1];
        @$hit[$k][2] = $hit[($k-1)][2];
      }
      $hit[$newpos][0] = $id;
      $hit[$newpos][1] = $s;
      $hit[$newpos][2] = $db;
      if ($hits > $maxhits) {
        $hits--;
      }
    }
  }
}

// remove CR if present (IE, Mozilla et al.) and add it again (for Opera)
$mol = str_replace("\r\n","\n",$mol);
$mol = str_replace("\n","\r\n",$mol);

//$safemol = escapeshellcmd($mol);
$safemol = str_replace(";"," ",$mol);

#$origmolstattable = $molstattable;
#$origmolhfptable  = $molcfptable;
#$origmolbfptable  = $molbfptable;


if ($mol !='') { 
  // check if user specified a particular contribution of functional similarity
  @$fsim = $_POST["fsim"];
  if (isset($fsim) && is_numeric($fsim)) {
    if (($fsim >= 0) && ($fsim <= 1)) {
      $fsim_wt = $fsim;
      $ssim_wt = 1 - $fsim_wt;   // the sum must be 1  
    }
  }
 
  //echo "<table width=\"100%\">\n";
  $time_start = getmicrotime();  

  if ($use_cmmmsrv == 'y') {
    /* create a TCP/IP socket */
    $socket = socket_create(AF_INET, SOCK_STREAM, SOL_TCP);
    if ($socket < 0) {
      //echo "socket_create() failed.\nreason: " . socket_strerror ($socket) . "\n";
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol --!>\n";
      $use_cmmmsrv = "n";
    }
    $result = socket_connect ($socket, $cmmmsrv_addr, $cmmmsrv_port);
    if ($result === FALSE) {
      //echo "socket_connect() failed.<p />\n";
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol --!>\n";
      $use_cmmmsrv = "n";
    }
    }
  if ($use_cmmmsrv == 'y') {
    $a = socket_read($socket, 250, PHP_NORMAL_READ);
    //echo "the socket says: $a\n";
    $pos = strpos($a,"READY");
    if ($pos === false) {
      echo "<!-- could not connect to cmmmsrv - reverting to checkmol/matchmol --!>\n";
      $use_cmmmsrv = "n";
    }
  }

  // for molstat analysis, use either "axHb" or "axxHb" as checkmol options:
  // with "x", only non-zero descriptors are used; with "xx", all descriptors are
  // used (this will produce slightly more accurate queries at the expense of
  // performance); "xx" requires checkmol v0.4d or higher
  
  $options = "axxHb";   // either "axHb" or "axxHb" (see above)
  if ($use_cmmmsrv == 'y') {
    $chkresult = filterthroughcmmm("$safemol", "#### checkmol:$options");  // "xx" requires checkmol v0.4d or higher
  } else {
    $chkresult = filterthroughcmd("$safemol", "$CHECKMOL -$options - ");
  }
  //echo "<pre>$chkresult</pre>";

  if (strlen($chkresult) < 2) {
    echo "no response from checkmol (maybe a server configuration problem?)\n</body></html>\n";
    exit;
  }

  // first part of output: molstat, second part: functional groups, third part: hashed fingerprints
  $myres = explode("\n", $chkresult);
  $chkresult1a  = $myres[0];    // molstat
  $chkresult1b = $myres[1];    // functional groups
  $chkresult2  = $myres[2];    // hashed fingerprints
  
  // strip trailing "newline"
  $chkresult1a = str_replace("\n","",$chkresult1a);
  $len = strlen($chkresult1a);
  // strip trailing semicolon
  if (substr($chkresult1a,($len-1),1) == ";") {
    $chkresult1a = substr($chkresult1a,0,($len-1));
  }  

  // determine number of atoms in query structure,
  // reject queries with less than 3 atoms ==>
  // $chkresult contains as the first 2 entries: "n_atoms:nn;n_bonds:nn;"
  $scpos = strpos($chkresult1a,";");
  $na_str = substr($chkresult1a,8,$scpos-8);
  $na_val = intval($na_str);
  if ( $na_val < 3 ) {
	header("location:structure_search.php?molerr=1");
  }

  $chkresult2 = str_replace("\n","",$chkresult2);

  $myres2 = explode(";", $chkresult2);
  $chkresult3 = $myres2[0];
  $chkresult4 = $myres2[1];
  $hfp = explode(",", $chkresult3);

  $molstat = explode(";",$chkresult1a);
  
  //echo "<pre>\n$chkresult1a\n</pre>\n";

  $chkresult1b = str_replace("\n","",$chkresult1b);

  $myres1b = explode(";", $chkresult1b);
  $chkresult3b = $myres1b[0];
  $a_sum_fg = $myres1b[1];
  $fg = explode(",", $chkresult3b);

  //echo "<pre>\n$chkresult3b\n</pre>\n";

  // MolClass/ChemGRID
  // get database connection for structure searches
  $link = mysql_pconnect($hostname,"$rw_user", "$rw_password") or die("Could not connect to database server!");
  mysql_select_db($database) or die("Could not select database!");

  // if dictionary-based fingerprints are also used:
  if ($use_dfp == 1) {
    // read the fragment dictionaries from the fpdef table and store them in an array
    $fpdefqstr = "SELECT fp_id, fpdef FROM $fpdeftable;";
    $fpdefresult = mysql_query($fpdefqstr)
        or die("Could not get fingerprint definition! ".$fpdefqstr); 
    $i = -1;
    while ($fpdefline = mysql_fetch_array($fpdefresult, MYSQL_ASSOC)) {
      $i++;
      $fpdef[$i] = $fpdefline["fpdef"];
    } 
    mysql_free_result($fpdefresult);
    $fp_count = $i + 1;
    // now create the dictionary-based fingerprints of the query structure
    if ($fp_count > 0) {
      //$safemol = str_replace("\$","\\\$",$safemol);
      $in_dict = 0;
      for ($i = 0; $i < $fp_count; $i++) {
        $fpnum = $i + 1;
        while (strlen($fpnum) < 2) { $fpnum = "0" . $fpnum; }
        $fpsdf = $fpdef[$i];
        $fpsdf = str_replace("\$","\\\$",$fpsdf);
        $fpchunk = $safemol . "\n\\\$\\\$\\\$\\\$\n" . $fpsdf;

        if ($use_cmmmsrv == "y") {    
          // remove extra "\\" for cmmmsrv
          $fpchunk = str_replace("\\\$","\$",$fpchunk);
          $bfp[$i] = filterthroughcmmm("$fpchunk", "#### matchmol:F"); 
        } else {
          $mmcmd = "$MATCHMOL $fpoptions -";
           $bfp[$i] = filterthroughcmd("$fpchunk ", "$mmcmd");
        }
        $bfp[$i] = rtrim($bfp[$i]);
        if ($bfp[$i] != "0") { $in_dict = 1; }
      }  // for ...
      // if we have something in the dictionary, prepare the SQL statements
      $a_sum_dfp = 0;
      for ($i = 0; $i < $fp_count; $i++) {
        $fpnum = $i + 1;
        while (strlen($fpnum) < 2) { $fpnum = "0" . $fpnum; }
        if ($bfp[$i] != "0") {
          $a_sum_dfp = $a_sum_dfp + count1bits_mysql($bfp[$i]);          
        }
      }  // for ...
    }
  }
  if ($use_cmmmsrv == "y") {    
    socket_write($socket,'#### bye');
    socket_close($socket);
  }

  $hits = 0;
  $total_cand_sum = 0;
  $n_structures_sum = 0;
  $nsel = 0;
  $nastr = "";

//  foreach ($dba as $db_id) {
    $dbtype        = 0;   // initialize at beginning of each loop!
//    $dbprefix      = $prefix . "db" . $db_id . "_";
//    $molstructable = $dbprefix . $molstrucsuffix;
//    $moldatatable  = $dbprefix . $moldatasuffix;
//    $molstattable  = $dbprefix . $molstatsuffix;
//    $molcfptable   = $dbprefix . $molcfpsuffix;
//    $molfgbtable   = $dbprefix . $molfgbsuffix;
//    $pic2dtable    = $dbprefix . $pic2dsuffix;
    $qstr01        = "SELECT * FROM $metatable WHERE (db_id = $db_id) AND (type = 1)";
    $result01      = mysql_query($qstr01)
      or die("Query failed (#1)!");    
    while($line01 = mysql_fetch_array($result01)) {
      $db_id        = $line01['db_id'];
      $dbtype       = $line01['type'];
      $dbname       = $line01['name'];
      $usemem       = $line01['usemem'];
      $memstatus    = $line01['memstatus'];
      $digits       = $line01['digits'];
      $subdirdigits = $line01['subdirdigits'];
    }
    mysql_free_result($result01);
  
    // use only SD data collections 
    if ($dbtype == 1) {
      $nsel++;
      if (!isset($digits) || (is_numeric($digits) == false)) { $digits = 8; }
      if (!isset($subdirdigits) || (is_numeric($subdirdigits) == false)) { $subdirdigits = 0; }
      if ($subdirdigits < 0) { $subdirdigits = 0; }
      if ($subdirdigits > ($digits - 1)) { $subdirdigits = $digits - 1; }
      if ($usemem == 'T') {
        if (($memstatus & 1) == 1) { $molstattable  .= $memsuffix; }
        if (($memstatus & 2) == 2) { $molcfptable   .= $memsuffix; }
      }
    
      $threshold    = $default_threshold;
    
      // get total number of structures in the database
      $n_qstr = "SELECT COUNT(mol_id) AS count FROM $molstructable;";
      $n_result = mysql_query($n_qstr)
          or die("Could not get number of entries!"); 
      while ($n_line = mysql_fetch_array($n_result, MYSQL_ASSOC)) {
        $n_structures = $n_line["count"];
      } 
      mysql_free_result($n_result);
    
      $total_cand  = 0;
      $dbhfp       = array(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);

      // assemble molstat query string
      $msqstr = "SELECT mol_id, (";
      $tmpstr = "";
      foreach ($molstat as $msrec) {
        $msrec = str_replace(":0","",$msrec);
        $msrec = str_replace(":","-",$msrec);
        if (strlen($tmpstr) > 0) { $tmpstr .= " + "; }
        //$tmpstr .= "ABS(" . $msrec . ")";
        $tmpstr .= "POW((" . $msrec . "),2)";   // use the squared difference values
      }
      $msqstr .= $tmpstr . ") AS diffsum FROM " . $molstattable;
      $msqstr .= " ORDER BY diffsum LIMIT " . $maxcand;
      //echo("$msqstr<br>\n");
    
      $ms_result = mysql_query($msqstr)
          or die("Could not get candidates!"); 
      $n_cand  = mysql_num_rows($ms_result);     // number of candidate structures

      //=============== begin outer loop
      while ($ms_line = mysql_fetch_array($ms_result, MYSQL_ASSOC)) {
        $cand_mol_id = $ms_line["mol_id"];
        $diffsum = $ms_line["diffsum"];

        if ($diffsum <= $max_diffsum) {
          $c_qstr = "SELECT hfp01, hfp02, hfp03, hfp04,";
          $c_qstr .= " hfp05, hfp06, hfp07, hfp08,";
          $c_qstr .= " hfp09, hfp10, hfp11, hfp12,";
          $c_qstr .= " hfp13, hfp14, hfp15, hfp16,";
          $c_qstr .= " n_h1bits, mol_id";
          $c_qstr .= " FROM $molcfptable WHERE mol_id = " . $cand_mol_id;
          //echo "$c_qstr<br>\n";
          $c_result = mysql_query($c_qstr)
              or die("Could not retrieve data!"); 
  
          // ================ begin inner loop
          while ($c_line = mysql_fetch_array($c_result, MYSQL_ASSOC)) {
            $total_cand++;
            $mol_id = $c_line["mol_id"];
 //  echo "$total_cand: $mol_id<br>\n";
            $dbhfp = array(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
            $dbhfp[0] = $c_line["hfp01"];  $dbhfp[1] = $c_line["hfp02"];
            $dbhfp[2] = $c_line["hfp03"];  $dbhfp[3] = $c_line["hfp04"];
            $dbhfp[4] = $c_line["hfp05"];  $dbhfp[5] = $c_line["hfp06"];
            $dbhfp[6] = $c_line["hfp07"];  $dbhfp[7] = $c_line["hfp08"];
            $dbhfp[8] = $c_line["hfp09"];  $dbhfp[9] = $c_line["hfp10"];
            $dbhfp[10] = $c_line["hfp11"]; $dbhfp[11] = $c_line["hfp12"];
            $dbhfp[12] = $c_line["hfp13"]; $dbhfp[13] = $c_line["hfp14"];
            $dbhfp[14] = $c_line["hfp15"]; $dbhfp[15] = $c_line["hfp16"];
            $dbn1bits = $c_line["n_h1bits"];
            
            $n1 = 0;
            $n2 = 0;
            $n3 = 0;
            $a  = 0;
            $b  = 0;
            $c  = 0;
            $a_sum = 0;
            $b_sum = 0;
            $c_sum = 0;
            $b_sum_fg = 0;
            $c_sum_fg = 0;
        
            for ($ii = 0; $ii < 16; $ii++) {
              $n1 = $hfp[$ii];
              $n2 = $dbhfp[$ii];
              $n1 = $n1 + 0;     // force treatment as integer number
              $n2 = $n2 + 0;
              $n3 = $n1 & $n2;   // bitwise AND
              $n3 = $n3 + 0;
              $a = count1bits($n1);
              $b = count1bits($n2);
              $c = count1bits($n3);
              $a_sum = $a_sum + $a;
              $b_sum = $b_sum + $b;
              $c_sum = $c_sum + $c;
            }
            // if dictionary-based fingerprints are to be included, do it now
            if (($use_dfp == 1) && ($in_dict == 1)) {
              $a_sum = $a_sum +  $a_sum_dfp;
              $bcq1 = "";
              $bcq2 = "";
              for ($iii = 0; $iii < $fp_count; $iii++) {
                $fpnum = $iii + 1;
                while (strlen($fpnum) < 2) { $fpnum = "0" . $fpnum; }
                if (strlen($bcq1) > 0) { $bcq1 .= " + "; }
                if (strlen($bcq2) > 0) { $bcq2 .= " + "; }
                $bcq1 .= "BIT_COUNT(dfp$fpnum & $bfp[$iii])";
                $bcq2 .= "BIT_COUNT(dfp$fpnum)";
              }
              $bcqstr = "SELECT $bcq1 AS n1c, $bcq2 AS n1b FROM $molcfptable WHERE mol_id = $mol_id";
    //    echo "bcqstr: $bcqstr<br>\n";
              $bcresult = mysql_query($bcqstr)
                or die("Could not retrieve bit_count!"); 
              while ($bcline = mysql_fetch_array($bcresult, MYSQL_ASSOC)) {
                $c_dfp = $bcline["n1c"];
                $b_dfp = $bcline["n1b"];
              }
              mysql_free_result($bcresult);
              $c_sum = $c_sum + $c_dfp;  
              $b_sum = $b_sum + $b_dfp;  
            }  // if ...
            // if functional groups are to be included, do it now
            if (($use_funct == 1) && ($a_sum_fg > 0)) {


              //$a_sum = $a_sum +  $a_sum_fg;
              $fgq1 = "";
              $fgq2 = "";
              for ($iii = 0; $iii < 8; $iii++) {
                $fgnum = $iii + 1;
                while (strlen($fgnum) < 2) { $fgnum = "0" . $fgnum; }
                if (strlen($fgq1) > 0) { $fgq1 .= " + "; }
                if (strlen($fgq2) > 0) { $fgq2 .= " + "; }
                $fgq1 .= "BIT_COUNT(fg$fgnum & $fg[$iii])";
                $fgq2 .= "BIT_COUNT(fg$fgnum)";
              }
              $fgqstr = "SELECT $fgq1 AS n1c, $fgq2 AS n1b FROM $molfgbtable WHERE mol_id = $mol_id";
  //      echo "fgqstr: $fgqstr<br>\n";
              $fgresult = mysql_query($fgqstr)
                or die("Could not retrieve bit_count!"); 
              while ($fgline = mysql_fetch_array($fgresult, MYSQL_ASSOC)) {
                $c_fg = $fgline["n1c"];
                $b_fg = $fgline["n1b"];
              }
              mysql_free_result($fgresult);
              $c_sum_fg = $c_sum_fg + $c_fg;  
              $b_sum_fg = $b_sum_fg + $b_fg;  

            }
            // and now we can calculate the Tanimoto score
            $tanimoto_s = 0;
            if (($a_sum + $b_sum - $c_sum) > 0) {
              $tanimoto_s = $c_sum / ($a_sum + $b_sum - $c_sum);
            }
            $tanimoto_f = 0;
            if (($a_sum_fg + $b_sum_fg - $c_sum_fg) > 0) {
              $tanimoto_f = $c_sum_fg / ($a_sum_fg + $b_sum_fg - $c_sum_fg);
            }
            $tanimoto = $ssim_wt * $tanimoto_s + $fsim_wt * $tanimoto_f;
            
            if ($use_diffsum == 1) {
              // multiply the Tanuimoto score with a coefficient derived from the molstat diffsum
              $molstat_coefficient = (($max_diffsum - $diffsum) / $max_diffsum );
              $tanimoto = $tanimoto * $molstat_coefficient;
            }

           // echo "mol_id: $mol_id  tanimoto: $tanimoto cutoff: $threshold diffsum: $diffsum ($molstat_coefficient)<br />\n";
            if ($tanimoto >= $threshold) {
              insertHit($mol_id,$tanimoto,$db_id);
              //echo "hit $hits: $mol_id ($tanimoto = $ssim_wt * $tanimoto_s + $fsim_wt * $tanimoto_f), candidate no. $total_cand<br>\n";
            }
          }    // end inner loop
          mysql_free_result($c_result);
        
        }  // if ($diffsum <= $max_diffsum).....

      }    // end outer loop
      mysql_free_result($ms_result);

      $total_cand_sum = $total_cand_sum + $total_cand;
      $n_structures_sum = $n_structures_sum + $n_structures;
  
    } else { $nastr = "similarity search is not supported for reaction data collections<br>"; }  // end if ($dbtype == 1)
  
  //}  // foreach

  //echo "<small>search finished with $hits hits";
  //if ($hits > 1) {
  //  echo ", sorted by similarity";
  //}
  //echo " (Tanimoto index in parentheses, relative weight of functional similarity = ${fsim100}%)</small><p />\n";


  $hitArray = array();

  if ($hits > 0) {
    $hitlist = "";
    for ($h = 0; $h < $hits; $h++) {
      $hit_id = $hit[$h][0];
      $hit_s  = $hit[$h][1];
      array_push($hitArray, $hit_id); //*******8

     // $hit_s_formatted = "(" . sprintf("%1.4f", $hit_s) . ")";
     // $db_id = $hit[$h][2];
     // $dbprefix      = $prefix . "db" . $db_id . "_";
     // $molstructable = $dbprefix . $molstrucsuffix;
     // $moldatatable  = $dbprefix . $moldatasuffix;
     // $pic2dtable    = $dbprefix . $pic2dsuffix;
     // showHit($hit_id,$hit_s_formatted);
     // if ($h < $download_limit) {
     //   if (strlen($hitlist) > 0) { $hitlist .= ","; }
     //   $hitlist .= $db_id . ":" . $hit_id;
     // }
      //echo "hit $h: $hit_id ($hit_s)<br>\n";
    }
  }

//  echo "</table>\n<hr>\n";
//  if ($nsel == 0) {
//    echo "no structure data collection selected!<br>\n<hr>\n";
//  }
//  echo "$nastr";

  $time_end = getmicrotime();  

//  print "<p><small>number of hits: <b>$hits</b> (out of $total_cand_sum candidate structures)<br />\n";
//  $time = $time_end - $time_start;
//  print "total number of structures in data collection(s): $n_structures_sum <br />\n";
//  printf("time used for query: %2.3f seconds</small></p>\n", $time);
  if(count($hitArray)<1)
  {
    header("location:structure_search.php?emperr=1");
  }
  else
  {
    $_SESSION['arraytable'] = $hitArray;
    header("location:hit_molecules.php");
  }



}                  // if ($mol != '')...
?>
