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

$app->get('/', 'showRESTinstructions');
$app->get('/dataset', 'getDatasets');
$app->get('/dataset/:id',	'getDataset');
$app->get('/dataset/:id/compounds',	'getDatasetCompounds');

$app->get('/compound/:id',	'getCompound');
$app->get('/compound/:id/structurefingerprint',	'getCompoundStructuralFingerprint');
$app->get('/compound/:id/propertyfingerprint',	'getCompoundPropertyFingerprint');
$app->get('/compound/:id/modelfingerprint',	'getCompoundModelFingerprint');

$app->get('/compound/:id/models',	'getCompoundModels');

$app->get('/model', 'getModels');
$app->get('/model/:id',	'getModel');

$app->run();

function showRESTinstructions() {

		echo '{"instructions": "http://sysbiolab.bio.ed.ac.uk/wiki/index.php/MolClass#MolClass_REST_service" }';
}

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

	$sql = "SELECT a.*, b.inchi_key, b.smiles, c.compound_name, c.class, c.classifier, c.activity_class, c.*, b.inchi FROM sdftags c left join inchi_key b using (mol_id) left join moldb_moldata a using (mol_id) WHERE ( compound_name=:id or mol_name=:id ) or mol_id=:id or ( b.smiles=:id or b.inchi=:id or b.inchi_key=:id )";
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

function getCompoundStructuralFingerprint($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT fingerprints.*, inchi_key.* FROM sdftags left join inchi_key using (mol_id) left join fingerprints using (mol_id) WHERE mol_id=:id or inchi_key.smiles=:id or inchi_key.inchi=:id or inchi_key.inchi_key=:id";
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

function getCompoundPropertyFingerprint($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT moldb_molstat.*, cdk_descriptors.* FROM sdftags left join inchi_key using (mol_id) left join cdk_descriptors using (mol_id) left join moldb_molstat using (mol_id) WHERE mol_id=:id or inchi_key.smiles=:id or inchi_key.inchi=:id or inchi_key.inchi_key=:id";
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

function getCompoundModelFingerprint($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT prediction_list.model_id, prediction_mols.lhood FROM prediction_mols left join prediction_list using (pred_id) WHERE mol_id=:id";
	try {
		$db = getConnection();
		$stmt = $db->prepare($sql);  
		$stmt->bindParam("id", $id);
		$stmt->execute();
		$singledataset = $stmt->fetchAll(PDO::FETCH_OBJ);
		//print_r($singledataset); 
		$returnModelArray = array();
		// push model id into array 
        $returnModelArray["mol_id"] = $id;          
    	foreach($singledataset as $i => $i_value) {
            $returnModelArray["model_".$i_value->model_id] = $i_value->lhood;
        }		 
		$db = null;
		echo json_encode($returnModelArray); 
	} catch(PDOException $e) {
		echo '{"error":{"text":'. $e->getMessage() .'}}'; 
	}
}


function getCompoundModels($id) {

        $id = str_replace("_","/",$id);

	$sql = "SELECT prediction_mols.*, prediction_list.model_id, prediction_list.batch_id FROM prediction_mols left join prediction_list using (pred_id) WHERE mol_id=:id group by mol_id, model_id";
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
	$sql = "SELECT model_id, name, classes, data_type, class_tag, class_scheme, info, pmid, filename FROM class_models left join batchlist using (batch_id)";
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
