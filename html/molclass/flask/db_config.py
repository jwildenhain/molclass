from app import app
from flaskext.mysql import MySQL

mysql = MySQL()
 
# MySQL configurations
app.config['MYSQL_DATABASE_USER'] = 'jw'
app.config['MYSQL_DATABASE_PASSWORD'] = 'engels01'
app.config['MYSQL_DATABASE_DB'] = 'molclass_v15'
app.config['MYSQL_DATABASE_HOST'] = 'localhost'
mysql.init_app(app)