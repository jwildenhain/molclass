package nick.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import weka.classifiers.Classifier;
import weka.classifiers.trees.*;
import weka.classifiers.rules.*;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.experiment.InstanceQuery;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AddClassification;
import weka.filters.unsupervised.attribute.NumericToNominal;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.RemoveUseless;

public class Predictor {

  static DecimalFormat df = new DecimalFormat("#.########");

  public static void main(String[] args) throws Exception {
    //int pred_id = new Integer(args[0]);
    int pred_id = new Integer(1);
    if (args.length != 1)
    {
            System.out.println("Usage: java -jar MolClass.jar:lib/* Predictor <pred_id>");
            System.out.println("...... Running test with pred_id = " + pred_id);
    } else {
            pred_id = new Integer(args[0]);
            System.out.println("...... Running test with pred_id = " + pred_id);
    }

    // parse config info from XML file
    String username = XMLReader.getTag("rw_user");
    String password = XMLReader.getTag("rw_password");
    String hostname = XMLReader.getTag("hostname");
    String database = XMLReader.getTag("database");
    String classtable = XMLReader.getTag("strucinfotable");
    String cdktable = XMLReader.getTag("cdkdesctable");
    String fptable = XMLReader.getTag("fingerprinttable");
    String batchmoltable = XMLReader.getTag("batchmoltable");
    String modeltable = XMLReader.getTag("modeltable");
    String predtable = XMLReader.getTag("predtable");
    String predmoltable = XMLReader.getTag("predmoltable");
    String website = XMLReader.getTag("website");
    String molclassemail = XMLReader.getTag("molclassemail");

    String databaseURL = new String("jdbc:mysql://" + hostname + "/" + database);

    // get other info from database table
    Connection conn = DriverManager.getConnection(databaseURL, username,
	password);
    String stmt = new String("SELECT batch_id, model_id, username email FROM " + predtable
	+ " WHERE pred_id = ?");
    PreparedStatement pstmt = conn.prepareStatement(stmt,
	ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    pstmt.setInt(1, pred_id);

    ResultSet rs = pstmt.executeQuery();
    rs.next();
    
    String email = rs.getString("email");
    int model_id = rs.getInt("model_id");
    int batch_id = rs.getInt("batch_id");

    stmt = new String("SELECT * FROM " + modeltable + " WHERE model_id = ?");
    pstmt = conn.prepareStatement(stmt, ResultSet.TYPE_FORWARD_ONLY,
	ResultSet.CONCUR_READ_ONLY);
    pstmt.setInt(1, new Integer(model_id));

    rs = pstmt.executeQuery();
    rs.next();

    String data_type = rs.getString("data_type");
    String class_tag = rs.getString("class_tag");
    Classifier classifier = (Classifier) Byter.bytesToObj(rs
	.getBytes("model_data"));
    Instances header = (Instances) Byter.bytesToObj(rs.getBytes("header"));

    // get data
    InstanceQuery query = new InstanceQuery();
    query.setUsername(username);
    query.setPassword(password);
    query.setDatabaseURL(databaseURL);

    Instances unlabeled = null;

    // Classifier classifier =
    // (RandomForest)SerializeJavaObjects_MySQL.readJavaObject(conn, new
    // Long(model_id), modeltable);

    String select_query = null;
    String[] options = null;

    BlobToBits blobToBits = new BlobToBits();
    NumericToNominal numericToNominal = new NumericToNominal();

    if (data_type.equals("CDK")) {

      select_query = new String("SELECT " + cdktable + ".* FROM " + cdktable
	  + ", " + batchmoltable + " WHERE " + cdktable
	  + ".MW IS NOT NULL AND " + batchmoltable + ".mol_id = " + cdktable
	  + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

    } else if (data_type.equals("MACCS")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".MACCS FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".MACCS IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert MACCS to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("SUB")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".SUB FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".SUB IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert SUB to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("PubChem")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".PubChem FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".PubChem IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert PubChem to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert PubChem attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("GO")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".GOFP FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".EXT IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert CDK Graph Only (GO) Fingerprints to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert CDK Extended attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("GOFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("EXT")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".EXT FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".EXT IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert CDK Extended Fingerprints to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert CDK Extended attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("KR")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".KR FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".EXT IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert CDK Klekota Roth Fingerprints to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert CDK Extended attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("EXTGO")) {

      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".EXT " + fptable
	  + ".GOFP FROM " + fptable + ", " + batchmoltable + " WHERE "
	  + fptable + ".EXT IS NOT NULL AND " + batchmoltable + ".mol_id = "
	  + fptable + ".mol_id AND " + batchmoltable + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert CDK Extended Fingerprints to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert CDK Extended attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert CDK Graph Only Fingerprints to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "3";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert CDK Graph Only attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("GOFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);
      
    } else if (data_type.equals("ALL")) {


          select_query = new String("select * from " +  cdktable + " limit 1");
          // create a statement
          pstmt = conn.prepareStatement(select_query, ResultSet.TYPE_FORWARD_ONLY,
	          ResultSet.CONCUR_READ_ONLY);
          // execute query and return result as a ResultSet
          rs = pstmt.executeQuery();
          // get the column names from the ResultSet
          String cdk_table_header = getColumnNames(rs);
          //System.out.println(cdk_table_header);


      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".MACCS, " + fptable + ".PubChem, " + fptable + ".EXT, " + fptable + ".SUB, "
          + cdk_table_header + " FROM " + fptable + ", " + cdktable
	  + ", " + batchmoltable + " WHERE " + cdktable + ".mol_id = "
	  + fptable + ".mol_id AND " + cdktable + ".MW IS NOT NULL AND "
	  + fptable + ".MACCS IS NOT NULL AND " + cdktable + ".mol_id = "
	  + batchmoltable + ".mol_id AND " + batchmoltable + ".batch_id ="
	  + batch_id);
      // !!! EXT SUB PubChem not null (Klotho)
      
      //System.out.println(select_query);
      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert MACCS to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "3";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert PubChem attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert EXT to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "4";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert EXT attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "5";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

    } else if (data_type.equals("MCAT")) {


          select_query = new String("select * from " +  cdktable + " limit 1");
          // create a statement
          pstmt = conn.prepareStatement(select_query, ResultSet.TYPE_FORWARD_ONLY,
	          ResultSet.CONCUR_READ_ONLY);
          // execute query and return result as a ResultSet
          rs = pstmt.executeQuery();
          // get the column names from the ResultSet
          String cdk_table_header = getColumnNames(rs);
          //System.out.println(cdk_table_header);


      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".MACCS, " + fptable + ".PubChem, " + fptable + ".ESFP, " + fptable + ".SUB, "
          + fptable + ".KR, " 
          + cdk_table_header + " FROM " + fptable + ", " + cdktable
	  + ", " + batchmoltable + " WHERE " + cdktable + ".mol_id = "
	  + fptable + ".mol_id AND " + cdktable + ".MW IS NOT NULL AND "
	  + fptable + ".MACCS IS NOT NULL AND " + cdktable + ".mol_id = "
	  + batchmoltable + ".mol_id AND " + batchmoltable + ".batch_id ="
	  + batch_id);
      // !!! EXT SUB PubChem not null (Klotho)
      
      //System.out.println(select_query);
      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert MACCS to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "3";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert PubChem attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert EXT to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "4";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert ESFP attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("ESFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "5";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);
      
      // convert KR to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "6";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert KR attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);
      
    } else if (data_type.equals("JUMBO")) {


          select_query = new String("select * from " +  cdktable + " limit 1");
          // create a statement
          pstmt = conn.prepareStatement(select_query, ResultSet.TYPE_FORWARD_ONLY,
	          ResultSet.CONCUR_READ_ONLY);
          // execute query and return result as a ResultSet
          rs = pstmt.executeQuery();
          // get the column names from the ResultSet
          String cdk_table_header = getColumnNames(rs);
          //System.out.println(cdk_table_header);


      select_query = new String("SELECT " + fptable + ".mol_id, " + fptable
	  + ".MACCS, " + fptable + ".PubChem, " + fptable + ".EXT, " + fptable + ".SUB, "
          + fptable + ".KR, " 
          + cdk_table_header + " FROM " + fptable + ", " + cdktable
	  + ", " + batchmoltable + " WHERE " + cdktable + ".mol_id = "
	  + fptable + ".mol_id AND " + cdktable + ".MW IS NOT NULL AND "
	  + fptable + ".MACCS IS NOT NULL AND " + cdktable + ".mol_id = "
	  + batchmoltable + ".mol_id AND " + batchmoltable + ".batch_id ="
	  + batch_id);
      // !!! EXT SUB PubChem not null (Klotho)
      
      //System.out.println(select_query);
      query.setQuery(select_query);
      unlabeled = query.retrieveInstances();

      // convert MACCS to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = unlabeled.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "3";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert PubChem attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert EXT to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "4";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert EXT attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "5";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);
      
      // convert KR to seperate attributes
      options = new String[2];
      options[0] = "-R";
      options[1] = "6";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, blobToBits);

      // convert KR attributes to nominal
      options = new String[2];
      maccInd = unlabeled.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(unlabeled);
      unlabeled = Filter.useFilter(unlabeled, numericToNominal);
      
    } else {
      throw new Exception("Unsupported data_type: " + data_type);
    }

    unlabeled.insertAttributeAt(header.classAttribute(), unlabeled
	.numAttributes());

    StringBuffer text = new StringBuffer();
    text.append("=== Predicted values ===\n\n");
    text.append(classifier.toString() + "\n");

    text.append("Classification Categories\n");
    for (int j = 0; j < header.classAttribute().numValues(); j++) {
      char ch = (char) ('a' + j);
      text.append(ch + " - " + header.classAttribute().value(j) + "\n");
    }

    text.append("\n");

    // table top bar
    text.append(Utils.padRight("   mol_id", 15)
	+ Utils.padRight("   Predicted Classification", 27) + '\t');
    for (int j = 0; j < header.classAttribute().numValues(); j++) {
      char ch = (char) ('a' + j);
      text.append(" " + ch + "\t");
    }
    text.append("\n");

    // compares the attributes in unlabeled to the attributes in header
    // discard attributes from unlabeled which are not in header
    for (int i = 0; i < unlabeled.numInstances(); i++) {
      Instance curr = unlabeled.instance(i);
      // create an instance for the classifier that fits the training data
      // Instances object returned here might differ slightly from the one
      // used during training the classifier, e.g., different order of
      // nominal values, different number of attributes.
      Instance inst = new DenseInstance(header.numAttributes());
      inst.setDataset(header);
      for (int n = 0; n < header.numAttributes(); n++) {
	Attribute att = unlabeled.attribute(header.attribute(n).name());
	// original attribute is also present in the current dataset
	if (att != null) {
	  if (att.isNominal()) {
	    // is this label also in the original data?
	    // Note:
	    // "numValues() > 0" is only used to avoid problems with nominal
	    // attributes that have 0 labels, which can easily happen with
	    // data loaded from a database
	    if ((header.attribute(n).numValues() > 0) && (att.numValues() > 0)) {
	      String label = curr.stringValue(att);
	      int index = header.attribute(n).indexOfValue(label);
	      if (index != -1)
		inst.setValue(n, index);
	    }
	  } else if (att.isNumeric()) {
	    inst.setValue(n, curr.value(att));
	  } else {
	    throw new IllegalStateException("Unhandled attribute type!");
	  }
	}
      }

      unlabeled.setClass(unlabeled.attribute(class_tag));

      double pred = classifier.classifyInstance(inst);
      
      double id = unlabeled.instance(i).value(unlabeled.attribute("mol_id"));
      String mol_id = unlabeled.attribute("mol_id").value((int) id);
      String pred_class = unlabeled.classAttribute().value((int) pred);
      String mol_col = Utils.padRight(mol_id, 15);
      String class_col = Utils.padRight(pred_class, 27);
      text.append(mol_col + class_col + '\t');

      double[] dist = classifier.distributionForInstance(inst);

      StringBuffer mol_dist = new StringBuffer();

      for (int x = 0; x < dist.length; x++) {
	double d = dist[x];
	text.append(df.format(d));
	mol_dist.append(df.format(d));
	if (x == (int) pred) {
	  text.append('*');
	}
	text.append('\t');
	mol_dist.append('\t');

	// System.out.print(predictionText(classifier, inst, new
	// Integer(mol_id)));
      }
      text.append('\n');
      // make log likelihood on first property column
     
      double llhood = logIt(dist[0],0.001);
      //System.out.print(llhood);
      
      stmt = new String("INSERT INTO " + predmoltable
		+ "(mol_id, pred_id, main_class, distribution, lhood) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE mol_id = ? , pred_id = ?");
      
      pstmt = conn.prepareStatement(stmt, ResultSet.TYPE_FORWARD_ONLY,
		ResultSet.CONCUR_UPDATABLE);
      pstmt.setInt(1, new Integer(mol_id));
      pstmt.setInt(2, pred_id);
      pstmt.setString(3, pred_class);
      pstmt.setString(4, mol_dist.toString());
      pstmt.setDouble(5, llhood);
      pstmt.setInt(6, new Integer(mol_id));
      pstmt.setInt(7, pred_id);
      pstmt.executeUpdate();
        
    }


    stmt = new String("UPDATE " + predtable
	+ " SET printout = ? WHERE pred_id = ?");
    pstmt = conn.prepareStatement(stmt, ResultSet.TYPE_FORWARD_ONLY,
	ResultSet.CONCUR_UPDATABLE);
    pstmt.setString(1, text.toString());
    pstmt.setInt(2, pred_id);
    pstmt.executeUpdate();

    conn.close();

    // System.out.println(text.toString());
    
    // ////////////
    // Send Email
    // ///////////

    String[] to = { email };
    String subject = new String("MolClass prediction completed");
    String message = new String(
	"MolClass has completed creating your predictions. Details can be found at " + website + "/view_pred.php?pred_id="
	    + pred_id);
    String from = new String(molclassemail);
    // disable email for now.
    //postMail(to, subject, message, from);

  }


private static void postMail(String recipients[], String subject,
    String message, String from) throws MessagingException {
  boolean debug = false;

  // Set the host smtp address
  Properties props = new Properties();
  props.put("mail.smtp.host", "localhost");

  // create some properties and get the default Session
  Session session = Session.getDefaultInstance(props, null);
  session.setDebug(debug);

  // create a message
  Message msg = new MimeMessage(session);

  // set the from and to address
  InternetAddress addressFrom = new InternetAddress(from);
  msg.setFrom(addressFrom);

  InternetAddress[] addressTo = new InternetAddress[recipients.length];
  for (int i = 0; i < recipients.length; i++) {
    addressTo[i] = new InternetAddress(recipients[i]);
  }
  msg.setRecipients(Message.RecipientType.TO, addressTo);

  // Optional : You can also set your custom headers in the Email if you Want
  msg.addHeader("MyHeaderName", "myHeaderValue");

  // Setting the Subject and Content Type
  msg.setSubject(subject);
  msg.setContent(message, "text/plain");
  Transport.send(msg);
}

  private static String getColumnNames(ResultSet rs) throws SQLException {
    if (rs == null) {
      return("");
    }
    ResultSetMetaData rsMetaData = rs.getMetaData();
    int numberOfColumns = rsMetaData.getColumnCount();
    String header = rsMetaData.getTableName(2) + "." + rsMetaData.getColumnName(2);
    // get the column names; column indexes start from 1
    for (int i = 3; i < numberOfColumns + 1; i++) {
      String columnName = rsMetaData.getColumnName(i);
      // Get the name of the column's table name
      String tableName = rsMetaData.getTableName(i);
      header = header + "," + tableName + "." + columnName;
      //System.out.println("column name=" + columnName + " table=" + tableName + "");
    }
    return(header);
  }

  private static double logIt(double p, double offset) {
      double loglike = Math.log((p+offset)/(1+offset-p));
      return(loglike);
 }




}
