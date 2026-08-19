package molclass.models;

import weka.attributeSelection.CfsSubsetEval;
import weka.core.Instances;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.supervised.attribute.Discretize;

/**
 * CFS evaluator that avoids Weka's display-label collision without changing
 * supervised cut points or the original instances reduced downstream.
 */
public final class RobustCfsSubsetEval extends CfsSubsetEval {
    private static final long serialVersionUID = 1L;

    public static final String CONTRACT_VERSION =
            "CFS_SUPERVISED_BIN_NUMBER_LABELS_V1";

    @Override
    public void buildEvaluator(Instances data) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("CFS training data is required");
        }
        if (data.classIndex() < 0 || !data.classAttribute().isNominal()) {
            throw new UnsupportedOperationException(
                    "robust CFS discretization requires a nominal class");
        }
        getCapabilities().testWithFail(data);
        super.buildEvaluator(discretizeEvaluationCopy(data));
    }

    static Instances discretizeEvaluationCopy(Instances source)
            throws Exception {
        Instances evaluationCopy = new Instances(source);
        evaluationCopy.deleteWithMissingClass();
        Discretize discretizer = createEvaluationDiscretizer();
        discretizer.setInputFormat(evaluationCopy);
        return Filter.useFilter(evaluationCopy, discretizer);
    }

    static Discretize createEvaluationDiscretizer() {
        Discretize discretizer = new Discretize();
        // CfsSubsetEval uses better encoding; -Y changes labels only.
        discretizer.setUseBetterEncoding(true);
        discretizer.setUseBinNumbers(true);
        return discretizer;
    }

    static String evaluationDiscretizerClass() {
        return Discretize.class.getName();
    }

    static String evaluationDiscretizerOptions() {
        return Utils.joinOptions(createEvaluationDiscretizer().getOptions()).trim();
    }
}
