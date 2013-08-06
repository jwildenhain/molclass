package nick.test;

import java.io.UnsupportedEncodingException;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.Vector;

import weka.core.*;
import weka.core.Capabilities.*;
import weka.filters.*;

//this filter is used for converting the string representation of the fingerprints which is stored on the database into seperate attributes
public class BlobToBits extends SimpleBatchFilter {

	protected int colToChange = 1;
	protected int numNewCols = 2400;

	public String globalInfo() {
		return "A batch filter which converts MySQL Blobs which contain molecular fingerprints into a set of attributes, each a binary bit corresponding to a bit in the fingerprint.";
	}

	// set capabilities
	public Capabilities getCapabilities() {
		Capabilities result = super.getCapabilities();
		result.enableAllAttributes();
		result.enableAllClasses();
		result.enable(Capability.NO_CLASS);
		result.enable(Capability.MISSING_CLASS_VALUES);
		result.enable(Capability.MISSING_VALUES);
		return result;
	}

	// Create options. -R specifies column index to change, -C specifies number
	// of new columns to add
	public Enumeration listOptions() {
		Vector newVector = new Vector(4);

		newVector.addElement(new Option(
				"\tSpecify column indexe to convert.\n", "R", 1, "-R <index>"));
		newVector.addElement(new Option(
				"\tSpecify number of new columns to be added.\n", "C", 1,
				"-C <numNew>"));

		return newVector.elements();
	}

	public void setOptions(String[] options) throws Exception {

		String column = Utils.getOption('R', options);
		if (column.length() != 0) {
			colToChange = new Integer(column);
		}

		String newCols = Utils.getOption('C', options);
		if (newCols.length() != 0) {
			numNewCols = new Integer(newCols);
		}

		if (getInputFormat() != null) {
			setInputFormat(getInputFormat());
		}
	}

	public String[] getOptions() {

		String[] options = new String[4];
		int current = 0;

		if (!(colToChange == -1)) {
			options[current++] = "-R";
			options[current++] = new Integer(colToChange).toString();
		}
		if (!(numNewCols == -1)) {
			options[current++] = "-C";
			options[current++] = new Integer(numNewCols).toString();
		}

		while (current < options.length) {
			options[current++] = "";
		}
		return options;
	}

	// adds the new columns
	protected Instances determineOutputFormat(Instances inputFormat) {
		Instances outputFormat = new Instances(inputFormat, 0);

		// Instance inst = inputFormat.firstInstance();

		String name = inputFormat.attribute(colToChange - 1).name();

		for (int x = 0; x < numNewCols; x++) {
			outputFormat.insertAttributeAt(new Attribute(name + "_" + x),
					outputFormat.numAttributes());
		}

		return outputFormat;
	}

	// processes the filter. Splits the specified column from a string
	// representation of a bitset into diffent columns, each with a value equal
	// to presence or no
	protected Instances process(Instances inst)
			throws UnsupportedEncodingException {

		Instances output = new Instances(determineOutputFormat(inst), 0);
		for (int i = 0; i < getInputFormat().numInstances(); i++) {
			double[] values = new double[output.numAttributes()];
			for (int n = 0; n < inst.numAttributes(); n++)
				values[n] = inst.instance(i).value(n);

			int first = inst.numAttributes();

			int column = colToChange - 1;
			// int[] splitCols = {1};

			int numNewCols = getNumBits(inst.instance(i).stringValue(column));
			// int numNewCols = 1024;
			// BitSet bs = bitSetFromString(inst.instance(i).stringValue(j));
			BitSet bs = bsFromString(inst.instance(i).stringValue(column));
			// System.out.println(bs.toString());//TODO: STRANGE OUTPUT - WRONG
			// FP
			for (int ind = first; ind - first < (bs.length()); ind++) {
				if (bs.get(ind - first))
					values[ind] = 1;
				else
					values[ind] = 0;
			}
			output.add(new DenseInstance(1, values));
		}

		return output;
	}

	@Override
	public String getRevision() {
		return RevisionUtils.extract("$Revision: 1.0 $");
	}

	public void setColToChange(int col) {
		colToChange = col;
	}

	public int getColToChange() {
		return colToChange;
	}

	public void setNumNewCols(int col) {
		numNewCols = col;
	}

	public int getNumNewCols() {
		return numNewCols;
	}

	private int getNumBits(String val) {
		BitSet bs = fromByteArray(val.getBytes());

		return bs.length();
	}

	private static BitSet bitSetFromString(String bins) {
		String s;
		char c;
		BitSet b;

		StringBuffer sb = new StringBuffer();

		for (int i = 0; i < bins.length(); ++i) {
			c = bins.charAt(i);
			if (c == '0' || c == '1') {
				sb.append(c);
			}
		}
		s = new String(sb);
		b = new BitSet(s.length());
		for (int i = 0; i < s.length(); ++i) {
			if (s.charAt(i) == '1') {
				b.set(i);
			} else {
				b.clear(i);
			}
		}
		return b;
	}

	private static BitSet fromByteArray(byte[] bytes) {
		BitSet bits = new BitSet();
		for (int i = 0; i < bytes.length * 8; i++) {
			if ((bytes[bytes.length - i / 8 - 1] & (1 << (i % 8))) > 0) {
				bits.set(i);
			}
		}
		return bits;
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

	public static void main(String[] args) {
		runFilter(new BlobToBits(), args);
	}
}
