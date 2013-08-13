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
import org.openscience.cdk.Molecule;
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
    // static Lock lock3d = new ReentrantLock();
    static Lock xLock = new ReentrantLock();

    // static int x = 1;
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
        try {
            Connection conn = null;
            PreparedStatement pstmt = null;
            Molecule molecule = new Molecule();
            ResultSet rs = null;
            String sdf_structure = null;

            // // Read sdf file and convert to Molecule. Ensure connectivity.
            SDFReader sr = new SDFReader();

            try {

                conn = DriverManager.getConnection(hostname, user, password);

                String nstmt = new String("SELECT struc FROM " + structablename
                        + " WHERE mol_id=?");
                pstmt = conn.prepareStatement(nstmt, ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_UPDATABLE);
                pstmt.setString(1, mol_id);


                rs = pstmt.executeQuery();

                rs.next();
                // convert from blob into string
                Blob struc = rs.getBlob("struc");
                byte[] bdata = struc.getBytes(1, (int) struc.length());
                sdf_structure = new String(bdata);

                nstmt = new String("SELECT * FROM " + infotablename
                        + " WHERE mol_id=?");
                pstmt = conn.prepareStatement(nstmt, ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_UPDATABLE);
                pstmt.setString(1, mol_id);

                rs = pstmt.executeQuery();

                rs.next();
            } catch (SQLException e) {
              //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Error with SQL connection", e);
                System.out.println("Error with SQL connection: "
                        + e.getMessage());
                return;
            } catch (Exception e) {
              //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Unknow Error", e);
                e.printStackTrace();
                return;
            }

            // Converts string of SDF data into Molecule. Needs lock because
            // SDFReader is not thread-safe.
            srLock.lock();
            try {
                molecule = sr.read(sdf_structure);

            } catch (Exception e) {
              //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Unknown error", e);
                System.out.println(e.getMessage());
                e.printStackTrace();
                return;
            } finally {
                srLock.unlock();
            }


            // Use SaltStripper to ensure the molecule is connected (no
            // unconnected atoms)
            SaltStripper ss = new SaltStripper();
            molecule = ss.stripSalt(molecule);

            // 3D coordinates disabled because does not work
            // TemplateHandler3D template = TemplateHandler3D.getInstance();
            // ModelBuilder3D mb3d = ModelBuilder3D.getInstance(template,"mm2");
            // molecule = (Molecule) mb3d.generate3DCoordinates(molecule, true);

            // this is the "DescriptorEngine" which calculates the descriptors
            DescriptorEngine engine = new DescriptorEngine(
                    DescriptorEngine.MOLECULAR);

            // Get the list of descriptors cdk will calculate
            List list = engine.getDescriptorClassNames();
            // These descriptors have to be removed because they cause CDK to
            // fail for various reasons. The first 3 fail because they need 3D
            // coordinates, not sure about the others.
            //Logger.getLogger(this.getClass().getName()).log(Level.INFO, "mol_id" + mol_id + " number of descriptors to calc : " + list.size());

            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.LengthOverBreadthDescriptor");
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.TaeAminoAcidDescriptor");
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.GravitationalIndexDescriptor"); // these
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.MomentOfInertiaDescriptor");
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.PetitjeanShapeIndexDescriptor"); // 1 num should work jw
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.WHIMDescriptor");
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.IPMolecularDescriptor");
            // those crash with very large molecules like Cyanocobalamin and Lanatoside_C:
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.WeightedPathDescriptor"); // 1# fails oca. jw
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.FMFDescriptor"); // Jan cdk1.5 crashes MurckoFragm.
            //list.remove("org.openscience.cdk.qsar.descriptors.molecular.HybridizationRatioDescriptor"); // jw new!
            //terribly slow and crash very often:
            list.remove("org.openscience.cdk.qsar.descriptors.molecular.IPMolecularLearningDescriptor"); // Jan CDKver1.5 very slow/buggy
            list.remove("org.openscience.cdk.qsar.descriptors.molecular.KierHallSmartsDescriptor"); // Jan new CDK version

            //Logger.getLogger(this.getClass().getName()).log(Level.INFO, "mol_id" + mol_id + " cleaned up number of descriptors to calc : " + list.size());

            engine = new DescriptorEngine(list);


            // Calculate descriptors and convert to set
            System.out.println("IN: " + mol_id);
            //Logger.getLogger(this.getClass().getName()).log(Level.INFO, "IN:" + mol_id);
            try {
                engine.process(molecule);
            } catch (CDKException e1) {
              //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "CDK Erro", e1);
                System.out.println("CalcError: " + mol_id + ": "
                        + e1.getMessage());
            }
            //Logger.getLogger(this.getClass().getName()).log(Level.INFO, "OUT:" + mol_id);
            System.out.println("OUT: " + mol_id);
            // System.out.println("CalcError: " + mol_id + ": " +
            // e.getMessage());
            // return;
            // }


            // Get set of Molecules properties
            Set descSet = molecule.getProperties().entrySet();

            // Filter out properties which are not DescriptorValues (the
            // molecules will have some properties which are not descriptors).
            Iterator itr = descSet.iterator();
            while (itr.hasNext()) {
                Map.Entry e = ((Map.Entry) itr.next());
//                Logger.getLogger(this.getClass().getName()).log(Level.INFO, "mol_id" + mol_id + " | " + e.getKey().toString() + " -> " + e.getValue().toString());
                if (!(e.getValue() instanceof DescriptorValue)) {
                    itr.remove();
                }
            }

            itr = descSet.iterator();

            // Iterate over the set of calculated descriptors. We have to test
            // what type it is (int, double, array), and write it to the
            // database in a different way depending on this.
            while (itr.hasNext()) {
                DescriptorValue desc = (DescriptorValue) ((Map.Entry) itr.next()).getValue();
                String[] names = desc.getNames();

                try {
                    rs.first();

                    for (int n = 0; n < names.length; n++) {
                        String name = names[n];
                        name = name.replace('-', '_'); // MySQL does not allow
                        // "-"
                        name = name.replace('.', '_'); // MySQL does not allow
                        // "."

                        if ((name.compareTo("TPSA") == 0) && (n == 0)) // rename
                        // sigular
                        // tpsa
                        {
                            name = new String("TopoPSA");
                        }

                        IDescriptorResult result = desc.getValue();

                        if (result instanceof BooleanResult) {
                            BooleanResult res = (BooleanResult) result;

                            boolean value = res.booleanValue();
                            rs.updateBoolean(name, value);
                        } else if (result instanceof DoubleArrayResult) {
                            DoubleArrayResult res = (DoubleArrayResult) result;

                            Double value = res.get(n);

                            if (value.isNaN()) {
                                continue;
                            } else if (value.isInfinite()) {
                                continue;
                            } else {
                                rs.updateDouble(name, value);
                            }
                        } else if (result instanceof DoubleResult) {
                            DoubleResult res = (DoubleResult) result;

                            Double value = res.doubleValue();
                            if (value.isNaN()) {
                                continue;
                            } else if (value.isInfinite()) {
                                continue;
                            } else {
                                rs.updateDouble(name, value);
                            }
                        } else if (result instanceof IntegerArrayResult) {
                            IntegerArrayResult res = (IntegerArrayResult) result;

                            Integer value = res.get(n);

                            rs.updateInt(name, value);
                        } else if (result instanceof IntegerResult) {
                            IntegerResult res = (IntegerResult) result;

                            Integer value = res.intValue();

                            rs.updateInt(name, value);
                        }
                    }
                    rs.updateRow();
                } catch (SQLException e) {
                    System.out.println("Error writing to database: "
                            + e.getMessage());
                  //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "sql error", e);
                } catch (Exception e) {
                  //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Unknown error", e);
                    e.printStackTrace();
                    return;
                }
            }

            try {
                if (conn != null) {
                    conn.close();
                }
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
              //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "sql error", e);
                e.printStackTrace();
            } catch (Exception e) {
              //  e.printStackTrace();
                Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Unknown error", e);
                return;
            }


            return;
        } catch (Exception e) {
          //  Logger.getLogger(this.getClass().getName()).log(Level.SEVERE, "Unknown error", e);
            e.printStackTrace();
            return;
        }
    }
}
