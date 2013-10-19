<?php
require_once 'Spreadsheet/Excel/Writer.php';
require_once('Config.php');
require_once "DB/DataObject.php";
require_once "Structures/DataGrid.php";  

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

$datagrid =& new Structures_DataGrid($num_rows); /* $num_rows rows per page */

/* Bind + Pass display options at binding time */
$dboptions = array('dsn' => $settings['root']['config']['DB_DataObject']['dsn']);

if (isset($_GET['mol_id'])) 
{

	$mol_id = $_GET['mol_id'];
	$sql = "SELECT info, model_id, pred_id, main_class, lhood, `data_type`, name, class_tag, class_scheme, distribution FROM prediction_mols JOIN prediction_list USING (pred_id) JOIN ( class_models JOIN batchlist USING (batch_id) ) USING (model_id) WHERE prediction_mols.mol_id = ".$mol_id;

$XLSfilename = 'MolClassMoleculeExportMolID_'.$mol_id.'.xls';

}

if (isset($_GET['pred_id'])) 
{
	$pred_id = $_GET['pred_id'];
	$sql ="SELECT mol_id, mol_name, main_class, distribution, lhood, apol, TopoPSA, LipinskiFailures, XLogP, ALogP, inchi_key.inchi_key, inchi_key.smiles, inchi_key.inchi, sdftags.* FROM ".$settings['root']['config']['database'].".sdftags JOIN ".$settings['root']['config']['database'].".inchi_key USING ( mol_id ) JOIN ".$settings['root']['config']['database'].".prediction_mols USING ( mol_id ) JOIN ".$settings['root']['config']['database'].".moldb_moldata USING ( mol_id ) join ".$settings['root']['config']['database'].".cdk_descriptors using (mol_id) where pred_id = ".$pred_id;
//print_r($sql);
$XLSfilename = 'MolClassPredictionExportPredID_'.$pred_id.'.xls';

}

if (isset($_GET['batch_id'])) 
{
	$pred_id = $_GET['batch_id'];
	$sql ="SELECT mol_id, mol_name, main_class, apol, TopoPSA, LipinskiFailures, XLogP, ALogP, inchi_key.inchi_key, inchi_key.smiles, inchi_key.inchi, sdftags.* FROM ".$settings['root']['config']['database'].".sdftags JOIN ".$settings['root']['config']['database'].".inchi_key USING ( mol_id ) JOIN ".$settings['root']['config']['database'].".prediction_mols USING ( mol_id ) JOIN ".$settings['root']['config']['database'].".moldb_moldata USING ( mol_id ) join ".$settings['root']['config']['database'].".cdk_descriptors using (mol_id) where pred_id = ".$pred_id;
//print_r($sql);
$XLSfilename = 'MolClassPredictionExportPredID_'.$pred_id.'.xls';

}

$test = $datagrid->bind($sql,$dboptions);

if (PEAR::isError($test)) 
{
  echo $test->getMessage();
}

//print_r($datagrid);  
$datagrid->generateColumns(); //important!!
$position = 'first';

// Create a workbook
$workbook = new Spreadsheet_Excel_Writer();

// Specify that spreadsheet must be sent the browser
$workbook->send($XLSfilename);

// Create your format
$format_bold =& $workbook->addFormat();
$format_bold->setBold();

// Fill the workbook, passing the format as an option
$options = array('headerFormat' => &$format_bold);
$datagrid->fill($workbook, $options);


$workbook->close();

?>
