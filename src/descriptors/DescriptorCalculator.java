package descriptors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.qsar.*;
import org.openscience.cdk.modeling.builder3d.ModelBuilder3D;
import org.openscience.cdk.modeling.builder3d.TemplateHandler3D;

public class DescriptorCalculator {

	// This method sets up the master thread and sets initialized the worker pool
	public void execute(int batch_id, int workers) throws SQLException,
			InterruptedException {
		//Get necessary XML Data
		String host = XMLReader.getTag("hostname");
		String database = XMLReader.getTag("database");
		String user = XMLReader.getTag("rw_user");
		String password = XMLReader.getTag("rw_password");
		String cdktable = XMLReader.getTag("cdkdesctable");
		String structable = XMLReader.getTag("molstructable");
		String batchmoltable = XMLReader.getTag("batchmoltable");
                int setCDKdescriptortimeout = Integer.parseInt(XMLReader.getTag("setCDKdescriptortimeout"));
                int setThreadPoolTimeout = Integer.parseInt(XMLReader.getTag("setThreadPoolTimeout"));

		String hostname = new String("jdbc:mysql://" + host + "/" + database);

		String nstmt = "SELECT " + cdktable + ".mol_id FROM "
				+ cdktable + ", " + batchmoltable + " WHERE " + cdktable
				+ ".MW IS NULL AND " + batchmoltable + ".batch_id = ? AND "
				+ cdktable + ".mol_id = " + batchmoltable + ".mol_id";

		ExecutorService pool = Executors.newFixedThreadPool(workers);

		try (Connection con = DriverManager.getConnection(hostname, user, password);
		     PreparedStatement stmt = con.prepareStatement(nstmt, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
			stmt.setInt(1, batch_id);
			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					String mol_id = rs.getString("mol_id");
					TimeOutWrapper to = new TimeOutWrapper(new CalculationHandler(
							hostname, user, password, cdktable, structable, mol_id),
							setCDKdescriptortimeout, mol_id);
					pool.submit(to);
				}
			}
		}

		System.out.println("All Submitted.");
		pool.shutdown();
		pool.awaitTermination(setThreadPoolTimeout, TimeUnit.SECONDS);
		pool.shutdownNow();
		return;

	}

}
