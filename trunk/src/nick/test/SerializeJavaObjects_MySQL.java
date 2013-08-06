package nick.test;
/*
 * mysql> CREATE TABLE class_models ( 
 * model_id INT AUTO_INCREMENT, 
 * name varchar(128), 
 * model_data LONGBLOB,
 * username VARCHAR(20),
 * batch_id INT(11),
 * data_type VARCHAR(10),
 * class_tag VARCHAR(20),
 * class_scheme VARCHAR(30),
 * printout TEXT, 
 * primary key (model_id));
 **/

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import weka.classifiers.Classifier;
import weka.classifiers.trees.*;
import weka.classifiers.rules.*;

public class SerializeJavaObjects_MySQL {


  public static long writeJavaObject(Connection conn, Object object, String tablename) throws Exception {
    String className = object.getClass().getName();
    
    String write_string = new String ("INSERT INTO " + tablename + "(name, model_data) VALUES (?, ?)");
    
    PreparedStatement pstmt = conn.prepareStatement(write_string);

    // set input parameters
    pstmt.setString(1, className);
    pstmt.setObject(2, object);
    pstmt.executeUpdate();

    // get the generated key for the id
    ResultSet rs = pstmt.getGeneratedKeys();
    int id = -1;
    if (rs.next()) {
      id = rs.getInt(1);
    }

    rs.close();
    pstmt.close();
    //System.out.println("writeJavaObject: done serializing: " + className);
    return id;
  }

  public static Object readJavaObject(Connection conn, long id, String tablename) throws Exception {
    String read_string = new String("SELECT model_data FROM " + tablename + " WHERE model_id = ?");
    
    PreparedStatement pstmt = conn.prepareStatement(read_string);
    pstmt.setLong(1, id);
    ResultSet rs = pstmt.executeQuery();
    rs.next();
    Object object = rs.getObject(1);
    String className = object.getClass().getName();
    System.out.println(className);

    rs.close();
    pstmt.close();
   // System.out.println("readJavaObject: done de-serializing: " + className);
    return object;
  }
}