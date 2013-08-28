/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package molclass;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author jw test
 */
public class Main {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // TODO code application logic here

        //Get the System Classloader
        ClassLoader sysClassLoader = ClassLoader.getSystemClassLoader();

        //Get the URLs
        URL[] urls = ((URLClassLoader)sysClassLoader).getURLs();


        System.out.println("ClassPath ( size : " + urls.length +"): ");
        for(int i=0; i< urls.length; i++)
        {
            System.out.println(urls[i].getFile());
        }

        if (args.length < 1)
        {
                System.out.println("\n");
		System.out.println("\tUsage: java -jar MolClass.jar AutomaticCalcDriver <batch_id>");
		System.out.println("\tUsage: java -jar MolClass.jar Fingerprinter <batch_id>");
		System.out.println("\tUsage: java -jar MolClass.jar Similarity <batch_id>");
                System.out.println("\tUsage: java -jar MolClass.jar MurckoFragments <batch_id>");
		System.out.println("\tUsage: java -jar MolClass.jar ModelBuilder <model_id>");
		System.out.println("\tUsage: java -jar MolClass.jar Predictor <pred_id>");
                System.out.println("\n");
                System.out.println("Hint when in struggle:");
                System.out.println("java -cp lib/cdk-1.4.18.jar:MolClass.jar descriptors.AutomaticCalcDriver <batch_id>");
                

	}
        else
        {

               if (args[0].equals("ModelBuilder") || args[0].equals("Predictor"))
               {
                        String base = "nick.test.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});
            
                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }

               if (args[0].equals("AutomaticCalcDriver"))
               {
                        String base = "descriptors.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});

                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }

               if (args[0].equals("Fingerprinter"))
               {
                        String base = "fingerprints.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});

                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }

               if (args[0].equals("InChiGenerator"))
               {
                        String base = "fingerprints.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});

                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }
               
               if (args[0].equals("Similarity"))
               {
                        String base = "fingerprints.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});

                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }
               
               if (args[0].equals("MurckoFragments"))
               {
                        String base = "fingerprints.";
                        Class target = null;

                        try {

                                target = Class.forName(base + args[0]);
                                Method main = target.getMethod("main", args.getClass());
                                String[] innerargs = new String[args.length -1];
                                System.arraycopy(args, 1, innerargs, 0, innerargs.length);
                                main.invoke(null, new Object[]{innerargs});

                            } catch (IllegalArgumentException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (InvocationTargetException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (NoSuchMethodException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (SecurityException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (ClassNotFoundException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            } catch (IllegalAccessException ex) {
                                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
                            }
               }
        }
    }
}
