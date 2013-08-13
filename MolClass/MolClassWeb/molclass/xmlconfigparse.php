<?php
$filename = "molclass.conf.xml";

$data = implode("", file($filename));
$parser = xml_parser_create();
//xml_parser_set_option($parser, XML_OPTION_CASE_FOLDING, 0);
//xml_parser_set_option($parser, XML_OPTION_SKIP_WHITE, 1);
xml_parse_into_struct($parser, $data, $values, $tags);
xml_parser_free($parser);


//print_r($tags);
//print_r($values);

$ro_user = $values[ $tags['RO_USER'][0] ]['value'];


$database      = $values[ $tags['DATABASE'][0] ]['value'];  
$hostname      = $values[ $tags['HOSTNAME'][0] ]['value']; 
$rw_user       = $values[ $tags['RW_USER'][0] ]['value']; 
$rw_password   = $values[ $tags['RW_PASSWORD'][0] ]['value'];  
$ro_user       = $values[ $tags['RO_USER'][0] ]['value'];   
$ro_password   = $values[ $tags['RO_PASSWORD'][0] ]['value'];     


$molstructable = $values[ $tags['MOLSTRUCTABLE'][0] ]['value'];
$moldatatable  = $values[ $tags['MOLDATATABLE'][0] ]['value'];
$molstattable  = $values[ $tags['MOLSTATTABLE'][0] ]['value'];
$molfgtable    = $values[ $tags['MOLFGTABLE'][0] ]['value'];
$molfgbtable   = $values[ $tags['MOLFGBTABLE'][0] ]['value'];
$molbfptable   = $values[ $tags['MOLBFPTABLE'][0] ]['value'];
$fpdeftable    = $values[ $tags['FPDEFTABLE'][0] ]['value'];
$molhfptable   = $values[ $tags['MOLHFPTABLE'][0] ]['value'];
$strucinfotable =$values[ $tags['STRUCINFOTABLE'][0] ]['value'];
$plateinfotable =$values[ $tags['PLATEINFOTABLE'][0] ]['value'];
$cdkdesctable =  $values[ $tags['CDKDESCTABLE'][0] ]['value'];
$batchlisttable =$values[ $tags['BATCHLISTTABLE'][0] ]['value'];
$batchmoltable = $values[ $tags['BATCHMOLTABLE'][0] ]['value'];
$fingerprinttable = $values[ $tags['FINGERPRINTTABLE'][0] ]['value'];
$modeltable = 	 $values[ $tags['MODELTABLE'][0] ]['value'];
$classschemetable = $values[ $tags['CLASSSCHEMETABLE'][0] ]['value'];
$predtable = $values[ $tags['PREDTABLE'][0] ]['value'];
$datatypetable = $values[ $tags['DATATYPETABLE'][0] ]['value'];
$predmoltable = $values[ $tags['PREDMOLTABLE'][0] ]['value'];


$bitmapURLdir  = $values[ $tags['BITMAPURLDIR'][0] ]['value'];
$digits        = $values[ $tags['DIGITS'][0] ]['value']; 
$subdirdigits  = $values[ $tags['SUBDIRDIGITS'][0] ]['value']; 
$sitename      = $values[ $tags['SITENAME'][0] ]['value']; 
$CHECKMOL      = $values[ $tags['CHECKMOL'][0] ]['value'];
$MATCHMOL      = $values[ $tags['MATCHMOL'][0] ]['value'];

$toolsdir 		= $values[ $tags['TOOLSDIR'][0] ]['value'];
$model_pred_dir		= $values[ $tags['MODEL_PRED_DIR'][0] ]['value'];

?>
