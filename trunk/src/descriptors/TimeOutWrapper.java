package descriptors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

// This TimeoutWrapper was added to solve a problem where CDk would take forever calculating descriptors for certain molecules. CDK did not implement a good way to terminate
// these jobs, so this is an inelegant solution to the problem, but the only one I could come up with at the time. It uses the deprecated method thread.stop() to stop any
// thread which has run for too long.
public class TimeOutWrapper implements Runnable {

	Runnable runnable;
	long timeout;
	String mol_id;
	String host = XMLReader.getTag("hostname");
	String database = XMLReader.getTag("database");
	String user = XMLReader.getTag("rw_user");
	String password = XMLReader.getTag("rw_password");
	String timeouttable = XMLReader.getTag("timeouttable");
	String hostname = new String("jdbc:mysql://" + host + "/" + database);

	//timeout = time to wait in milliseconds.
	public TimeOutWrapper(Runnable runnable, long timeout, String mol_id) {
		this.runnable = runnable;
		this.timeout = timeout;
		this.mol_id = mol_id;
	}

	public void run() {
		Thread thread = new Thread(runnable);
		thread.start();

		// Wait for the thread to terminate, up to timeout
		try {
			thread.join(timeout);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// If the thread is still running after timeout, terminate it with thread.stop
		// yes, I know thread.stop is deprecated and it is a terrible sin to use
		// it but I can't figure out how to solve this problem
		//
		//The mol_ids for molecules which timeout are written to the timeouttable so used for debugging CDK
		if (thread.isAlive()) {
			thread.stop();
			System.out.println("timeout: " + mol_id);

			try {
				Connection con = DriverManager.getConnection(hostname, user,
						password);
				String stmt = new String("INSERT INTO " + timeouttable
						+ "(mol_id) VALUES (?)");
				PreparedStatement pstmt = con.prepareStatement(stmt);
				pstmt.setInt(1, new Integer(mol_id));
				pstmt.executeUpdate();

			} catch (SQLException e) {
				System.out.println("SQL Error");
				e.printStackTrace();
			}
		}

		return;
	}

}
