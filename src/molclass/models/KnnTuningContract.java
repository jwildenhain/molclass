package molclass.models;

/** Produces a valid, bounded odd-K search grid for Weka IBk cross-validation. */
final class KnnTuningContract {
    private static final int MAX_FOLDS = 10;
    private static final int MAX_K = 25;

    private KnnTuningContract() {
    }

    static Plan forTrainingInstances(int trainingInstances) {
        if (trainingInstances < 1) {
            throw new IllegalArgumentException("KNN tuning requires at least one training instance");
        }
        int folds = Math.min(MAX_FOLDS, trainingInstances);
        if (folds < 2) {
            return new Plan(folds, 0, 1, 1, false);
        }

        int largestValidationFold = (int) Math.ceil(trainingInstances / (double) folds);
        int smallestCvTrainingFold = trainingInstances - largestValidationFold;
        int upper = Math.min(MAX_K, Math.max(1, smallestCvTrainingFold));
        if ((upper & 1) == 0) {
            upper--;
        }
        boolean tuned = upper > 1;
        int steps = tuned ? (upper + 1) / 2 : 1;
        return new Plan(folds, smallestCvTrainingFold, upper, steps, tuned);
    }

    record Plan(int folds, int smallestCvTrainingFold, int upper, int steps, boolean tuned) {
        Plan {
            if (folds < 1 || smallestCvTrainingFold < 0 || upper < 1
                    || (upper & 1) == 0 || steps < 1) {
                throw new IllegalArgumentException("invalid KNN tuning plan");
            }
            if (tuned && (upper > smallestCvTrainingFold || steps != (upper + 1) / 2)) {
                throw new IllegalArgumentException("unsafe KNN tuning plan");
            }
            if (!tuned && (upper != 1 || steps != 1)) {
                throw new IllegalArgumentException("invalid direct KNN plan");
            }
        }

        String parameter() {
            if (!tuned) {
                throw new IllegalStateException("direct K=1 plan has no CV parameter");
            }
            return "K 1 " + upper + " " + steps;
        }
    }
}
