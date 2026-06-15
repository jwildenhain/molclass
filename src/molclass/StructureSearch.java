package molclass;

import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;
import org.openscience.cdk.inchi.InChIGeneratorFactory;
import org.openscience.cdk.inchi.InChIToStructure;
import org.openscience.cdk.fingerprint.MACCSFingerprinter;
import org.openscience.cdk.fingerprint.PubchemFingerprinter;
import org.openscience.cdk.fingerprint.KlekotaRothFingerprinter;
import org.openscience.cdk.similarity.Tanimoto;
import org.openscience.cdk.isomorphism.Pattern;
import descriptors.SDFReader;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class StructureSearch {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java molclass.StructureSearch <similarity|substructure> <smiles|inchi> <query_string> [maccs|pubchem|kr] [limit] [threshold]");
            System.exit(1);
        }

        String searchType = args[0].toLowerCase();
        String queryType = args[1].toLowerCase();
        String queryString = args[2];
        
        String fpType = "maccs";
        int limit = 100;
        double threshold = 0.8;

        if (args.length >= 4) {
            String val = args[3].toLowerCase();
            if (val.equals("maccs") || val.equals("pubchem") || val.equals("kr")) {
                fpType = val;
                if (args.length >= 5) limit = Integer.parseInt(args[4]);
                if (args.length >= 6) threshold = Double.parseDouble(args[5]);
            } else {
                // assume limit was passed
                limit = Integer.parseInt(args[3]);
                if (args.length >= 5) threshold = Double.parseDouble(args[4]);
            }
        }

        try {
            // 1. Parse the query molecule
            IAtomContainer queryMol = null;
            if (queryType.equals("smiles")) {
                SmilesParser sp = new SmilesParser(SilentChemObjectBuilder.getInstance());
                queryMol = sp.parseSmiles(queryString);
            } else if (queryType.equals("inchi")) {
                InChIGeneratorFactory factory = InChIGeneratorFactory.getInstance();
                InChIToStructure intostruct = factory.getInChIToStructure(queryString, SilentChemObjectBuilder.getInstance());
                queryMol = intostruct.getAtomContainer();
            } else {
                System.err.println("Error: query_type must be either 'smiles' or 'inchi'");
                System.exit(1);
            }

            if (queryMol == null) {
                System.err.println("Error: Failed to parse query structure");
                System.exit(1);
            }

            // 2. Generate target fingerprints for the query
            MACCSFingerprinter mfp = new MACCSFingerprinter();
            PubchemFingerprinter pcfp = new PubchemFingerprinter(SilentChemObjectBuilder.getInstance());
            KlekotaRothFingerprinter krfp = new KlekotaRothFingerprinter();

            BitSet queryMACCS = mfp.getBitFingerprint(queryMol).asBitSet();
            BitSet queryPubChem = pcfp.getBitFingerprint(queryMol).asBitSet();
            BitSet queryKR = krfp.getBitFingerprint(queryMol).asBitSet();

            // 3. Connect to database
            String hostname = XMLReader.getTag("hostname");
            String database = XMLReader.getTag("database");
            String user = XMLReader.getTag("ro_user");
            String password = XMLReader.getTag("ro_password");
            String fptable = XMLReader.getTag("fingerprinttable");
            String structable = XMLReader.getTag("molstructable");

            String databaseURL = "jdbc:mysql://" + hostname + "/" + database;
            try (Connection conn = DriverManager.getConnection(databaseURL, user, password)) {

                if (searchType.equals("similarity")) {
                    runSimilaritySearch(conn, fptable, fpType, queryPubChem, queryMACCS, queryKR, threshold, limit);
                } else if (searchType.equals("substructure")) {
                    runSubstructureSearch(conn, fptable, structable, queryMol, fpType, queryPubChem, queryMACCS, queryKR, limit);
                } else {
                    System.err.println("Error: search_type must be either 'similarity' or 'substructure'");
                    System.exit(1);
                }
            }

        } catch (Exception e) {
            System.err.println("Error running search: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runSimilaritySearch(Connection conn, String fptable, String fpType, BitSet queryPubChem, BitSet queryMACCS, BitSet queryKR, double threshold, int limit) throws Exception {
        // Query all fingerprints
        String sql = "SELECT mol_id, MACCS, PubChem, KR FROM " + fptable;
        List<SimilarityResult> results = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                int molId = rs.getInt("mol_id");
                String maccsStr = rs.getString("MACCS");
                String pubchemStr = rs.getString("PubChem");
                String krStr = rs.getString("KR");

                BitSet dbFp = null;
                BitSet queryFp = null;

                if (fpType.equals("maccs")) {
                    if (maccsStr == null) continue;
                    dbFp = bsFromString(maccsStr);
                    queryFp = queryMACCS;
                } else if (fpType.equals("pubchem")) {
                    if (pubchemStr == null) continue;
                    dbFp = bsFromString(pubchemStr);
                    queryFp = queryPubChem;
                } else {
                    if (krStr == null) continue;
                    dbFp = bsFromString(krStr);
                    queryFp = queryKR;
                }

                double score = calculateTanimoto(queryFp, dbFp);

                if (score >= threshold) {
                    results.add(new SimilarityResult(molId, score));
                }
            }
        }

        // Sort by similarity descending
        Collections.sort(results, new Comparator<SimilarityResult>() {
            public int compare(SimilarityResult o1, SimilarityResult o2) {
                return Double.compare(o2.similarity, o1.similarity);
            }
        });

        // Print output as JSON array
        System.out.print("[");
        int count = 0;
        for (SimilarityResult res : results) {
            if (count > 0) System.out.print(",");
            System.out.print("{\"mol_id\":" + res.molId + ",\"similarity\":" + String.format("%.4f", res.similarity) + "}");
            count++;
            if (count >= limit) break;
        }
        System.out.print("]\n");
    }

    private static void runSubstructureSearch(Connection conn, String fptable, String structable, IAtomContainer queryMol, String fpType, BitSet queryPubChem, BitSet queryMACCS, BitSet queryKR, int limit) throws Exception {
        // Step 1: Pre-screening candidates using fingerprints (subset check)
        String sql = "SELECT mol_id, MACCS, PubChem, KR FROM " + fptable;
        List<Integer> candidates = new ArrayList<>();

        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                int molId = rs.getInt("mol_id");
                String maccsStr = rs.getString("MACCS");
                String pubchemStr = rs.getString("PubChem");
                String krStr = rs.getString("KR");

                BitSet dbFp = null;
                BitSet queryFp = null;

                if (fpType.equals("maccs")) {
                    if (maccsStr == null) continue;
                    dbFp = bsFromString(maccsStr);
                    queryFp = queryMACCS;
                } else if (fpType.equals("pubchem")) {
                    if (pubchemStr == null) continue;
                    dbFp = bsFromString(pubchemStr);
                    queryFp = queryPubChem;
                } else {
                    if (krStr == null) continue;
                    dbFp = bsFromString(krStr);
                    queryFp = queryKR;
                }

                // A candidate must have all query bits set in the selected fingerprint
                if (isSubset(queryFp, dbFp)) {
                    candidates.add(molId);
                }
            }
        }

        // Step 2: Exact isomorphism test on candidates
        Pattern pattern = Pattern.findSubstructure(queryMol);
        SDFReader sr = new SDFReader();
        List<Integer> hits = new ArrayList<>();

        String strucSql = "SELECT struc FROM " + structable + " WHERE mol_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(strucSql)) {
            for (int molId : candidates) {
                pstmt.setInt(1, molId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        Blob strucBlob = rs.getBlob("struc");
                        byte[] bdata = strucBlob.getBytes(1, (int) strucBlob.length());
                        String sdf = new String(bdata);

                        try {
                            IAtomContainer candidateMol = sr.read(sdf);
                            if (candidateMol != null && pattern.matches(candidateMol)) {
                                hits.add(molId);
                                if (hits.size() >= limit) break;
                            }
                        } catch (Exception e) {
                            // ignore parsing errors on specific malformed structures
                        }
                    }
                }
            }
        }

        // Print output as JSON array
        System.out.print("[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) System.out.print(",");
            System.out.print("{\"mol_id\":" + hits.get(i) + "}");
        }
        System.out.print("]\n");
    }

    private static BitSet bsFromString(String string) {
        if (string == null || string.trim().isEmpty() || string.equals("{}")) {
            return new BitSet();
        }
        BitSet bs = new BitSet();
        String clean = string.replace("{", "").replace("}", "").trim();
        if (clean.isEmpty()) return bs;
        String[] parts = clean.split(",");
        for (String part : parts) {
            String val = part.trim();
            if (!val.isEmpty()) {
                bs.set(Integer.parseInt(val));
            }
        }
        return bs;
    }

    private static boolean isSubset(BitSet query, BitSet candidate) {
        BitSet temp = (BitSet) query.clone();
        temp.andNot(candidate);
        return temp.isEmpty();
    }

    private static double calculateTanimoto(BitSet bs1, BitSet bs2) {
        BitSet intersection = (BitSet) bs1.clone();
        intersection.and(bs2);
        int a = bs1.cardinality();
        int b = bs2.cardinality();
        int c = intersection.cardinality();
        if (a + b - c == 0) return 0.0;
        return (double) c / (a + b - c);
    }

    private static class SimilarityResult {
        int molId;
        double similarity;

        SimilarityResult(int molId, double similarity) {
            this.molId = molId;
            this.similarity = similarity;
        }
    }
}
