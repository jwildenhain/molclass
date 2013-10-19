/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fingerprints;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openscience.cdk.DefaultChemObjectBuilder;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.exception.InvalidSmilesException;
import org.openscience.cdk.fragment.MurckoFragmenter;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmilesParser;


/**
 *
 * @author zahir
 */

class SingleMurckoCalcTask implements Callable<String> {
    
    private String mol_id = null;
    private String inchikeytable = null;
    private String structablename = null;
    private String batchmoltable = null;
    private String hostname = null;
    private String user = null;
    private String password = null;   
    
    SingleMurckoCalcTask(String hostname, String user, String password,
            String inchikeytable, String structablename, String batchmoltable, String mol_id) {
        this.mol_id = mol_id;
        this.inchikeytable = inchikeytable;
        this.structablename = structablename;
        this.batchmoltable = batchmoltable;
        this.hostname = hostname;
        this.user = user;
        this.password = password;
    }
    
    @Override
    public String call() throws Exception {
        //Thread.sleep(4000); // Just to demo a long running task of 4 seconds.
         String returnCallable = "";
                
	 Connection con = DriverManager.getConnection(hostname, user, password);

         String stmt1 = new String("SELECT " + inchikeytable + ".mol_id, " + inchikeytable + ".smiles FROM " + inchikeytable + ", " + batchmoltable + " WHERE " + batchmoltable + ".mol_id = " + inchikeytable + ".mol_id AND " + batchmoltable
				+ ".mol_id = ?");
         
         PreparedStatement stmt = con.prepareStatement(stmt1, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_UPDATABLE);
      
         stmt.setInt(1, Integer.parseInt(mol_id));           
         ResultSet rs1 = stmt.executeQuery();
        
         SmilesParser smilesParser = new SmilesParser(DefaultChemObjectBuilder.getInstance());
      
         while (rs1.next()) {

         String smiles = rs1.getString("smiles");
         
         int current_molid = rs1.getInt("mol_id");
    
         try {
             IAtomContainer molecule = smilesParser.parseSmiles(smiles);
      
             MurckoFragmenter mf = new MurckoFragmenter(true,2);
             try {      
                 mf.generateFragments(molecule);
             } catch (CDKException ex) {
                Logger.getLogger(TestMurckoFragments.class.getName()).log(Level.SEVERE, null, ex);
             }
      
             String[] murckoFrame = mf.getFrameworks();


             
             // if new murcko store it in murcko table
             //System.out.println("Fragments:");
             for (int i = 0; i <= murckoFrame.length - 1; i++) {
                    //System.out.println(murckoFrame[i]);
                    
                    // if murcko already exists count ++ and update
                    String stmt2 = "SELECT * FROM murcko WHERE smiles like '"
                                                     + murckoFrame[i] + "'";
                    PreparedStatement pstmt = con.prepareStatement(stmt2, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_UPDATABLE);
                    
                    ResultSet rs2 = pstmt.executeQuery();
                    if (rs2.next()) {
                        
                        int tmpcount = rs2.getInt("count") + 1;
                        Statement sqlstmt = con.createStatement();
                        String sqladdsmile = "update `murcko` set `count` = " + tmpcount + " where murcko_id = " + rs2.getInt("murcko_id");  
                        //System.out.println(sqladdsmile);
                        int updateCount = sqlstmt.executeUpdate(sqladdsmile);
                                
                    } else {
                        Statement sqlstmt = con.createStatement();
                        String sqladdsmile = "insert into `murcko` (`smiles`,`count`) VALUES ('" + murckoFrame[i] + "', 1)";  
                        //System.out.println(sqladdsmile);
                        int updateCount = sqlstmt.executeUpdate(sqladdsmile);
                        
                    }
             
                    // store murcko id and mol id in murcko_mol table
                    String stmt_mu_mol_link = "SELECT murcko_id FROM `murcko` WHERE `smiles` like '"
                                                     + murckoFrame[i] + "'";
                    PreparedStatement mumostmt = con.prepareStatement(stmt_mu_mol_link, ResultSet.TYPE_FORWARD_ONLY,
								ResultSet.CONCUR_UPDATABLE);
                    
                    ResultSet rs3 = mumostmt.executeQuery();
                    if (rs3.next()) {
                        Statement sqlstmt = con.createStatement();
                        String sqladdsmile = "insert ignore into `murcko_mol` (`murcko_id`,`mol_id`) VALUES (" + rs3.getInt("murcko_id") + "," + current_molid + ")";  
                        //System.out.println(sqladdsmile);
                        int updateCount = sqlstmt.executeUpdate(sqladdsmile);
          
                        
                    } else {
                        
                    }
   
             
                  returnCallable = mol_id + ":" +murckoFrame[i];  
             }
             

             
             /*
             String[] murckoRing = mf.getRingSystems();
             System.out.println("Rings:");
             for (int i = 0; i <= murckoRing.length - 1; i++) {
                    System.out.println("Ring: " + i + " " + murckoRing[i]);
             }
             */
        
         } catch (InvalidSmilesException ex) {
             Logger.getLogger(TestMurckoFragments.class.getName()).log(Level.SEVERE, null, ex);
         }
        
        
         }
         
        con.close();
        return (returnCallable);
    }
}
