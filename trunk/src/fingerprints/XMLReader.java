package fingerprints;

import java.io.File;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

public class XMLReader {

  public static String getTag(String tag) {
    
    String ret = null;
    
    try {
      DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
      Document doc = docBuilder.parse(new File("molclass.conf.xml"));
      
   // normalize text representation
      doc.getDocumentElement ().normalize ();
      
      NodeList nodeLst = doc.getElementsByTagName(tag);
      Node node = nodeLst.item(0);
      Element element = (Element) node;
      NodeList item = element.getChildNodes();
      
      
      ret = ((Node) (item.item(0))).getNodeValue();
      
      
    } catch (Exception e) {
      e.printStackTrace();
    }
    
    return ret;
    
  }
}
