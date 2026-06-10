package descriptors;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

public class XMLReader {
  private static final Map<String, String> cache = new ConcurrentHashMap<>();
  private static volatile Document doc = null;
  private static final Object lock = new Object();

  private static Document getDocument() {
    Document localDoc = doc;
    if (localDoc != null) {
      return localDoc;
    }
    synchronized (lock) {
      if (doc == null) {
        try {
          DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
          DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
          doc = docBuilder.parse(new File("molclass.conf.xml"));
          doc.getDocumentElement().normalize();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      return doc;
    }
  }

  public static String getTag(String tag) {
    if (tag == null) {
      return null;
    }
    String cachedVal = cache.get(tag);
    if (cachedVal != null) {
      return cachedVal;
    }
    
    Document d = getDocument();
    if (d == null) {
      return null;
    }
    
    try {
      NodeList nodeLst = d.getElementsByTagName(tag);
      if (nodeLst != null && nodeLst.getLength() > 0) {
        Node node = nodeLst.item(0);
        if (node instanceof Element) {
          Element element = (Element) node;
          NodeList item = element.getChildNodes();
          if (item != null && item.getLength() > 0) {
            Node child = item.item(0);
            if (child != null) {
              String val = child.getNodeValue();
              if (val != null) {
                String trimmed = val.trim();
                cache.put(tag, trimmed);
                return trimmed;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }
}
