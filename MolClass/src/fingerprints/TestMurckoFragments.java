/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fingerprints;

import java.util.Iterator;
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
 * @author jan wildenhain
 */
public class TestMurckoFragments {
    
    /**
	 * @param args
	 * @throws Exception
	 */
    public static void main(String[] args) throws Exception {
        
        SmilesParser smilesParser = new SmilesParser(DefaultChemObjectBuilder.getInstance());
      
        //String smiles = "COc1ccc2cc(ccc2(c1))C(C)C(O)=O";
        String smiles = "COc2cc1ccccc1cc2CCC3CCNC3";
        
    
        try {
            IAtomContainer molecule = smilesParser.parseSmiles(smiles);
      
            MurckoFragmenter mf = new MurckoFragmenter(true,2);
            try {      
                mf.generateFragments(molecule);
            } catch (CDKException ex) {
                Logger.getLogger(TestMurckoFragments.class.getName()).log(Level.SEVERE, null, ex);
            }
      
            String[] murckoFrame = mf.getFrameworks();
            String[] murckoRing = mf.getRingSystems();

            /*Iterator it = murckos.iterator();
            while(it.hasNext()){
                System.out.println(it.next());
            }
            */ 
            System.out.println("Fragments:");
            for (int i = 0; i <= murckoFrame.length - 1; i++) {
                    System.out.println(murckoFrame[i]);
            }
            
            System.out.println("Rings:");
            for (int i = 0; i <= murckoRing.length - 1; i++) {
                    System.out.println("Ring: " + i + " " + murckoRing[i]);
            }
        
        } catch (InvalidSmilesException ex) {
            Logger.getLogger(TestMurckoFragments.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        
       
    }
    
    

            
}
