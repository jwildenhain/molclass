<?php

/**
 * R graph <-> PHP handle
 *
 * @filesource
 * @author Jan Wildenhain
 * @version 1.02 update 8th August 2013 
 * @copyright 2007  
 * @package R graph to PHP
 */
# ini_set('display_errors',   1);

require_once "PEAR.php";
require_once('Config.php');

//error_reporting(E_ALL);
//ini_set('display_errors', 1);
//PEAR::setErrorHandling(PEAR_ERROR_DIE); 



// lead configuration file website
$conf = new Config();
$root =& $conf->parseConfig('molclass.conf.xml', 'XML');
if (PEAR::isError($root)) {
    die('Error while reading configuration: ' . $root->getMessage());
}
$settings = $root->toArray();

# write variable passing from global_config.ini
    if (count($settings['root']['config']['R_parameters'])) {
           foreach ($settings['root']['config']['R_parameters'] as $key => $val) {
                    putenv("$key=$val");
                 #   print $key."=".$val;
           }
    }
/*
R_executable
R_CMD_parameters
R_source_folder
R_data_cache
R_allow_svg
R_png_convert
R_png_density
R_png_resize
*/
    
// in molclass.conf.xml
    $R_cache = $settings['root']['config']['R_parameters']['R_data_cache'];
    $R_path = $settings['root']['config']['R_parameters']['R_executable'];
    $R_par = $settings['root']['config']['R_parameters']['R_CMD_parameters'];
    $R_sf = $settings['root']['config']['R_parameters']['R_source_folder'];
    $R_PNG_convert = $settings['root']['config']['R_parameters']['R_png_convert']; 
    $R_PNG_convert_parameters = "-density ".$settings['root']['config']['R_parameters']['R_png_density']." -resize ".$settings['root']['config']['R_parameters']['R_png_resize'] ;
    
if (!isset($_GET['base'])) {
	$_GET['base'] = 'hist';
}
if (!isset($_GET['type'])) {
	$_GET['type'] = 'Model'; # model or prediction
}
if (!isset($_GET['view'])) {
	$_GET['view'] = 'density'; # density or count
}
if (!isset($_GET['id'])) {
	$_GET['id'] = 1; # density or count
}

    $R_script = array ( 'hist' => 'MolClassDistribution.R' );
   
    $FILENAME = $_GET['base']."_".$_GET['type']."_".$_GET['view']."_".$_GET['id'];
    $JOBTITLE = $_GET['type']." ".$_GET['id'];
    putenv("R_PLOT_TITLE=".$JOBTITLE); // name plot (final!)
    putenv("R_FILENAME=".$FILENAME); // make file name cachable 
    putenv("R_P_TYPE=".$_GET['type']); // set model or pred data processing
    putenv("R_P_VIEW=".$_GET['view']); // density or count pdf output
    putenv("R_P_ID=".$_GET['id']);  // model or pred id from the database
    putenv("R_DATA_CACHE=".$R_cache); // not used here but good to have
    //set to prevent unit test    
    putenv("R_UNIQUE_JOB_ID="."random_id"); // not used here but good to have 

             
    ###############
    # R execution #
    ###############
    system("$R_path < ".$R_sf.$R_script[$_GET['base']]." $R_par "." 1>> ./log/R_exec.log"." 2>> ./log/R_error.log"); 
    
    ##############################
    # plot output PNG PDF or SVG #
    ##############################
    if ( isset($_GET['PNG']) ) 
         { 
           showPNG($R_PNG_convert,$R_PNG_convert_parameters,$FILENAME,$R_cache); 
         } 
    else if ( isset($_GET['SVG']) ) {
           $handle = fopen($R_cache.$FILENAME.".svg", "r");
           $contents = fread($handle, filesize($R_cache.$FILENAME.".svg"));
           header("Content-type: image/svg+xml");
           print($contents);
    
    }     
    else {
           $handle = fopen($R_cache.$FILENAME.".pdf", "r");
           $contents = fread($handle, filesize($R_cache.$FILENAME.".pdf"));
           header('Content-type: application/pdf');
           header('Content-Disposition: attachment; filename="'.$FILENAME.'.pdf"');
           print($contents);
         }
#}

function showPNG($R_PNG_convert,$R_PNG_convert_parameters,$fn,$dir) {

           // echo("$R_PNG_convert $R_PNG_convert_parameters $dir$fn.pdf $dir$fn.png");
           system("$R_PNG_convert $R_PNG_convert_parameters $dir$fn.pdf $dir$fn.png");


           $handle = fopen($dir.$fn.".png", "rb");
           #while(!feof($handle))
           #{
           #      $data .= fread($handle, 1024);
           #}
           #fclose($handle);
           $contents = fread($handle, filesize($dir.$fn.".png"));
           header("Content-type: image/png\n");
           header("Content-Transfer-Encoding: binary\n");
           header("Content-length: " . strlen($contents) . "\n");
           header("Content-name: " . $fn . ".png\n");
           print($contents);

}


?>
