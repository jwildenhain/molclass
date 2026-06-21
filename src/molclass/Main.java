/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package molclass;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author jw test
 */
public class Main {

    private static final Map<String, String> TOOL_CLASS_MAP;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("ModelBuilder", "molclass.ModelBuilder");
        map.put("Predictor", "molclass.Predictor");
        map.put("AutomaticCalcDriver", "molclass.descriptors.AutomaticCalcDriver");
        map.put("Fingerprinter", "molclass.fingerprints.Fingerprinter");
        map.put("Similarity", "molclass.fingerprints.Similarity");
        map.put("InChiGenerator", "molclass.fingerprints.InChiGenerator");
        map.put("MurckoFragments", "molclass.fingerprints.MurckoFragments");
        map.put("SdfImporter", "molclass.SdfImporter");
        map.put("SdfConverter", "molclass.SdfConverter");
        map.put("StructureSearch", "molclass.StructureSearch");
        map.put("BlobToBits", "molclass.BlobToBits");
        map.put("WekaDemo", "molclass.WekaDemo");
        map.put("CreateNewColumns", "molclass.descriptors.CreateNewColumns");
        map.put("DBConnectionTest", "molclass.descriptors.DBConnectionTest");
        map.put("FingerprintReader", "molclass.fingerprints.FingerprintReader");
        map.put("FromString", "molclass.fingerprints.FromString");
        map.put("TextFingerprinter", "molclass.fingerprints.TextFingerprinter");
        map.put("FingerprintRebuilder", "molclass.FingerprintRebuilder");
        map.put("XMLReader", "molclass.XMLReader");
        TOOL_CLASS_MAP = Collections.unmodifiableMap(map);
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        String classPath = System.getProperty("java.class.path");
        System.out.println("ClassPath: " + classPath);

        if (args.length < 1) {
            printUsage();
            return;
        }

        String tool = args[0];
        String targetClass = TOOL_CLASS_MAP.get(tool);

        // Allow direct fully-qualified class names.
        if (tool.contains(".")) {
            targetClass = tool;
        }

        if (targetClass == null) {
            targetClass = "molclass." + tool;
        }

        runTool(targetClass, args);
    }

    private static void runTool(String className, String[] args) {
        try {
            Class<?> target = Class.forName(className);
            Method main = target.getMethod("main", args.getClass());
            String[] innerArgs = new String[args.length - 1];
            System.arraycopy(args, 1, innerArgs, 0, innerArgs.length);
            main.invoke(null, new Object[]{innerArgs});
        } catch (IllegalArgumentException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchMethodException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SecurityException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
            System.err.println("Could not find class: " + className);
            System.err.println("Please use java -jar MolClass.jar <tool>");
            printUsage();
        } catch (IllegalAccessException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private static void printUsage() {
        System.out.println("\n");
        System.out.println("\tUsage: java -jar MolClass.jar <tool> <args>");
        for (String tool : TOOL_CLASS_MAP.keySet()) {
            System.out.println("\tUsage: java -jar MolClass.jar " + tool);
        }
        System.out.println("\n");
        System.out.println("Examples:");
        System.out.println("\tjava -jar MolClass.jar SdfImporter <sdf_target> <username> <email> <mol_type> <pmid> <info> <id>");
        System.out.println("\tjava -jar MolClass.jar ModelBuilder <model_id>");
        System.out.println("\tjava -jar MolClass.jar Predictor <pred_id>");
        System.out.println("\tjava -jar MolClass.jar AutomaticCalcDriver <batch_id>");
        System.out.println("\thint when in struggle: java -cp lib/cdk-2.12.jar:MolClass.jar descriptors.AutomaticCalcDriver <batch_id>");
        System.out.println("\n");
    }
}
