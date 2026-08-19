package molclass.descriptors;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * XMLReader parses the project's configuration file (molclass.conf.xml)
 * and provides tag values via {@code getTag}. It loads the XML once
 * on first use and caches values in a map for fast subsequent lookups.
 * This implementation is deliberately lightweight and avoids external
 * dependencies, making it suitable for unit‑test environments.
 */
public class XMLReader {
    // Cache of tag -> value
    private static final Map<String, String> TAGS = new HashMap<>();
    // Flag to ensure the config is loaded only once
    private static volatile boolean initialized = false;

    private static void init() {
        if (initialized) {
            return;
        }
        synchronized (XMLReader.class) {
            if (initialized) {
                return;
            }
            try {
                // The configuration file resides at the project root.
                // Resolve relative to the classpath location.
                File configFile = new File("molclass.conf.xml");
                if (!configFile.exists()) {
                    // Fall back to looking next to the executing jar/class.
                    configFile = new File(System.getProperty("user.dir"), "molclass.conf.xml");
                }
                if (!configFile.exists()) {
                    // If still not found, leave tags empty.
                    initialized = true;
                    return;
                }
                DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                Document doc = dBuilder.parse(configFile);
                doc.getDocumentElement().normalize();
                // Load all direct child elements of <config>
                NodeList nodes = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < nodes.getLength(); i++) {
                    if (nodes.item(i) instanceof org.w3c.dom.Element) {
                        org.w3c.dom.Element elem = (org.w3c.dom.Element) nodes.item(i);
                        String tagName = elem.getTagName();
                        String text = elem.getTextContent().trim();
                        TAGS.put(tagName, text);
                    }
                }
            } catch (Exception e) {
                // Silently ignore parsing errors; calls will return empty strings.
                e.printStackTrace();
            } finally {
                initialized = true;
            }
        }
    }

    /**
     * Returns the text content of the requested tag from the configuration.
     * If the tag does not exist or the file cannot be read, an empty string
     * is returned, preserving the previous placeholder behaviour.
     */
    public static String getTag(String tag) {
        init();
        return TAGS.getOrDefault(tag, "");
    }
}
