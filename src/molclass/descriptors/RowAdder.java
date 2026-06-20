package descriptors;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedList;

import org.openscience.cdk.qsar.result.BooleanResult;
import org.openscience.cdk.qsar.result.DoubleArrayResult;
import org.openscience.cdk.qsar.result.DoubleResult;
import org.openscience.cdk.qsar.result.IDescriptorResult;
import org.openscience.cdk.qsar.result.IntegerArrayResult;
import org.openscience.cdk.qsar.result.IntegerResult;

public class RowAdder {

	public void rowCheck(String name, IDescriptorResult result,
			Connection conn, String infotablename) throws SQLException {

		PreparedStatement pstmt = conn
				.prepareStatement("SHOW COLUMNS FROM " + infotablename + " WHERE Field = '"
						+ name + "'");
		ResultSet rs = pstmt.executeQuery();
		LinkedList<String> notAdded = new LinkedList<String>();

		int x = 0;
		
		if (rs.isLast()) {
			try {
				if (result instanceof BooleanResult) {
					pstmt = conn
							.prepareStatement("ALTER TABLE " + infotablename + " add "
									+ name + " BOOL");
					pstmt.executeUpdate();
					System.out.println(x + " New column " + name + " added.");
				} else if (result instanceof DoubleArrayResult) {
					pstmt = conn
							.prepareStatement("ALTER TABLE " + infotablename + " add "
									+ name + " DOUBLE");
					pstmt.executeUpdate();
					System.out.println(x + " New column " + name + " added.");
				} else if (result instanceof DoubleResult) {
					pstmt = conn
							.prepareStatement("ALTER TABLE " + infotablename + " add "
									+ name + " DOUBLE");
					pstmt.executeUpdate();
					System.out.println(x + " New column " + name + " added.");
				} else if (result instanceof IntegerArrayResult) {
					pstmt = conn
							.prepareStatement("ALTER TABLE " + infotablename + " add "
									+ name + " INT(5)");
					pstmt.executeUpdate();
					System.out.println(x + " New column " + name + " added.");
				} else if (result instanceof IntegerResult) {
					pstmt = conn
							.prepareStatement("ALTER TABLE " + infotablename + " add "
									+ name + " INT(5)");
					pstmt.executeUpdate();
					System.out.println(x + " New column " + name + " added.");
				}
			} catch (SQLException e) {
				System.out.println(x + e.getMessage());
				notAdded.add(name);
			} finally {
				x++;
			}
		}
		System.out.println("Did not add: ");
		Iterator<String> itr2 = notAdded.iterator();
		while(itr2.hasNext())
		{
			System.out.println(itr2.next());
		}
		
	}
}
