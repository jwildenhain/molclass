package descriptors;

import java.sql.SQLException;

import descriptors.XMLReader;

public class AutomaticCalcDriver {

	//This method is the main method for CDK Descriptor calculation.
	public static void main(String[] args) throws SQLException, InterruptedException {

                int batch_id = new Integer(1);
                if (args.length != 1)
		{
			System.out.println("Usage: java -jar MolClass.jar:lib/* AutomaticCalcDriver <batch_id>");
                        System.out.println("...... Running test with batch_id = " + batch_id);

	
		} else {

                        batch_id = new Integer(args[0]);
                        System.out.println("...... Running test with batch_id = " + batch_id);
                }
	    
		DescriptorCalculator dc = new DescriptorCalculator();
		
		//Calculate descriptors for batch_id, with 4 worker threads.
		dc.execute(batch_id, 4);
	}

}
