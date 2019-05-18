

# author: jw
# date: May 2019
# reference: https://www.roytuts.com/python-rest-api-crud-example-using-flask-and-mysql/
#
# Instructions:
# Run FLASK service: python3 main.py
# Find netservice (standard port 5000): sudo netstat -tulnp | grep :5000
# Find & Kill flask service: ps au | grep "python3 main.py" kill -9 ID
# Test: curl http://localhost:5000/
#
import pymysql
import re
import json
from app import app
from db_config import mysql
from flask import jsonify
from flask import flash, request, Response
from werkzeug import generate_password_hash, check_password_hash
	





@app.route('/dataset', methods = ['GET'])
def datasets():
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT batch_id, info, tags,pmid, mol_type FROM batchlist")
		rows = cursor.fetchall()
		resp = jsonify(rows)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()
		
@app.route('/dataset/<int:id>', methods = ['GET'])
def dataset(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT batch_id, info, tags,pmid, mol_type FROM batchlist WHERE batch_id = %s", id)
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()


@app.route('/dataset/<int:id>/compounds', methods = ['GET'])
def dataset_compounds(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT mol_id FROM batchlist join batchmols using (batch_id) WHERE batch_id = %s", id)
		rows = cursor.fetchall()
		resp = jsonify(rows)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<string:id>', methods = ['GET'])
def get_compound(id):
	try:
		id = re.sub('_','/',id)
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT a.*, b.inchi_key, b.smiles, c.compound_name, c.class, c.classifier, c.activity_class, c.*, b.inchi FROM sdftags c left join inchi_key b using (mol_id) left join moldb_moldata a using (mol_id) WHERE ( c.compound_name= %s or mol_name= %s ) or mol_id= %s or ( b.smiles= %s or b.inchi= %s or b.inchi_key= %s )", (id,id,id,id,id,id))
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<int:id>/molid', methods = ['GET'])
def get_compound_molid(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT a.*, b.inchi_key, b.smiles, c.compound_name, c.class, c.classifier, c.activity_class, c.*, b.inchi FROM sdftags c left join inchi_key b using (mol_id) left join moldb_moldata a using (mol_id) WHERE mol_id = %s", id)
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<string:id>/name', methods = ['GET'])
def get_compound_name(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT a.*, b.inchi_key, b.smiles, c.compound_name, c.class, c.classifier, c.activity_class, c.*, b.inchi FROM sdftags c left join inchi_key b using (mol_id) left join moldb_moldata a using (mol_id) WHERE c.compound_name = %s or mol_name = %s", (id,id))
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()


@app.route('/compound/<string:id>/structurefingerprint', methods = ['GET'])
def get_compound_structurefingerprint(id):
	try:
		id = re.sub('_','/',id)
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT fingerprints.*, inchi_key.* FROM sdftags left join inchi_key using (mol_id) left join fingerprints using (mol_id) WHERE mol_id= %s or inchi_key.smiles= %s or inchi_key.inchi= %s or inchi_key.inchi_key= %s", (id,id,id,id))
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<string:id>/propertyfingerprint', methods = ['GET'])
def get_compound_propertyfingerprint(id):
	try:
		id = re.sub('_','/',id)
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT moldb_molstat.*, cdk_descriptors.* FROM sdftags left join inchi_key using (mol_id) left join cdk_descriptors using (mol_id) left join moldb_molstat using (mol_id) WHERE mol_id= %s or inchi_key.smiles= %s or inchi_key.inchi= %s or inchi_key.inchi_key= %s", (id,id,id,id))
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<int:id>/modelfingerprint', methods = ['GET'])
def get_compound_modelfingerprint(id):
	try:
		#id = re.sub('_','/',id)
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT prediction_list.model_id as model_id, prediction_mols.lhood as lhood FROM prediction_mols left join prediction_list using (pred_id) WHERE mol_id= %s", (id))
		rows = cursor.fetchall()

		# convert array data into single json object with all models
		returnModelDict = {}
		returnModelDict['ds'] = {}
		returnModelDict['ds']['mol_id'] = id
		for row in rows:
			#model_id = 'model_' + row['model_id']
			returnModelDict['ds'][row['model_id']] = row['lhood']
        
		#return Response(json.dumps(returnModelList, mimetype='application/json'))
		#resp = jsonify(rows)
		resp = jsonify(returnModelDict)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/compound/<int:id>/models', methods = ['GET'])
def get_compound_models(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT prediction_mols.*, prediction_list.model_id, prediction_list.batch_id FROM prediction_mols left join prediction_list using (pred_id) WHERE mol_id= %s group by mol_id, model_id", (id))
		rows = cursor.fetchall()
		resp = jsonify(rows)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/model', methods = ['GET'])
def get_models():
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT model_id, name, classes, data_type, class_tag, class_scheme, info, pmid, filename FROM class_models left join batchlist using (batch_id)")
		rows = cursor.fetchall()
		resp = jsonify(rows)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()

@app.route('/model/<int:id>', methods = ['GET'])
def get_model(id):
	try:
		conn = mysql.connect()
		cursor = conn.cursor(pymysql.cursors.DictCursor)
		cursor.execute("SELECT model_id, classes, data_type, class_tag, class_scheme, printout FROM class_models WHERE model_id= %s", (id))
		row = cursor.fetchone()
		resp = jsonify(row)
		resp.status_code = 200
		return resp
	except Exception as e:
		print(e)
	finally:
		cursor.close() 
		conn.close()


@app.errorhandler(404)
def not_found(error=None):
    message = {
        'status': 404,
        'message': 'Not Found: ' + request.url + '  For instructions check: http://chemgrid.org/wiki/index.php/MolClass#MolClass_REST_service',
    }
    resp = jsonify(message)
    resp.status_code = 404

    return resp
		
if __name__ == "__main__":
    app.run()
