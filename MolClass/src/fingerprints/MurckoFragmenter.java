/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fingerprints;

/**
 *
 * @author zahir
 */
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;

public class MurckoFragmenter {
    public static void main(String[] args) throws Exception {
        
         int batch_id = new Integer(1);
         if (args.length != 1)
         {
		System.out.println("Usage: java -jar MolClass.jar MurckoFragments <batch_id>");
                System.out.println("...... Running test with batch_id = " + batch_id);
	 } else {
                batch_id = new Integer(args[0]);
                System.out.println("...... Running test with batch_id = " + batch_id);
         }

	 String host = XMLReader.getTag("hostname");
	 String database = XMLReader.getTag("database");
	 String user = XMLReader.getTag("rw_user");
	 String password = XMLReader.getTag("rw_password");
	 String inchikeytable = XMLReader.getTag("inchikeytable");
	 String structablename = XMLReader.getTag("molstructable");
	 String batchmoltable = XMLReader.getTag("batchmoltable");
         int setCDKdescriptortimeout = Integer.parseInt(XMLReader.getTag("setCDKdescriptortimeout"));

	 String hostname = "jdbc:mysql://" + host + "/" + database;    
          
         Connection con = DriverManager.getConnection(hostname, user, password);

         
         String stmt1 = new String("SELECT " + inchikeytable + ".mol_id, " + inchikeytable + ".smiles FROM " + inchikeytable + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + inchikeytable + ".mol_id AND " + batchmoltable
				+ ".batch_id = ?");
         
         PreparedStatement stmt = con.prepareStatement(stmt1, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_UPDATABLE);
         stmt.setInt(1, batch_id);           
         ResultSet rs1 = stmt.executeQuery();
        
         SmilesParser smilesParser = new SmilesParser(DefaultChemObjectBuilder.getInstance());
      
         int it = 1;
         while (rs1.next()) 
          {
     
              String mol_id = rs1.getString("mol_id");
        
              ExecutorService executor = Executors.newSingleThreadExecutor();
              //ExecutorService executor = Executors.newFixedThreadPool(2);
              
              Future<String> future = executor.submit(new SingleMurckoCalcTask(hostname,user,password,inchikeytable,structablename,batchmoltable,mol_id));

              try {
                    // System.out.println("Started..");   
                     System.out.println(future.get(setCDKdescriptortimeout, TimeUnit.MILLISECONDS));
                    // System.out.println("Finished!");
              } catch (TimeoutException e) {
                    System.out.println(mol_id + ":Terminated!");
              }

              executor.shutdownNow();
          }
         rs1.close();
         con.close();
        }
}
