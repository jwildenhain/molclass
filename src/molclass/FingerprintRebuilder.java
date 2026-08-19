package molclass;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.BitSet;

import org.openscience.cdk.fingerprint.*;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.silent.SilentChemObjectBuilder;

/**
 * Utility to recompute fingerprint bitsets for a given range of molecule IDs
 * and store them in the {@code fingerprints} table. This is used only for
 * test‑environment preparation.
 */
public class FingerprintRebuilder {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: FingerprintRebuilder <jdbcUrl> <user> <password> [startId] [endId]");
            System.exit(1);
        }
        String url = args[0];
        String user = args[1];
        String pass = args[2];
        int start = 1;
        int end = 5;
        if (args.length >= 5) {
            start = Integer.parseInt(args[3]);
            end = Integer.parseInt(args[4]);
        }
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            String selectSql = "SELECT mol_id, struc FROM moldb_molstruc WHERE mol_id BETWEEN ? AND ?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setInt(1, start);
                ps.setInt(2, end);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int molId = rs.getInt("mol_id");
                        String sdf = rs.getString("struc");
                        IAtomContainer mol = new SDFReader().read(sdf);
                        // Compute all fingerprints used by the application
                        MACCSFingerprinter maccs = new MACCSFingerprinter();
                        ExtendedFingerprinter ext = new ExtendedFingerprinter();
                        PubchemFingerprinter pub = new PubchemFingerprinter(SilentChemObjectBuilder.getInstance());
                        GraphOnlyFingerprinter gofp = new GraphOnlyFingerprinter();
                        SubstructureFingerprinter sub = new SubstructureFingerprinter();
                        KlekotaRothFingerprinter kr = new KlekotaRothFingerprinter();
                        EStateFingerprinter es = new EStateFingerprinter();

                        String updateSql = "UPDATE fingerprints SET MACCS = ?, EXT = ?, PubChem = ?, GOFP = ?, SUB = ?, KR = ?, ESFP = ? WHERE mol_id = ?";
                        try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                            update.setString(1, bitSetToString(maccs.getBitFingerprint(mol).asBitSet()));
                            update.setString(2, bitSetToString(ext.getBitFingerprint(mol).asBitSet()));
                            update.setString(3, bitSetToString(pub.getBitFingerprint(mol).asBitSet()));
                            update.setString(4, bitSetToString(gofp.getBitFingerprint(mol).asBitSet()));
                            update.setString(5, bitSetToString(sub.getBitFingerprint(mol).asBitSet()));
                            update.setString(6, bitSetToString(kr.getBitFingerprint(mol).asBitSet()));
                            update.setString(7, bitSetToString(es.getBitFingerprint(mol).asBitSet()));
                            update.setInt(8, molId);
                            update.executeUpdate();
                        }
                        System.out.println("Recomputed fingerprints for mol_id=" + molId);
                    }
                }
            }
        }
    }

    private static String bitSetToString(BitSet bs) {
        if (bs == null || bs.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            if (!first) sb.append(',');
            sb.append(i);
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
