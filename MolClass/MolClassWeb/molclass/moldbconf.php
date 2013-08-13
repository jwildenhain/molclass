<?php
//This file parses the xml config file and converts it to php variables
//If a new variable is added to the xml file it will have to be added here as well

$filename = "molclass.conf.xml";

$data = implode("", file($filename));
$parser = xml_parser_create();
xml_parser_set_option($parser, XML_OPTION_CASE_FOLDING, 0);
xml_parser_set_option($parser, XML_OPTION_SKIP_WHITE, 1);
xml_parse_into_struct($parser, $data, $values, $tags);
xml_parser_free($parser);

$database = $values[ $tags['database'][0] ]['value'];
$hostname = $values[ $tags['hostname'][0] ]['value'];
$rw_user = $values[ $tags['rw_user'][0] ]['value'];
$rw_password = $values[ $tags['rw_password'][0] ]['value'];
$ro_user = $values[ $tags['ro_user'][0] ]['value'];
$ro_password = $values[ $tags['ro_password'][0] ]['value'];


$molstructable = $values[ $tags['molstructable'][0] ]['value'];
$moldatatable = $values[ $tags['moldatatable'][0] ]['value'];
$metatable = $values[ $tags['metatable'][0] ]['value'];
$pic2dtable = $values[ $tags['pic2dtable'][0] ]['value'];
$molstattable = $values[ $tags['molstattable'][0] ]['value'];
$molfgtable = $values[ $tags['molfgtable'][0] ]['value'];
$molfgbtable = $values[ $tags['molfgbtable'][0] ]['value'];
$molbfptable = $values[ $tags['molbfptable'][0] ]['value'];
$molcfptable = $values[ $tags['molcfptable'][0] ]['value'];
$fpdeftable = $values[ $tags['fpdeftable'][0] ]['value'];
$molhfptable = $values[ $tags['molhfptable'][0] ]['value'];
$strucinfotable =$values[ $tags['strucinfotable'][0] ]['value'];
$plateinfotable =$values[ $tags['plateinfotable'][0] ]['value'];
$cdkdesctable = $values[ $tags['cdkdesctable'][0] ]['value'];
$batchlisttable =$values[ $tags['batchlisttable'][0] ]['value'];
$batchmoltable = $values[ $tags['batchmoltable'][0] ]['value'];
$fingerprinttable = $values[ $tags['fingerprinttable'][0] ]['value'];
$modeltable = $values[ $tags['modeltable'][0] ]['value'];
$classschemetable = $values[ $tags['classschemetable'][0] ]['value'];
$predtable = $values[ $tags['predtable'][0] ]['value'];
$datatypetable = $values[ $tags['datatypetable'][0] ]['value'];
$predmoltable = $values[ $tags['predmoltable'][0] ]['value'];
$timeouttable = $values[ $tags['timeouttable'][0] ]['value'];
$smilestable = $values[ $tags['smilestable'][0] ]['value'];

$bitmapURLdir = $values[ $tags['bitmapURLdir'][0] ]['value'];
$digits = $values[ $tags['digits'][0] ]['value'];
$subdirdigits = $values[ $tags['subdirdigits'][0] ]['value'];
$sitename = $values[ $tags['sitename'][0] ]['value'];
$CHECKMOL = $values[ $tags['CHECKMOL'][0] ]['value'];
$MATCHMOL = $values[ $tags['MATCHMOL'][0] ]['value'];

$charset = $values[ $tags['charset'][0] ]['value'];
$memsuffix = $values[ $tags['memsuffix'][0] ]['value'];
$multiselect = $values[ $tags['multiselect'][0] ]['value'];
$default_db = $values[ $tags['default_db'][0] ]['value'];
$fpdict_mode = $values[ $tags['fpdict_mode'][0] ]['value'];
$scratchdir = $values[ $tags['scratchdir'][0] ]['value'];
$enablereactions = $values[ $tags['enablereactions'][0] ]['value'];
$enable_download = $values[ $tags['enable_download'][0] ]['value']; 
$download_limit  = $values[ $tags['download_limit'][0] ]['value'];
$enable_svg     = $values[ $tags['enable_svg'][0] ]['value'];
$enable_bitmaps = $values[ $tags['enable_bitmaps'][0] ]['value'];
$use_cmmmsrv   = $values[ $tags['use_cmmmsrv'][0] ]['value'];
$cmmmsrv_addr  = $values[ $tags['cmmmsrv_addr'][0] ]['value'];
$cmmmsrv_port = $values[ $tags['cmmmsrv_port'][0] ]['value'];
$tweakmolfiles = $values[ $tags['tweakmolfiles'][0] ]['value'];
if(!isset($values[ $tags['prefix'][0] ]['value'])) {
    $values[ $tags['prefix'][0] ]['value'] = "";
}
$prefix = $values[ $tags['prefix'][0] ]['value'];

$toolsdir = $values[ $tags['toolsdir'][0] ]['value'];
$model_pred_dir = $values[ $tags['model_pred_dir'][0] ]['value'];


?>
