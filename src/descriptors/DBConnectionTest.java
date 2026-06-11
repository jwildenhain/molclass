package descriptors;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Diagnostic utility to verify that the application can successfully establish
 * a connection to the MySQL database using the credentials defined in molclass.conf.xml.
 */
public class DBConnectionTest {
    public static void main(String[] args) {
        try {
            String host = XMLReader.getTag("hostname");
            String database = XMLReader.getTag("database");
            String user = XMLReader.getTag("rw_user");
            String pass = XMLReader.getTag("rw_password");

            if (host == null || database == null || user == null) {
                System.err.println("[error] Missing database configuration in molclass.conf.xml");
                System.exit(1);
            }

            String url = "jdbc:mysql://" + host + "/" + database;
            System.out.println("[deploy] Verifying database connectivity to: " + url + " as " + user + "...");

            // Load the MySQL driver class
            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection conn = DriverManager.getConnection(url, user, pass);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    System.out.println("[deploy] Database connection verification successful.");
                    System.exit(0);
                }
            }
        } catch (ClassNotFoundException e) {
            // Fallback to older driver name if modern driver is not available
            try {
                Class.forName("com.mysql.jdbc.Driver");
                String host = XMLReader.getTag("hostname");
                String database = XMLReader.getTag("database");
                String user = XMLReader.getTag("rw_user");
                String pass = XMLReader.getTag("rw_password");
                String url = "jdbc:mysql://" + host + "/" + database;
                try (Connection conn = DriverManager.getConnection(url, user, pass);
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    if (rs.next()) {
                        System.out.println("[deploy] Database connection verification successful (legacy driver).");
                        System.exit(0);
                    }
                }
            } catch (Exception ex) {
                System.err.println("[error] Database connection failed: " + ex.getMessage());
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("[error] Database connection failed: " + e.getMessage());
            System.exit(1);
        }
        System.exit(1);
    }
}
