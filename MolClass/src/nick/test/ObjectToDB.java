package nick.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ObjectToDB {
  public static long writeJavaObject(Connection conn, Object object, String tablename) throws Exception {
    String className = object.getClass().getName();
    
    String write_string = new String ("INSERT INTO " + tablename + "(name, model_data) VALUES (?, ?)");
    PreparedStatement pstmt = conn.prepareStatement(write_string);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	ObjectOutputStream oout = new ObjectOutputStream(baos);
	oout.writeObject(object);
	oout.close();
    
    // set input parameters
    pstmt.setString(1, className);
    pstmt.setBytes(2, baos.toByteArray());
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
    byte[] buf = rs.getBytes(1);
    
    Object object = null;
    
    if (buf != null) {
	ObjectInputStream objectIn = new ObjectInputStream(
			new ByteArrayInputStream(buf));
	object = objectIn.readObject();
    }


    String className = object.getClass().getName();
    System.out.println(className);

    rs.close();
    pstmt.close();
   // System.out.println("readJavaObject: done de-serializing: " + className);
    return object;
  }
}
