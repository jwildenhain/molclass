package descriptors;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openscience.cdk.AtomContainer;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.qsar.descriptors.molecular.*;
import org.openscience.cdk.modeling.builder3d.ModelBuilder3D;
import org.openscience.cdk.modeling.builder3d.TemplateHandler3D;
import org.openscience.cdk.qsar.DescriptorEngine;
import org.openscience.cdk.qsar.DescriptorValue;
import org.openscience.cdk.qsar.result.*;

// This class does the actual calculation of the descriptors for a given mol_id
class CalculationHandler implements Runnable {

    private String mol_id = null;
    private String infotablename = null;
    private String structablename = null;
    private String hostname = null;
    private String user = null;
    private String password = null;
    static Lock srLock = new ReentrantLock();
    static Lock xLock = new ReentrantLock();

    // Obtain a connection from the HikariCP pool. The pool is keyed by the DB credentials.
    private static Connection getPooledConnection(String hostname, String user, String password) throws SQLException {
        return DBConnectionPool.getDataSource(hostname, user, password).getConnection();
    }

    CalculationHandler(String hostname, String user, String password,
            String infotablename, String structablename, String mol_id) {
        this.mol_id = mol_id;
        this.infotablename = infotablename;
        this.structablename = structablename;
        this.hostname = hostname;
        this.user = user;
        this.password = password;
    }

    public void run() {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        String sdf_structure = null;

        try {
            conn = getPooledConnection(hostname, user, password);
            // Disable auto‑commit for the duration of this molecule processing to reduce commit overhead.
            conn.setAutoCommit(false);
            String nstmt = "SELECT struc FROM " + structablename + " WHERE mol_id=?";
            pstmt = conn.prepareStatement(nstmt, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            pstmt.setString(1, mol_id);

            rs = pstmt.executeQuery();
            if (rs.next()) {
                Blob struc = rs.getBlob("struc");
                byte[] bdata = struc.getBytes(1, (int) struc.length());
                sdf_structure = new String(bdata);
            }
        } catch (SQLException e) {
            System.out.println("Error with SQL connection: " + e.getMessage());
            return;
        } catch (Exception e) {
            e.printStackTrace();
            return;
        } finally {
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        if (sdf_structure == null) {
            return;
        }

        IAtomContainer molecule = null;
        SDFReader sr = new SDFReader();

        srLock.lock();
        try {
            molecule = sr.read(sdf_structure);
        } catch (Exception e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
            return;
        } finally {
            srLock.unlock();
        }

        SaltStripper ss = new SaltStripper();
        molecule = ss.stripSalt(molecule);

        DescriptorEngine engine = new DescriptorEngine(
                org.openscience.cdk.qsar.IMolecularDescriptor.class, 
                org.openscience.cdk.silent.SilentChemObjectBuilder.getInstance()
        );

        List list = engine.getDescriptorClassNames();
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.IPMolecularLearningDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.KierHallSmartsDescriptor");
        // Remove 3D descriptors because the input SDF structures are 2D, which causes calculation failures and stderr warnings.
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.WHIMDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.VABCDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.MomentOfInertiaDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.LengthOverBreadthDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.GravitationalIndexDescriptor");
        list.remove("org.openscience.cdk.qsar.descriptors.molecular.CPSADescriptor");

        engine = new DescriptorEngine(list, org.openscience.cdk.silent.SilentChemObjectBuilder.getInstance());

        System.out.println("IN: " + mol_id);
        try {
            engine.process(molecule);
        } catch (CDKException e1) {
            System.out.println("CalcError: " + mol_id + ": " + e1.getMessage());
        }
        System.out.println("OUT: " + mol_id);

        Set descSet = molecule.getProperties().entrySet();
        Iterator itr = descSet.iterator();
        while (itr.hasNext()) {
            Map.Entry e = ((Map.Entry) itr.next());
            if (!(e.getValue() instanceof DescriptorValue)) {
                itr.remove();
            }
        }

        itr = descSet.iterator();
        Map<String, Object> updates = new HashMap<>();
        while (itr.hasNext()) {
            DescriptorValue desc = (DescriptorValue) ((Map.Entry) itr.next()).getValue();
            String[] names = desc.getNames();

            for (int n = 0; n < names.length; n++) {
                String name = names[n];
                name = name.replace('-', '_').replace('.', '_');

                if (name.equals("TPSA") && n == 0) {
                    name = "TopoPSA";
                }

                IDescriptorResult result = desc.getValue();
                if (result instanceof BooleanResult) {
                    updates.put(name, ((BooleanResult) result).booleanValue());
                } else if (result instanceof DoubleArrayResult) {
                    Double value = ((DoubleArrayResult) result).get(n);
                    if (!value.isNaN() && !value.isInfinite()) {
                        updates.put(name, value);
                    }
                } else if (result instanceof DoubleResult) {
                    Double value = ((DoubleResult) result).doubleValue();
                    if (!value.isNaN() && !value.isInfinite()) {
                        updates.put(name, value);
                    }
                } else if (result instanceof IntegerArrayResult) {
                    updates.put(name, ((IntegerArrayResult) result).get(n));
                } else if (result instanceof IntegerResult) {
                    updates.put(name, ((IntegerResult) result).intValue());
                }
            }
        }

        if (!updates.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE " + infotablename + " SET ");
            List<Object> values = new ArrayList<>();
            int count = 0;
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                if (count > 0) {
                    sql.append(", ");
                }
                sql.append(entry.getKey()).append("=?");
                values.add(entry.getValue());
                count++;
            }
            sql.append(" WHERE mol_id=?");

            PreparedStatement pstmtUpdate = null;
            try {
                pstmtUpdate = conn.prepareStatement(sql.toString());
                for (int i = 0; i < values.size(); i++) {
                    Object val = values.get(i);
                    if (val instanceof Boolean) {
                        pstmtUpdate.setBoolean(i + 1, (Boolean) val);
                    } else if (val instanceof Double) {
                        pstmtUpdate.setDouble(i + 1, (Double) val);
                    } else if (val instanceof Integer) {
                        pstmtUpdate.setInt(i + 1, (Integer) val);
                    }
                }
                pstmtUpdate.setInt(values.size() + 1, Integer.parseInt(mol_id));
                pstmtUpdate.addBatch();
                // Execute batch immediately for this single‑molecule task.
                pstmtUpdate.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                System.out.println("Error writing to database: " + e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    if (pstmtUpdate != null) {
                        pstmtUpdate.close();
                    }
                    if (conn != null) {
                        conn.close(); // Return to pool
                    }
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
