package nick.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public class Byter {

  static byte[] objToBytes(Object object) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ObjectOutputStream oout = new ObjectOutputStream(baos);
    oout.writeObject(object);
    oout.close();
    
    byte[] bytes = baos.toByteArray();
    
    return bytes;

  }
  
  static Object bytesToObj(byte[] bytes) throws IOException, ClassNotFoundException{
    
    Object object = null;
    
    if (bytes != null) {
	ObjectInputStream objectIn = new ObjectInputStream(
			new ByteArrayInputStream(bytes));
	object = objectIn.readObject();
    }
    
    return object;
  }

}
