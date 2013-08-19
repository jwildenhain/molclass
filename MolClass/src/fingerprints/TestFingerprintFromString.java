package fingerprints;

import java.util.BitSet;
import java.util.Scanner;
import org.openscience.cdk.similarity.Tanimoto;

public class TestFingerprintFromString {

	public static void main(String[] args) {
		BitSet bs1 = bsFromString("{21, 49, 65, 75, 94, 98, 104, 105, 106, 107, 108, 109, 112, 122, 126, 142, 144, 145, 146, 147, 148, 149, 153, 155, 156, 157, 158, 160, 161, 162, 163, 164}");
		BitSet bs2 = bsFromString("{1, 9, 65, 75, 94, 98, 104, 105, 106, 107, 108, 109, 112, 122, 126, 142, 144, 145, 146, 147, 148, 149, 153, 155, 156, 157, 158, 160, 161, 162, 163, 164}");
		//System.out.println(bs.toString());
                Double score = calculateSimilarity(bs1,bs2);
                System.out.println(score);
	}

	private static BitSet bsFromString(String string) {
		String s = new String(string);
		BitSet bs = new BitSet();
		
		s = s.replace('{', ' ');
		s = s.replace('}', ' ');
		s = s.replace(',', ' ');

		Scanner scan = new Scanner(s);

		while (scan.hasNextInt()) {
			int index = scan.nextInt();
			bs.set(index);
		}

		return bs;
	}
        
        private static double calculateSimilarity(BitSet fp1, BitSet fp2) {
		
		double score = Double.MIN_VALUE;
		if (fp1 != null && fp2 != null) {
			try {
				score = Tanimoto.calculate(fp1, fp2);
			} catch (Exception e) {
				score = 0.0;
			}
		}
		if (score == Double.MIN_VALUE) score = 0.0;

		return score;
	}
        
}