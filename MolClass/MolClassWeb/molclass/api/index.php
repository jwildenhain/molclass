<?php
//ini_set('display_errors',   1);
require_once "PEAR.php";
require_once('Config.php');

// lead configuration file website
$conf = new Config();
$root =& $conf->parseConfig('../molclass.conf.xml', 'XML');
if (PEAR::isError($root)) {
    die('Error while reading configuration: ' . $root->getMessage());
}
$settings = $root->toArray();


require 'Slim/Slim.php';

$app = new Slim();

$app->get('/dataset', 'getDatasets');
$app->get('/dataset/:id',	'getDataset');
$app->get('/dataset/:id/compounds',	'getDatasetCompounds');

$app->get('/compound/:id',	'getCompound');
$app->get('/compound/:id/models',	'getCompoundModels');

$app->get('/model', 'getModels');
$app->get('/model/:id',	'getModel');

$app->run();

function getDatasets() {
	$sql = "SELECT batch_id, info, tags,pmid, mol_type FROM batchlist";
	try {
		$db = getConnection();
		$stmt = $db->query($sql);  
		$datasetlist = $stmt->fetchAll(PDO::FETCH_OBJ);
		$db = null;
		echo '{"datasets": ' . json_encode($datasetlist) . '}';
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getDataset($id) {
	$sql = "SELECT batch_id, info, tags,pmid, mol_type FROM batchlist WHERE batch_id=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$singledataset = $stmt->fetchObject();  
		$db = null;
		echo json_encode($singledataset); 
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getDatasetCompounds($id) {
	$sql = "SELECT mol_id FROM batchlist join batchmols using (batch_id) WHERE batch_id=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$datasetCompounds = $stmt->fetchAll(PDO::FETCH_OBJ);  
		$db = null;
		echo '{"batchcompounds": ' . json_encode($datasetCompounds) . '}';
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getCompound($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT inchi_key.*, sdftags.* FROM sdftags left join inchi_key using (mol_id) WHERE mol_id=:id or inchi_key.smiles=:id or inchi_key.inchi=:id or inchi_key.inchi_key=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$singledataset = $stmt->fetchObject(); # ->fetchAll(PDO::FETCH_OBJ);  
		$db = null;
		echo json_encode($singledataset); 
		#echo '{"compounds": ' . json_encode($singledataset) . '}'; 
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getCompoundModels($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT prediction_mols.*, prediction_list.model_id, prediction_list.batch_id FROM prediction_mols left join inchi_key using (mol_id) join prediction_list using (pred_id) WHERE mol_id=:id or inchi_key.smiles=:id or inchi_key.inchi=:id or inchi_key.inchi_key=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$singledataset = $stmt->fetchAll(PDO::FETCH_OBJ);  
		$db = null;
		#echo json_encode($singledataset); 
		echo '{"compound models": ' . json_encode($singledataset) . '}'; 
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getModels() {
	$sql = "SELECT model_id, classes, data_type, class_tag, class_scheme  FROM class_models";
	try {
		$db = getConnection();
		$stmt = $db->query($sql);  
		$datasetlist = $stmt->fetchAll(PDO::FETCH_OBJ);
		$db = null;
		echo '{"models": ' . json_encode($datasetlist) . '}';
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getModel($id) {
	$sql = "SELECT model_id, classes, data_type, class_tag, class_scheme, printout FROM class_models WHERE model_id=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$singledataset = $stmt->fetchObject();  
		$db = null;
		echo json_encode($singledataset); 
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}

function getConnection() {


        global $settings;
	$dbhost=$settings['root']['config']['hostname'];
	$dbuser=$settings['root']['config']['ro_user'];
	$dbpass=$settings['root']['config']['ro_password'];
	$dbname=$settings['root']['config']['database'];

	$dbh = new PDO("mysql:host=$dbhost;dbname=$dbname", $dbuser, $dbpass);	
	$dbh->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
	return $dbh;
}

?>
