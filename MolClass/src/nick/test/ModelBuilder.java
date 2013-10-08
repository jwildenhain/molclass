package nick.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.Random;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.BayesNet;
import weka.classifiers.trees.*;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.rules.*;
import weka.classifiers.meta.StackingC;
import weka.classifiers.rules.DTNB;
import weka.classifiers.bayes.HNB;
import weka.classifiers.lazy.IBk;
import weka.classifiers.meta.RealAdaBoost;
import weka.classifiers.meta.LogitBoost;
import weka.classifiers.meta.RacedIncrementalLogitBoost;
import weka.classifiers.functions.SMO;
import weka.classifiers.functions.LibSVM;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.functions.MultilayerPerceptron;
//import weka.filters.supervised.attribute.PLSFilter;
import weka.classifiers.meta.CVParameterSelection;
//import weka.classifiers.SingleClassifierEnhancer;
//import weka.classifiers.RandomizableSingleClassifierEnhancer;
//import weka.classifiers.meta.GridSearch;

import weka.core.Attribute;
import weka.core.Instances;
import weka.core.OptionHandler;
import weka.core.Range;
import weka.experiment.InstanceQuery;
import weka.filters.Filter;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.unsupervised.attribute.NumericToNominal;
import weka.filters.supervised.attribute.Discretize;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.RemoveUseless;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

public class ModelBuilder {

  public static void main(String[] args) throws Exception {

    int model_id = new Integer(1);
    if (args.length != 1)
    {
        	System.out.println("Usage: java -jar MolClass.jar:lib/* ModelBuilder <model_id>");
                System.out.println("\nRunning test with model_id = " +  model_id + ".");

    } else {

                model_id = new Integer(args[0]);
                System.out.println("...... Running test with model_id = " + model_id);


    }
    
    //read info from XML config file
    String hostname = XMLReader.getTag("hostname");
    String database = XMLReader.getTag("database");
    String username = XMLReader.getTag("rw_user");
    String password = XMLReader.getTag("rw_password");
    String classtable = XMLReader.getTag("strucinfotable");
    String cdktable = XMLReader.getTag("cdkdesctable");
    String fptable = XMLReader.getTag("fingerprinttable");
    String batchmoltable = XMLReader.getTag("batchmoltable");
    String modeltable = XMLReader.getTag("modeltable");
    String website = XMLReader.getTag("website");
    String molclassemail = XMLReader.getTag("molclassemail");
    int crossValidateModelFromXML = Integer.parseInt(XMLReader.getTag("crossValidateModel"));
    double setDistributionSpreadFromXML = Double.parseDouble(XMLReader.getTag("setDistributionSpread"));

    
    String databaseURL = new String("jdbc:mysql://" + hostname + "/" + database);
    
    //get info about model from modeltable
    Connection conn = DriverManager.getConnection(databaseURL, username,
	password);
    String stmt = new String("SELECT username, batch_id, data_type, class_tag, class_scheme, username email FROM " + modeltable + " WHERE model_id = ?");
    PreparedStatement pstmt = conn.prepareStatement(stmt, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    pstmt.setInt(1, model_id);
    
    ResultSet rs = pstmt.executeQuery();
    rs.next();
    
    String user = rs.getString("username");
    int batch_id = rs.getInt("batch_id");
    String data_type = rs.getString("data_type");
    String class_tag = rs.getString("class_tag");
    String class_scheme = rs.getString("class_scheme");
    String email = rs.getString("email");
    

    InstanceQuery query = new InstanceQuery();
    query.setUsername(username);
    query.setPassword(password);
    query.setDatabaseURL(databaseURL);
    
    conn.close();

    //Retrieve instances
    Instances data = null;

    Remove remove = new Remove();
    RemoveUseless removeUseless = new RemoveUseless();
    NumericToNominal numericToNominal = new NumericToNominal();
 //   Discretize numericToNominal = new Discretize();
    //Discretize discretize = new Discretize();
    BlobToBits blobToBits = new BlobToBits();

    String[] options = null;

    String select_query = null;

    // Program splits here depending on what data type the user is using. If you
    // wish to implement a new data type your should add a new else-if statement
    // to this section

    // CDK Descriptors
    if (data_type.equals("CDK")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + cdktable + ". * FROM " + classtable + ", " + cdktable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + cdktable + ".MW IS NOT NULL AND " + classtable
	  + ".mol_id = " + cdktable + ".mol_id AND " + classtable
	  + ".mol_id = " + batchmoltable + ".mol_id AND " + batchmoltable
	  + ".batch_id =" + batch_id);

      query.setQuery(select_query);
      data = query.retrieveInstances();

      options = new String[2];

      // remove mol_id
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless attributes
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // MACCS Fingerprint
    } else if (data_type.equals("MACCS")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".MACCS FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".MACCS IS NOT NULL");

      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert MACCS to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "200";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove MACCS
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // Both Substructure Fingerprint
      // http://pele.farmbio.uu.se/nightly/api/org/openscience/cdk/fingerprint/SubstructureFingerprinter.html
    } else if (data_type.equals("SUB")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".SUB FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".SUB IS NOT NULL");

      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert SUB to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "310";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove SUB
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // Both Graph Only Fingerprint
    } else if (data_type.equals("GO")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".GOFP FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".GOFP IS NOT NULL");

      query.setQuery(select_query);
      data = query.retrieveInstances();

      // convert GOFP to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "1110";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert GOFP attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("GOFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove GOFP
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // Both Klekota-Roth Fingerprint
    } else if (data_type.equals("KR")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".KR FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".KR IS NOT NULL");

      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert KR to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "5100";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert KR attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove KR
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // Both Pubchem Fingerprint
    } else if (data_type.equals("PubChem")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".PubChem FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".PubChem IS NOT NULL");

      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert PubChem to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "1000"; // number of columns
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert PubChem attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove PubChem
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // CDK Extended Fingerprints
    } else if (data_type.equals("EXT")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".EXT FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".EXT IS NOT NULL");
      
      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert fingerprint to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "1100"; // number of columns
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert fingerprint attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove fingerprint
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // Both graph theoretical fingerprints - GraphOnly + ECFP
    }  else if (data_type.equals("EXTGO")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".EXT, " + fptable + ".GOFP FROM " + classtable + ", " + fptable + ", "
	  + batchmoltable + " WHERE NOT " + classtable + "." + class_tag
	  + " = '' AND " + classtable + ".mol_id = " + fptable + ".mol_id AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".EXT IS NOT NULL");
      
      query.setQuery(select_query);
      data = query.retrieveInstances();


      // convert EC fingerprint to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "1100"; // number of columns
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert EC fingerprint attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);
      
      // convert GO fingerprint to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "3";
      options[2] = "-C";
      options[3] = "1100";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert GO fingerprint to nominal
      options = new String[2];
      maccInd = data.attribute("GOFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove EXT ECFP fingerprint
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove GO ECFP
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      
      // remove useless values
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // CDK descriptors and MACCS,PubChem, Extended (ECFP) and Sub fingerprints
    } else if (data_type.equals("ALL")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".MACCS, " + fptable + ".PubChem, " + fptable + ".EXT, "
          + fptable + ".SUB, " + cdktable + ".* FROM " + classtable + ", "
	  + fptable + ", " + cdktable + ", " + batchmoltable + " WHERE NOT "
	  + classtable + "." + class_tag + " = '' AND " + classtable
	  + ".mol_id = " + fptable + ".mol_id AND " + classtable + ".mol_id = "
	  + cdktable + ".mol_id AND " + cdktable + ".MW <> 0 AND "
	  + cdktable + ".MW IS NOT NULL AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".MACCS IS NOT NULL");
      // !!! add PubChem EXT, Klotho, SUB not null
      System.out.println(select_query);
      query.setQuery(select_query);
      data = query.retrieveInstances();
     
      // convert MACCS to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "200";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);
      
      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "3";
      options[2] = "-C";
      options[3] = "1000";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert Pubchem attributes to nominal
      options = new String[2];
      maccInd = data.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert EXT to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "4";
      options[2] = "-C";
      options[3] = "1100";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert EXT attributes to nominal
      options = new String[2];
      maccInd = data.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "5";
      options[2] = "-C";
      options[3] = "310";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = data.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // remove  MACCS
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove PubChem
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove EXT
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove SUB
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove MolID
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless attributes
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // remove VABC
      //options = new String[2];
      //maccInd = data.attribute("VABC").index();
      //options[0] = "-R";
      //options[1] = new String(maccInd);


    } else if (data_type.equals("JUMBO")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".MACCS, " + fptable + ".PubChem, " + fptable + ".EXT, "
          + fptable + ".SUB, " + fptable + ".KR, " + cdktable + ".* FROM " 
          + classtable + ", "
	  + fptable + ", " + cdktable + ", " + batchmoltable + " WHERE NOT "
	  + classtable + "." + class_tag + " = '' AND " + classtable
	  + ".mol_id = " + fptable + ".mol_id AND " + classtable + ".mol_id = "
	  + cdktable + ".mol_id AND " + cdktable + ".MW <> 0 AND "
	  + cdktable + ".MW IS NOT NULL AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".MACCS IS NOT NULL");
      // !!! add PubChem EXT, Klotho, SUB not null
      System.out.println(select_query);
      query.setQuery(select_query);
      data = query.retrieveInstances();
     
      // convert MACCS to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "200";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);
      
      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "3";
      options[2] = "-C";
      options[3] = "1000";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert Pubchem attributes to nominal
      options = new String[2];
      maccInd = data.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert EXT to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "4";
      options[2] = "-C";
      options[3] = "1100";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert EXT attributes to nominal
      options = new String[2];
      maccInd = data.attribute("EXT_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "5";
      options[2] = "-C";
      options[3] = "310";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = data.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert KR to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "6";
      options[2] = "-C";
      options[3] = "5110";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert KR attributes to nominal
      options = new String[2];
      maccInd = data.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);
      
      
      // remove  MACCS
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove PubChem
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove EXT
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove SUB
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove KR
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove MolID
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless attributes
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // remove VABC
      //options = new String[2];
      //maccInd = data.attribute("VABC").index();
      //options[0] = "-R";
      //options[1] = new String(maccInd);


    }  else if (data_type.equals("MCAT")) {

      select_query = new String("SELECT " + classtable + "." + class_tag + ", "
	  + fptable + ".MACCS, " + fptable + ".PubChem, " + fptable + ".ESFP, "
          + fptable + ".SUB, " + fptable + ".KR, " + cdktable + ".* FROM " 
          + classtable + ", "
	  + fptable + ", " + cdktable + ", " + batchmoltable + " WHERE NOT "
	  + classtable + "." + class_tag + " = '' AND " + classtable
	  + ".mol_id = " + fptable + ".mol_id AND " + classtable + ".mol_id = "
	  + cdktable + ".mol_id AND " + cdktable + ".MW <> 0 AND "
	  + cdktable + ".MW IS NOT NULL AND "
	  + classtable + ".mol_id = " + batchmoltable + ".mol_id AND "
	  + batchmoltable + ".batch_id =" + batch_id + " AND " + fptable
	  + ".MACCS IS NOT NULL");
      // !!! add PubChem EXT, Klotho, SUB not null
      System.out.println(select_query);
      query.setQuery(select_query);
      data = query.retrieveInstances();
     
      // convert MACCS to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "2";
      options[2] = "-C";
      options[3] = "200";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);
      
      // convert MACCS attributes to nominal
      options = new String[2];
      int maccInd = data.attribute("MACCS_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert PubChem to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "3";
      options[2] = "-C";
      options[3] = "1000";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert Pubchem attributes to nominal
      options = new String[2];
      maccInd = data.attribute("PubChem_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert ESFP to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "4";
      options[2] = "-C";
      options[3] = "100";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert ESFP attributes to nominal
      options = new String[2];
      maccInd = data.attribute("ESFP_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert SUB to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "5";
      options[2] = "-C";
      options[3] = "310";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert SUB attributes to nominal
      options = new String[2];
      maccInd = data.attribute("SUB_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);

      // convert KR to seperate attributes
      options = new String[4];
      options[0] = "-R";
      options[1] = "6";
      options[2] = "-C";
      options[3] = "5110";
      blobToBits.setOptions(options);
      blobToBits.setInputFormat(data);
      data = Filter.useFilter(data, blobToBits);

      // convert KR attributes to nominal
      options = new String[2];
      maccInd = data.attribute("KR_0").index();
      options[0] = "-R";
      options[1] = new String(maccInd + "-last");
      numericToNominal.setOptions(options);
      numericToNominal.setInputFormat(data);
      data = Filter.useFilter(data, numericToNominal);
      
      
      // remove  MACCS
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove PubChem
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove ESFP
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove SUB
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove KR
      options = new String[2];
      options[0] = "-R";
      options[1] = "2";
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);
      // remove MolID
      remove.setOptions(options);
      remove.setInputFormat(data);
      data = Filter.useFilter(data, remove);

      // remove useless attributes
      options = new String[2];
      options[0] = "-M";
      options[1] = "99.0";
      removeUseless.setOptions(options);
      removeUseless.setInputFormat(data);
      data = Filter.useFilter(data, removeUseless);

      // remove VABC
      //options = new String[2];
      //maccInd = data.attribute("VABC").index();
      //options[0] = "-R";
      //options[1] = new String(maccInd);


    } else {
      throw new Exception("data_type " + data_type + " invalid.");
    }
    
    // set classification to first column
    data.setClassIndex(0);

    // get number of data columns for

    // split on classification type
    Classifier classifier = null;

    if (class_scheme.equals("RandomForest")) {
  //    classifier = new  CVParameterSelection();
  //    ((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-P \"I 50 100 2\" -W weka.classifiers.trees.RandomForest -- -K 0 -S 2"));
      classifier = new RandomForest();
      ((RandomForest)classifier).setOptions(weka.core.Utils.splitOptions("-I 100 -K 0 -S 2"));
    } else if (class_scheme.equals("LMT")) {
      classifier = new LMT();
      ((LMT)classifier).setOptions(weka.core.Utils.splitOptions("-I 1 -M 15 -W 0.0"));
//    } else if (class_scheme.equals("Prism")){
//      classifier = new Prism();
//    } else if (class_scheme.equals("GridSearch")){
//      classifier = new GridSearch();
//      String[] class_options = {""};
//      ((GridSearch)classifier).setOptions(class_options);
    } else if (class_scheme.equals("LibSVM")){
        classifier = new  LibSVM();
        ((LibSVM)classifier).setOptions(weka.core.Utils.splitOptions("-S 0 -C 2 -Z"));
        ((LibSVM)classifier).setProbabilityEstimates(true);
    } else if (class_scheme.equals("LibSVM2")){
      //classifier = new  CVParameterSelection();
      //((CVParameterSelection)classifier).addCVParameter("G 1 10 11");
      //"-P","C 1 1 1",
      //String[] class_options = {"-W","weka.classifiers.functions.LibSVM","--Z","--S","0","--C","2"};
      //((CVParameterSelection)classifier).setOptions(class_options);
      //((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-C 1.0 -L 0.0010 -P 1.0E-12 -N 0 -V -1 -W 1 -K \"weka.classifiers.functions.supportVector.PolyKernel -C 250007 -E 1.0\""));
      //((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-W weka.classifiers.functions.LibSVM -P \"G 1.0 10.0 11.0\" -- -Z -S 0 -C 2"));
//      http://www.cs.iastate.edu/~yasser/wlsvm/
//      http://weka.sourceforge.net/doc.stable/weka/classifiers/functions/LibSVM.html
//      -S 2 -K 2 -D 3 -G 0.2 -R 0.0 -N 0.5 -M 40.0 -C 1.0 -E 0.0010 -P 0.1
//      http://www.cs.iastate.edu/~yasser/wlsvm/
        classifier = new  LibSVM();
        ((LibSVM)classifier).setOptions(weka.core.Utils.splitOptions("-Z  -S 0 -K 2 -D 3 -R 0.0 -N 0.5 -M 40.0 -C 1.0 -E 0.0010 -P 0.1"));
        ((LibSVM)classifier).setProbabilityEstimates(true);
    } else if (class_scheme.equals("SMO")){
      classifier = new  CVParameterSelection();
      ((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-P \"C 0.0001 10 10\" -W weka.classifiers.functions.SMO -- -M -V 10"));
      //classifier = new  SMO();
      //String[] class_options = {"-C 0.5 -M -V 10"};
      //((SMO)classifier).setOptions(class_options);
    } else if (class_scheme.equals("KNN")){
      classifier = new  CVParameterSelection();
      ((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-P \"K 3 20 18\" -W weka.classifiers.lazy.IBk"));
   //   classifier = new  IBk();
   //   ((IBk)classifier).setOptions(weka.core.Utils.splitOptions("-K 10"));
    } else if (class_scheme.equals("J48")){ // try -U
      classifier = new  CVParameterSelection();
      ((CVParameterSelection)classifier).setOptions(weka.core.Utils.splitOptions("-P \"C 0.05 0.4 8\" -W weka.classifiers.trees.J48 -- -M 2 -A "));
      //classifier = new  J48();
      //((J48)classifier).setOptions(weka.core.Utils.splitOptions("-A -U"));
    } else if (class_scheme.equals("realAdaBoost")) {
      //
      // RealAdaBoost Add-On to improve 2-class classification
      //weka.classifiers.meta
      classifier = new RealAdaBoost();
      String[] class_options = {"-Q"}; // enable/disable kernel -K
      ((RealAdaBoost)classifier).setOptions(class_options);
    } else if (class_scheme.equals("LogitBoost")) {
      classifier = new LogitBoost();
      String[] class_options = {""}; // enable/disable kernel -K
      ((LogitBoost)classifier).setOptions(class_options);
    } else if (class_scheme.equals("RacedIncrementalLogitBoost")) {
      classifier = new RacedIncrementalLogitBoost();
      String[] class_options = {"-Q"}; // enable/disable kernel -K
      ((RacedIncrementalLogitBoost)classifier).setOptions(class_options);
    } else if (class_scheme.equals("Ensemble")) {
      classifier = new StackingC();
      // -B " alg + parameters" -X CV
      // -B "weka.classifiers.functions.Logistic -R 1.0E-8 -M -1"
      // -B "weka.classifiers.trees.RandomForest -I 100 -K 10"
      // -B "weka.classifiers.meta.AdaBoostM1 -P 100 -S 1 -I 10 -W weka.classifiers.trees.DecisionStump"
      // -B "weka.classifiers.functions.MultilayerPerceptron -L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a"
      ((StackingC)classifier).setOptions(weka.core.Utils.splitOptions("weka.classifiers.meta.Vote -S 1 -B \"weka.classifiers.trees.J48 -C 0.25 -M 2\" -B \"weka.classifiers.bayes.NaiveBayes\" -B \"weka.classifiers.rules.OneR -B 6\" -B \"weka.classifiers.meta.AdaBoostM1 -P 100 -S 1 -I 10 -W weka.classifiers.trees.DecisionStump\" -B \"weka.classifiers.trees.RandomForest -I 100 -K 10\" -B \"weka.classifiers.functions.SMO -C 1.0 -L 0.0010 -P 1.0E-12 -N 0 -V -1 -W 1\" -R MAJ"));
      //((StackingC)classifier).setOptions(weka.core.Utils.splitOptions("weka.classifiers.meta.StackingC -X 10 -M \"weka.classifiers.functions.LinearRegression -S 1 -R 1.0E-8\" -S 1 -B \"weka.classifiers.trees.J48 -C 0.25 -M 2\" -B \"weka.classifiers.functions.Logistic -R 1.0E-8 -M -1\" -B \"weka.classifiers.functions.MultilayerPerceptron -L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a\" -B \"weka.classifiers.bayes.NaiveBayes\" -B \"weka.classifiers.rules.OneR -B 6\" -B \"weka.classifiers.functions.SMO -C 1.0 -L 0.0010 -P 1.0E-12 -N 0 -V -1 -W 1 -K \\\"weka.classifiers.functions.supportVector.PolyKernel -C 250007 -E 1.0\\\"\" -B \"weka.classifiers.lazy.IBk -K 1 -W 0 -A \\\"weka.core.neighboursearch.LinearNNSearch -A \\\\\\\"weka.core.EuclideanDistance -R first-last\\\\\\\"\\\"\""));
    } else if (class_scheme.equals("Ensemble2")) {
      classifier = new StackingC();
      // -B " alg + parameters" -X CV
      // -B "weka.classifiers.functions.Logistic -R 1.0E-8 -M -1"
      // -B "weka.classifiers.meta.AdaBoostM1 -P 100 -S 1 -I 10 -W weka.classifiers.trees.DecisionStump"
      // -B "weka.classifiers.functions.MultilayerPerceptron -L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a"
      //((StackingC)classifier).setOptions(weka.core.Utils.splitOptions("weka.classifiers.meta.Vote -S 1 -B \"weka.classifiers.trees.J48 -C 0.25 -M 2\" -B \"weka.classifiers.bayes.NaiveBayes\" -B \"weka.classifiers.rules.OneR -B 6\" -B \"weka.classifiers.functions.SMO -C 1.0 -L 0.0010 -P 1.0E-12 -N 0 -V -1 -W 1\" -R MAJ"));
      ((StackingC)classifier).setOptions(weka.core.Utils.splitOptions("weka.classifiers.meta.StackingC -X 10 -M \"weka.classifiers.functions.LinearRegression -S 1 -R 1.0E-8\" -S 1 -B \"weka.classifiers.trees.J48 -C 0.25 -M 2\" -B \"weka.classifiers.functions.Logistic -R 1.0E-8 -M -1\" -B \"weka.classifiers.functions.MultilayerPerceptron -L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a\" -B \"weka.classifiers.bayes.NaiveBayes\" -B \"weka.classifiers.rules.OneR -B 6\" -B \"weka.classifiers.functions.SMO -C 1.0 -L 0.0010 -P 1.0E-12 -N 0 -V -1 -W 1 -K \\\"weka.classifiers.functions.supportVector.PolyKernel -C 250007 -E 1.0\\\"\" -B \"weka.classifiers.lazy.IBk -K 1 -W 0 -A \\\"weka.core.neighboursearch.LinearNNSearch -A \\\\\\\"weka.core.EuclideanDistance -R first-last\\\\\\\"\\\"\""));
    } else if (class_scheme.equals("NeuralNet")) {
      classifier = new MultilayerPerceptron();
    //  String[] class_options = {""}; // enable/disable kernel -K
        ((MultilayerPerceptron)classifier).setOptions(weka.core.Utils.splitOptions("-L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a"));
     // ((BayesNet)classifier).setOptions(class_options);
    } else if (class_scheme.equals("BayesNet")) {
      classifier = new BayesNet();
    //  String[] class_options = {""}; // enable/disable kernel -K
        ((BayesNet)classifier).setOptions(weka.core.Utils.splitOptions("-Q weka.classifiers.bayes.net.search.local.K2 -- -P 2 -R -S BAYES"));
     // ((BayesNet)classifier).setOptions(class_options);
    } else if (class_scheme.equals("NaiveBayes")) {
      classifier = new NaiveBayes();
      String[] class_options = {""}; // enable/disable kernel -K
      ((NaiveBayes)classifier).setOptions(class_options);
    } else if (class_scheme.equals("NBTree")){
      classifier = new NBTree();
    } else if (class_scheme.equals("HiddenNaiveBayes")){
      classifier = new HNB();
    } else if (class_scheme.equals("DecisionTreeNaiveBayes")){
      classifier = new DTNB();
      ((DTNB)classifier).setOptions(weka.core.Utils.splitOptions(""));
    }
    else{
      throw new Exception("class_scheme " + class_scheme + " not recognized.");
    }
    //
    // Add classifer batch filtering
    //
    //http://weka.wikispaces.com/Use+Weka+in+your+Java+code#Attribute%20selection
    //
    //
    AttributeSelection filter = new AttributeSelection();  // package weka.filters.supervised.attribute!
    CfsSubsetEval attreval = new CfsSubsetEval();
    GreedyStepwise search = new GreedyStepwise();
    search.setSearchBackwards(true);
    filter.setEvaluator(attreval);
    filter.setSearch(search);
    filter.setInputFormat(data);
    // generate new data
    data = Filter.useFilter(data, filter);
    
    // Implement SMOTE Filter for testing purposes only!
    // weka.filters.Filter
      Filter incclass = new SMOTE();
      String[] class_options = {""}; // set somote features
      ((SMOTE)incclass).setOptions(class_options);
      incclass.setInputFormat(data);
      try
      {
            data = Filter.useFilter(data, incclass);
      }
      catch (IllegalArgumentException e)
      {
            System.out.println("Can not perform SMOTE: ");
            System.out.println(e);
      }
    //
    // Adjusting weights on datasets if they are very unbalanced.
    //
    SpreadSubsample fltbias = new SpreadSubsample();
    fltbias.setDistributionSpread(setDistributionSpreadFromXML); // setDistributionSpread = 5
    fltbias.setInputFormat(data);
    data = Filter.useFilter(data, fltbias);


    classifier.buildClassifier(data);

    Evaluation eval = new Evaluation(data); // set crossValidateModel = 10
    eval.crossValidateModel(classifier, data, crossValidateModelFromXML, new Random(1));

    StringBuffer printout = new StringBuffer();
    printout.append(eval.toSummaryString(true));
    printout.append("\n");
    printout.append(eval.toClassDetailsString());
    printout.append("\n");
    printout.append(eval.toMatrixString());
    printout.append("\n");
    printout.append(classifier.toString());


    conn = DriverManager.getConnection(databaseURL, username,
	password);
    // System.out.println(printout);

//    long model_id = SerializeJavaObjects_MySQL.writeJavaObject(conn,
//	classifier, modeltable);   


    String updatestmt = new String(
	"UPDATE "
	    + modeltable
	    + " SET printout = ?, model_data = ?, header = ?, classes = ? WHERE model_id = ?");
    // System.out.println(updatestmt);
    
    Instances header = new Instances(data, -1);
    
    //create the list of classifiers
    StringBuffer classes = new StringBuffer();
    
    for (int j = 0; j < header.classAttribute().numValues(); j++) {
      classes.append(header.classAttribute().value(j) + "\t");
    }

    pstmt = conn.prepareStatement(updatestmt);
    pstmt.setString(1, printout.toString());
    pstmt.setBytes(2, Byter.objToBytes(classifier));
    pstmt.setBytes(3, Byter.objToBytes(header));
    pstmt.setString(4, classes.toString());
    pstmt.setInt(5, model_id);
    pstmt.executeUpdate();

    // ////////////
    // Send Email
    // ///////////

    String[] to = { email };
    String subject = new String("MolClass model completed");
    String message = new String(
	"MolClass has completed creating your classification model. Details can be found at " + website + "/view_model_detail.php?model_id="
	    + model_id);
    String from = new String(molclassemail);
    // disable email for now.
    //postMail(to, subject, message, from);
    conn.close();
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
}
