package molclass.models;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import weka.attributeSelection.ASEvaluation;
import weka.attributeSelection.ASSearch;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.attributeSelection.Ranker;
import weka.attributeSelection.ReliefFAttributeEval;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.OptionHandler;
import weka.core.SparseInstance;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.supervised.instance.SpreadSubsample;

/** Definition-scoped, deterministic Weka 3.8.7 rebuild worker for migrated v3 definitions. */
public final class V3ModelRebuilder {
    private static final String GENERATION_LABEL = "v3-cdk-2.12-weka-3.8.7-stratified-gzip-v1";
    private static final String CDK_VERSION = "2.12";
    private static final String WEKA_VERSION = "3.8.7";
    private static final long DEFAULT_SEED = 20260814L;
    static final int SMALL_SPLIT_MINIMUM_PER_CLASS = 10;
    static final int MAX_CROSS_VALIDATION_FOLDS = 10;
    static final String EVALUATION_CONTRACT = "WEKA_WEIGHTED_AGGREGATES_V2";
    static final String ROBUST_CFS_CONTRACT =
            RobustCfsSubsetEval.CONTRACT_VERSION;
    static final String RELIEFF_RANK_ALL_CONTRACT = "RELIEFF_RANK_ALL_V1";
    static final long PARALLEL_CFS_MODEL_DEFINITION_ID = 19L;
    static final String PARALLEL_CFS_CONTRACT =
            "CFS_SUBSET_GREEDY_WEKA_3_8_7_PARALLEL_CANDIDATES_V1";
    static final String STOCK_SMOTE_IMPLEMENTATION =
            "weka.filters.supervised.instance.SMOTE:1.0.3-revision-8108";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern MANIFEST_NAME = Pattern.compile(
            "\\\"name\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"");

    private V3ModelRebuilder() { }

    // Bootstrap-aggregated J48: meaningfully different from RandomForest's bagging of
    // random-feature-subset trees, since J48 considers the full feature set at each split.
    // 100 iterations to match RandomForest's scale for a fair comparison.
    static weka.classifiers.meta.Bagging createBaggingClassifier(int threads) {
        weka.classifiers.meta.Bagging classifier = new weka.classifiers.meta.Bagging();
        classifier.setClassifier(new weka.classifiers.trees.J48());
        classifier.setNumIterations(100);
        classifier.setSeed(1);
        classifier.setNumExecutionSlots(threads);
        return classifier;
    }

    // Boosted J48 via classic AdaBoost.M1. 50 iterations (Weka's own default is 10;
    // scikit-learn's AdaBoost default is 50) -- a full J48 tree is already a stronger learner
    // than boosting's traditional weak-learner assumption expects, so this stays well short of
    // RandomForest/Bagging's 100 to bound overfitting risk on the smaller QSAR datasets here.
    static weka.classifiers.meta.AdaBoostM1 createAdaBoostM1Classifier() {
        weka.classifiers.meta.AdaBoostM1 classifier = new weka.classifiers.meta.AdaBoostM1();
        classifier.setClassifier(new weka.classifiers.trees.J48());
        classifier.setNumIterations(50);
        classifier.setSeed(1);
        return classifier;
    }

    static weka.classifiers.meta.StackingC createEnsemble2Classifier() throws Exception {
        weka.classifiers.trees.J48 tree = new weka.classifiers.trees.J48();
        tree.setOptions(Utils.splitOptions("-C 0.25 -M 2"));

        weka.classifiers.functions.Logistic logistic = new weka.classifiers.functions.Logistic();
        logistic.setOptions(Utils.splitOptions("-R 1.0E-8 -M -1"));

        weka.classifiers.functions.MultilayerPerceptron neuralNet =
                new weka.classifiers.functions.MultilayerPerceptron();
        neuralNet.setOptions(Utils.splitOptions(
                "-L 0.3 -M 0.2 -N 500 -V 0 -S 0 -E 20 -H a"));

        weka.classifiers.bayes.NaiveBayes naiveBayes =
                new weka.classifiers.bayes.NaiveBayes();

        weka.classifiers.rules.OneR oneRule = new weka.classifiers.rules.OneR();
        oneRule.setOptions(Utils.splitOptions("-B 6"));

        weka.classifiers.functions.SMO supportVector = new weka.classifiers.functions.SMO();
        supportVector.setOptions(Utils.splitOptions(
                "-C 1.0 -L 0.001 -P 1.0E-12 -N 0 -V -1 -W 1 "
                + "-K \"weka.classifiers.functions.supportVector.PolyKernel "
                + "-C 250007 -E 1.0\""));

        weka.classifiers.lazy.IBk nearestNeighbour = new weka.classifiers.lazy.IBk();
        nearestNeighbour.setOptions(Utils.splitOptions(
                "-K 1 -W 0 -A \"weka.core.neighboursearch.LinearNNSearch "
                + "-A \\\"weka.core.EuclideanDistance -R first-last\\\"\""));

        weka.classifiers.functions.LinearRegression meta =
                new weka.classifiers.functions.LinearRegression();
        meta.setOptions(Utils.splitOptions("-S 1 -R 1.0E-8"));

        weka.classifiers.meta.StackingC classifier = new weka.classifiers.meta.StackingC();
        classifier.setNumFolds(10);
        classifier.setSeed(1);
        classifier.setNumExecutionSlots(1);
        classifier.setClassifiers(new Classifier[]{
                tree, logistic, neuralNet, naiveBayes, oneRule, supportVector, nearestNeighbour
        });
        classifier.setMetaClassifier(meta);
        return classifier;
    }

    record FeatureSelection(
            String code,
            String contractVersion,
            ASEvaluation evaluator,
            ASSearch search,
            String evaluatorClass,
            String evaluatorOptions,
            String searchClass,
            String searchOptions) {
        String algorithmContractFragment() {
            String result = "featureSelection=" + code
                    + "; featureSelectionContract=" + contractVersion
                    + "; evaluatorClass=" + evaluatorClass
                    + "; evaluatorOptions=\"" + evaluatorOptions + "\""
                    + "; searchClass=" + searchClass
                    + "; searchOptions=\"" + searchOptions + "\"";
            if (evaluator instanceof RobustCfsSubsetEval) {
                result += "; evaluationDiscretizerClass="
                        + RobustCfsSubsetEval.evaluationDiscretizerClass()
                        + "; evaluationDiscretizerOptions=\""
                        + RobustCfsSubsetEval.evaluationDiscretizerOptions()
                        + "\"";
            }
            return result;
        }

        String manifestJson() {
            String result = "{\"code\":" + quote(code)
                    + ",\"contractVersion\":" + quote(contractVersion)
                    + ",\"evaluatorClass\":" + quote(evaluatorClass)
                    + ",\"evaluatorOptions\":" + quote(evaluatorOptions)
                    + ",\"searchClass\":" + quote(searchClass)
                    + ",\"searchOptions\":" + quote(searchOptions);
            if (evaluator instanceof RobustCfsSubsetEval) {
                result += ",\"evaluationDiscretizerClass\":"
                        + quote(RobustCfsSubsetEval.evaluationDiscretizerClass())
                        + ",\"evaluationDiscretizerOptions\":"
                        + quote(RobustCfsSubsetEval.evaluationDiscretizerOptions());
            }
            return result + "}";
        }
    }

    static ReliefFAttributeEval createReliefFAttributeEvaluator() {
        ReliefFAttributeEval evaluator = new ReliefFAttributeEval();
        evaluator.setSampleSize(-1);
        evaluator.setSeed(1);
        evaluator.setNumNeighbours(10);
        evaluator.setWeightByDistance(false);
        try {
            evaluator.setSigma(2);
        } catch (Exception exception) {
            throw new IllegalStateException("Weka rejected the ReliefF sigma contract", exception);
        }
        return evaluator;
    }

    static Ranker createReliefFRanker() {
        Ranker search = new Ranker();
        try {
            search.setStartSet("");
        } catch (Exception exception) {
            throw new IllegalStateException("Weka rejected the empty ReliefF start set", exception);
        }
        search.setThreshold(-Double.MAX_VALUE);
        search.setNumToSelect(-1);
        search.setGenerateRanking(true);
        return search;
    }

    static FeatureSelection featureSelection(String selection) {
        return featureSelectionForModel(0L, selection, 1, true);
    }

    static FeatureSelection featureSelectionForModel(
            long modelDefinitionId,
            String selection,
            int configuredThreads,
            boolean nominalClass) {
        String code = selection == null ? "" : selection.trim();
        if ("NONE".equalsIgnoreCase(code)) {
            return new FeatureSelection("None", "NONE_V1", null, null, "", "", "", "");
        }
        ASEvaluation evaluator;
        ASSearch search;
        String contractVersion;
        String canonicalCode;
        if ("ReliefFAttributeEval".equalsIgnoreCase(code)) {
            evaluator = createReliefFAttributeEvaluator();
            search = createReliefFRanker();
            contractVersion = RELIEFF_RANK_ALL_CONTRACT;
            canonicalCode = "ReliefFAttributeEval";
        } else if ("CfsSubsetEval".equalsIgnoreCase(code)) {
            GreedyStepwise greedy = new GreedyStepwise();
            contractVersion = "CFS_SUBSET_GREEDY_V1";
            if (usesRobustCfs(modelDefinitionId)) {
                if (!nominalClass) {
                    throw new UnsupportedOperationException(
                            "definitions 103/104 robust CFS requires a nominal class");
                }
                if (configuredThreads < 1 || configuredThreads > 64) {
                    throw new IllegalArgumentException(
                            "CFS threads must be between 1 and 64");
                }
                evaluator = new RobustCfsSubsetEval();
                greedy.setNumExecutionSlots(configuredThreads);
                contractVersion = ROBUST_CFS_CONTRACT;
            } else {
                evaluator = new CfsSubsetEval();
            }
            if (modelDefinitionId == PARALLEL_CFS_MODEL_DEFINITION_ID) {
                if (!nominalClass) {
                    throw new UnsupportedOperationException(
                            "definition 19 deterministic parallel CFS requires a nominal class");
                }
                if (configuredThreads < 1 || configuredThreads > 64) {
                    throw new IllegalArgumentException(
                            "CFS threads must be between 1 and 64");
                }
                greedy.setNumExecutionSlots(configuredThreads);
                contractVersion = PARALLEL_CFS_CONTRACT;
            } else if (!usesRobustCfs(modelDefinitionId)) {
                contractVersion = "CFS_SUBSET_GREEDY_V1";
            }
            search = greedy;
            canonicalCode = "CfsSubsetEval";
        } else {
            throw new UnsupportedOperationException("unknown feature selection " + selection);
        }
        return new FeatureSelection(
                canonicalCode,
                contractVersion,
                evaluator,
                search,
                evaluator.getClass().getName(),
                resolvedOptions((OptionHandler) evaluator),
                search.getClass().getName(),
                resolvedOptions((OptionHandler) search));
    }

    static boolean usesRobustCfs(long modelDefinitionId) {
        return modelDefinitionId == 103L || modelDefinitionId == 104L;
    }

    static FilteredClassifier commonTrainingPipeline(
            Classifier base, String selection, long seed) {
        return commonTrainingPipeline(base, featureSelection(selection), seed);
    }

    private static FilteredClassifier commonTrainingPipeline(
            Classifier base, FeatureSelection selection, long seed) {
        return commonTrainingPipeline(base, selection, seed, new SMOTE());
    }

    private static FilteredClassifier commonTrainingPipeline(
            Classifier base, FeatureSelection selection, long seed, SMOTE smote) {
        Classifier selected = base;
        if (selection.evaluator() != null) {
            AttributeSelectedClassifier wrapper = new AttributeSelectedClassifier();
            wrapper.setClassifier(base);
            wrapper.setEvaluator(selection.evaluator());
            wrapper.setSearch(selection.search());
            selected = wrapper;
        }
        SpreadSubsample spread = new SpreadSubsample();
        spread.setDistributionSpread(5.0);
        spread.setRandomSeed((int) seed);
        smote.setRandomSeed((int) seed);
        MultiFilter filters = new MultiFilter();
        filters.setFilters(new Filter[]{spread, smote});
        FilteredClassifier result = new FilteredClassifier();
        result.setFilter(filters);
        result.setClassifier(selected);
        return result;
    }

    static String definitionStatusPredicate(boolean explicitModel) {
        return explicitModel
                ? "md.status IN ('PENDING_REBUILD','REBUILD_FAILED','UNSUPPORTED_CONFIGURATION')"
                : "md.status='PENDING_REBUILD'";
    }

    static boolean stillEligible(String status, boolean explicitModel) {
        return "PENDING_REBUILD".equals(status)
                || explicitModel && ("REBUILD_FAILED".equals(status)
                || "UNSUPPORTED_CONFIGURATION".equals(status));
    }

    static boolean eligibleAfterDefinitionLock(String currentStatus, boolean explicitModel) {
        return stillEligible(currentStatus, explicitModel);
    }

    static String definitionLockName(String schema, long modelDefinitionId) {
        validate(schema);
        if (modelDefinitionId <= 0) {
            throw new IllegalArgumentException("model definition id must be positive");
        }
        String prefix = "molclass-v3-model-rebuild:";
        String readable = prefix + schema + ":" + modelDefinitionId;
        if (readable.length() <= 64) {
            return readable;
        }
        byte[] digest = sha((schema + ":" + modelDefinitionId)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder bounded = new StringBuilder(prefix);
        for (int index = 0; index < 16; index++) {
            int value = digest[index] & 0xff;
            bounded.append(Character.forDigit(value >>> 4, 16));
            bounded.append(Character.forDigit(value & 0x0f, 16));
        }
        return bounded.toString();
    }

    static String activeDefinitionMessage(long modelDefinitionId) {
        return "model definition " + modelDefinitionId
                + " is already active in another rebuild worker";
    }

    static List<String> interruptedRecoverySql(String schema) {
        String builds = qualifiedTable(schema, "model_build");
        String jobs = qualifiedTable(schema, "job");
        String definitions = qualifiedTable(schema, "model_definition");
        String audits = qualifiedTable(schema, "audit_event");
        return List.of(
                "INSERT INTO " + audits
                        + " (actor,action_code,entity_type,entity_id,event_details_json,created_at)"
                        + " SELECT 'model-rebuild-worker','MODEL_BUILD_INTERRUPTED','MODEL_BUILD',"
                        + " CAST(model_build_id AS CHAR),JSON_OBJECT('reason','worker restarted while build was RUNNING'),NOW(6)"
                        + " FROM " + builds
                        + " WHERE model_definition_id=? AND status='RUNNING'",
                "UPDATE " + jobs + " j JOIN " + builds + " mb ON mb.job_id=j.job_id"
                        + " SET j.status='FAILED',j.runstep='COMPLETE',j.error_code='WORKER_INTERRUPTED',"
                        + " j.error_message='worker restarted while model build was RUNNING',j.finished_at=NOW(6)"
                        + " WHERE mb.model_definition_id=? AND mb.status='RUNNING' AND j.status='RUNNING'",
                "UPDATE " + definitions + " md JOIN " + builds
                        + " mb ON mb.model_definition_id=md.model_definition_id"
                        + " SET md.status='REBUILD_FAILED'"
                        + " WHERE mb.model_definition_id=? AND mb.status='RUNNING'"
                        + " AND md.status='PENDING_REBUILD'",
                "UPDATE " + builds
                        + " SET status='INTERRUPTED',runstep='FAILED',error_code='WORKER_INTERRUPTED',"
                        + " error_message='worker restarted while model build was RUNNING',finished_at=NOW(6)"
                        + " WHERE model_definition_id=? AND status='RUNNING'");
    }

    public static void main(String[] args) {
        try {
            Config config = Config.parse(args, System.getenv());
            try (Connection read = V3JdbcSession.configureUtc(DriverManager.getConnection(
                        config.jdbcUrl, config.user, config.password));
                 Connection write = V3JdbcSession.configureUtc(DriverManager.getConnection(
                        config.jdbcUrl, config.user, config.password))) {
                read.setAutoCommit(true);
                write.setAutoCommit(false);
                new Worker(config, read, write).run();
            }
        } catch (Exception exception) {
            System.err.println("Model rebuild failed: " + rootMessage(exception));
            System.exit(1);
        }
    }

    enum SplitStrategy { HASH, SCAFFOLD }
    static final String HASH_SPLIT_STRATEGY_NAME = "STRATIFIED_HASH_80_10_10_V1";
    static final String SCAFFOLD_SPLIT_STRATEGY_NAME = "STRATIFIED_SCAFFOLD_80_10_10_V1";
    static String splitStrategyName(SplitStrategy strategy) {
        return strategy == SplitStrategy.SCAFFOLD ? SCAFFOLD_SPLIT_STRATEGY_NAME : HASH_SPLIT_STRATEGY_NAME;
    }

    private record Config(String jdbcUrl, String user, String password, String schema,
                          Long modelDefinitionId, Integer limit, long seed, int threads,
                          SplitStrategy splitStrategy, long parallelSmoteMinInstances) {
        static Config parse(String[] args, Map<String, String> environment) {
            Map<String, String> values = new LinkedHashMap<>();
            Set<String> allowed = Set.of("jdbc-url", "db-user", "password-env",
                    "target-schema", "model-id", "limit", "seed", "threads",
                    "split-strategy", "parallel-smote-min-instances");
            for (int index = 0; index < args.length; index++) {
                if ("--help".equals(args[index])) {
                    usage();
                    System.exit(0);
                }
                if (!args[index].startsWith("--")) throw new IllegalArgumentException(args[index]);
                String key = args[index].substring(2);
                if (!allowed.contains(key) || index + 1 == args.length) {
                    throw new IllegalArgumentException("invalid option --" + key);
                }
                values.put(key, args[++index]);
            }
            String jdbc = first(values.get("jdbc-url"), environment.get("MOLCLASS_JDBC_URL"));
            String user = first(values.get("db-user"), environment.get("MOLCLASS_DB_USER"));
            String passwordName = values.getOrDefault("password-env", "MOLCLASS_DB_PASSWORD");
            String password = environment.get(passwordName);
            if (jdbc == null || user == null || password == null) {
                throw new IllegalArgumentException("database environment is incomplete");
            }
            String schema = values.getOrDefault("target-schema", "molclass_v3");
            validate(schema);
            Long modelId = values.containsKey("model-id")
                    ? Long.valueOf(values.get("model-id")) : null;
            Integer limit = values.containsKey("limit")
                    ? Integer.valueOf(values.get("limit")) : null;
            long seed = values.containsKey("seed") ? Long.parseLong(values.get("seed")) : DEFAULT_SEED;
            String threadValue=first(values.get("threads"),environment.get("MOLCLASS_MODEL_THREADS"));
            int threads=threadValue==null?Math.min(8,Runtime.getRuntime().availableProcessors()):Integer.parseInt(threadValue);
            if(threads<1||threads>64)throw new IllegalArgumentException("threads must be between 1 and 64");
            // Default HASH preserves today's exact split behavior for every existing/automated
            // rebuild -- SCAFFOLD is opt-in only, never silently changed underneath a caller.
            SplitStrategy splitStrategy = SplitStrategy.valueOf(
                    values.getOrDefault("split-strategy", "HASH").toUpperCase(Locale.ROOT));
            long parallelSmoteMinInstances = values.containsKey("parallel-smote-min-instances")
                    ? Long.parseLong(values.get("parallel-smote-min-instances")) : 5000L;
            if (parallelSmoteMinInstances < 0) {
                throw new IllegalArgumentException("parallel-smote-min-instances must not be negative");
            }
            return new Config(jdbc, user, password, schema, modelId, limit, seed, threads,
                    splitStrategy, parallelSmoteMinInstances);
        }
    }

    private static void usage() {
        System.out.println("./gradlew rebuildV3Models -PmodelArgs=\"--model-id ID --limit N --seed N "
                + "--split-strategy HASH|SCAFFOLD --parallel-smote-min-instances N\"");
    }

    private record Definition(long id, long datasetId, long targetPropertyId,
                              String targetColumn, String modelName, String algorithm,
                              String featureSelection, String declaredClasses,
                              long featureProfileId, String definitionStatus,
                              String featureStatus) { }
    private record Component(int order, Long descriptorId, Long fingerprintId,
                             String code, int bitLength, List<String> descriptorNames) { }
    private record Excluded(long datasetMoleculeId, String reason) { }
    private record PreparedData(Instances train, Instances validation, Instances holdout,
                                List<Long> trainIds, List<Long> validationIds,
                                List<Long> holdoutIds, List<Excluded> excluded,
                                Map<String, Long> classSupport, List<String> scaffoldFallbackLabels) { }

    /** A single splittable unit for {@link #assignPartitions}, stripped of any Weka/DB dependency. */
    record PartitionCandidate(long id, String groupKey, long score) { }
    /** Which ids landed in VALIDATION/HOLDOUT; everything else is TRAIN by exclusion. */
    record PartitionAssignment(Set<Long> validationIds, Set<Long> holdoutIds) { }

    // Below this many distinct scaffold groups, whole-group assignment can't approximate an
    // 80/10/10 split meaningfully (e.g. one scaffold dominating a class, or the class itself
    // being tiny) -- callers should fall back to the per-instance HASH split for that label
    // rather than call assignPartitions with input too coarse to stratify.
    static final int MIN_DISTINCT_SCAFFOLD_GROUPS = 3;

    /**
     * Greedily assigns whole {@code groupKey} groups (never split across partitions -- that is
     * the entire point: no scaffold, and therefore no near-duplicate molecule, ever leaks
     * across train/validation/holdout) to VALIDATION then HOLDOUT until each target count is
     * reached, remainder to TRAIN. Groups are ordered largest-first (ties broken by the
     * existing deterministic hash score) so the biggest scaffold clusters are placed as a
     * whole before smaller ones, mirroring DeepChem's {@code ScaffoldSplitter}. A group that
     * overshoots a target is still assigned whole -- accepting imperfect 80/10/10 adherence is
     * the honest cost of not leaking scaffolds, and DeepChem's reference implementation accepts
     * the same overshoot rather than splitting a group.
     */
    static PartitionAssignment assignPartitions(
            List<PartitionCandidate> candidates, int validationCount, int holdoutCount) {
        Map<String, List<PartitionCandidate>> groups = new LinkedHashMap<>();
        for (PartitionCandidate candidate : candidates) {
            groups.computeIfAbsent(candidate.groupKey(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<List<PartitionCandidate>> ordered = new ArrayList<>(groups.values());
        ordered.sort((left, right) -> {
            int bySize = Integer.compare(right.size(), left.size());
            if (bySize != 0) return bySize;
            long leftMin = left.stream().mapToLong(PartitionCandidate::score).min().orElse(0);
            long rightMin = right.stream().mapToLong(PartitionCandidate::score).min().orElse(0);
            return Long.compareUnsigned(leftMin, rightMin);
        });

        Set<Long> validation = new LinkedHashSet<>();
        Set<Long> holdout = new LinkedHashSet<>();
        for (List<PartitionCandidate> group : ordered) {
            if (validation.size() < validationCount) {
                for (PartitionCandidate candidate : group) validation.add(candidate.id());
            } else if (holdout.size() < holdoutCount) {
                for (PartitionCandidate candidate : group) holdout.add(candidate.id());
            }
        }
        return new PartitionAssignment(validation, holdout);
    }
    private record BuildContext(long jobId, long buildId) { }
    private record CrossValidationPlan(boolean required, int folds, String trigger) {
        String manifestJson() {
            return "{\"required\":" + required + ",\"trigger\":" + quote(trigger)
                    + ",\"minimumPerClass\":" + SMALL_SPLIT_MINIMUM_PER_CLASS
                    + ",\"folds\":" + folds + "}";
        }
    }
    record EvaluationMetric(String code, Double value, String unavailableReason) { }
    private record Algorithm(
            Classifier classifier, String contract, FeatureSelection featureSelection,
            SmoteExecution smoteExecution) { }

    static boolean requiresCrossValidation(Instances validation, Instances holdout) {
        if (validation == null || holdout == null || validation.classIndex() < 0
                || holdout.classIndex() < 0
                || validation.classAttribute().numValues() != holdout.classAttribute().numValues()) {
            throw new IllegalArgumentException("compatible classified partitions are required");
        }
        for (int classIndex = 0; classIndex < validation.classAttribute().numValues(); classIndex++) {
            if (classSupport(validation, classIndex) < SMALL_SPLIT_MINIMUM_PER_CLASS
                    || classSupport(holdout, classIndex) < SMALL_SPLIT_MINIMUM_PER_CLASS) {
                return true;
            }
        }
        return false;
    }

    static int crossValidationFoldCount(int instanceCount) {
        if (instanceCount < 2) {
            throw new IllegalArgumentException("cross-validation requires at least two molecules");
        }
        return Math.min(MAX_CROSS_VALIDATION_FOLDS, instanceCount);
    }

    private static int classSupport(Instances data, int classIndex) {
        int support = 0;
        for (int index = 0; index < data.numInstances(); index++) {
            Instance instance = data.instance(index);
            if (!instance.classIsMissing() && (int) instance.classValue() == classIndex) support++;
        }
        return support;
    }

    private static CrossValidationPlan crossValidationPlan(PreparedData data) {
        boolean required = requiresCrossValidation(data.validation, data.holdout);
        int usable = data.train.numInstances() + data.validation.numInstances()
                + data.holdout.numInstances();
        return new CrossValidationPlan(required,
                required ? crossValidationFoldCount(usable) : 0,
                required ? "SPLIT_CLASS_SUPPORT_BELOW_10" : "NOT_REQUIRED");
    }

    static List<EvaluationMetric> evaluationMetrics(Evaluation evaluation) throws Exception {
        double[][] matrix = evaluation.confusionMatrix();
        double total = 0.0;
        double correct = 0.0;
        for (int actual = 0; actual < matrix.length; actual++) {
            for (int predicted = 0; predicted < matrix[actual].length; predicted++) {
                total += matrix[actual][predicted];
                if (actual == predicted) correct += matrix[actual][predicted];
            }
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("evaluation has no observations");

        double weightedPrecision = 0.0;
        double weightedRecall = 0.0;
        double weightedF1 = 0.0;
        double weightedAuc = 0.0;
        double aucSupport = 0.0;
        for (int classIndex = 0; classIndex < matrix.length; classIndex++) {
            double actualSupport = 0.0;
            double predictedSupport = 0.0;
            for (int index = 0; index < matrix.length; index++) {
                actualSupport += matrix[classIndex][index];
                predictedSupport += matrix[index][classIndex];
            }
            if (!(actualSupport > 0.0)) continue;
            double truePositives = matrix[classIndex][classIndex];
            double precision = predictedSupport > 0.0 ? truePositives / predictedSupport : 0.0;
            double recall = truePositives / actualSupport;
            double f1 = precision + recall > 0.0
                    ? 2.0 * precision * recall / (precision + recall) : 0.0;
            weightedPrecision += actualSupport * precision;
            weightedRecall += actualSupport * recall;
            weightedF1 += actualSupport * f1;
            if (total - actualSupport > 0.0) {
                double auc = evaluation.areaUnderROC(classIndex);
                if (Double.isFinite(auc)) {
                    weightedAuc += actualSupport * auc;
                    aucSupport += actualSupport;
                }
            }
        }

        double kappa = evaluation.kappa();
        Double finiteKappa = Double.isFinite(kappa) ? kappa : null;
        Double finiteAuc = aucSupport > 0.0 ? weightedAuc / aucSupport : null;
        return List.of(
                new EvaluationMetric("ACCURACY", correct / total, null),
                new EvaluationMetric("KAPPA", finiteKappa,
                        finiteKappa == null ? "DEGENERATE_CONFUSION_MATRIX" : null),
                new EvaluationMetric("WEIGHTED_PRECISION", weightedPrecision / total, null),
                new EvaluationMetric("WEIGHTED_RECALL", weightedRecall / total, null),
                new EvaluationMetric("WEIGHTED_F1", weightedF1 / total, null),
                new EvaluationMetric("WEIGHTED_AUC", finiteAuc,
                        finiteAuc == null ? "NO_EVALUABLE_ONE_VS_REST_CLASS" : null));
    }

    record SmoteExecution(SMOTE filter, String implementation, int threads) {
        String manifestJson() {
            return "{\"implementation\":" + quote(implementation)
                    + ",\"threads\":" + threads + "}";
        }
    }

    // DeterministicParallelSmote is a byte-for-byte-deterministic drop-in for stock SMOTE (see
    // its class doc): only nearest-neighbor discovery, the O(n^2) part, is parallelized, with
    // results stably sorted back into source-row order. Gating it on training-set size rather
    // than a single hardcoded model id lets every large dataset benefit from the speedup, not
    // just the one definition it was originally built for.
    static SmoteExecution smoteExecution(long trainingInstanceCount, int configuredThreads, long thresholdInstances) {
        if (configuredThreads < 1 || configuredThreads > 64) {
            throw new IllegalArgumentException("threads must be between 1 and 64");
        }
        if (trainingInstanceCount >= thresholdInstances) {
            return new SmoteExecution(
                    new DeterministicParallelSmote(configuredThreads),
                    DeterministicParallelSmote.IMPLEMENTATION_ID,
                    configuredThreads);
        }
        return new SmoteExecution(new SMOTE(), STOCK_SMOTE_IMPLEMENTATION, 1);
    }

    private static final class Worker {
        private final Config config;
        private final Connection read;
        private final Connection write;

        Worker(Config config, Connection read, Connection write) {
            this.config = config; this.read = read; this.write = write;
        }

        void run() throws Exception {
            for (long modelDefinitionId : candidateDefinitionIds()) {
                processDefinition(modelDefinitionId);
            }
        }

        private void processDefinition(long modelDefinitionId) throws Exception {
            boolean explicitModel = config.modelDefinitionId != null;
            if (!acquireDefinitionLock(modelDefinitionId)) {
                if (explicitModel) {
                    throw new IllegalStateException(activeDefinitionMessage(modelDefinitionId));
                }
                System.out.println("Model " + modelDefinitionId
                        + " is active in another rebuild worker; skipping.");
                return;
            }
            Exception primaryFailure = null;
            try {
                recoverInterruptedAttempts(modelDefinitionId);
                Definition definition = definition(modelDefinitionId);
                if (definition == null) {
                    if (explicitModel) {
                        throw new IllegalArgumentException("model definition " + modelDefinitionId
                                + " does not exist or has incomplete metadata");
                    }
                    return;
                }
                if (!eligibleAfterDefinitionLock(definition.definitionStatus, explicitModel)) {
                    String message = "Model " + definition.id + " is no longer eligible (status "
                            + definition.definitionStatus + "); skipping.";
                    if (explicitModel) {
                        throw new IllegalStateException(message);
                    }
                    System.out.println(message);
                    return;
                }
                if (definition.featureStatus == null
                        || !definition.featureStatus.startsWith("READY")) {
                    System.out.println("Model " + definition.id + " waiting for features.");
                    return;
                }
                rebuild(definition);
            } catch (Exception exception) {
                primaryFailure = exception;
                throw exception;
            } finally {
                try {
                    releaseDefinitionLock(modelDefinitionId);
                } catch (SQLException releaseFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(releaseFailure);
                    } else {
                        // Propagation closes the owning connection, which is the lock fallback.
                        throw releaseFailure;
                    }
                }
            }
        }

        private void recoverInterruptedAttempts(long modelDefinitionId) throws SQLException {
            try {
                for (String sql : interruptedRecoverySql(config.schema)) {
                    try (PreparedStatement statement = write.prepareStatement(sql)) {
                        statement.setLong(1, modelDefinitionId);
                        statement.executeUpdate();
                    }
                }
                write.commit();
            } catch (SQLException exception) {
                write.rollback();
                throw exception;
            }
        }

        private List<Long> candidateDefinitionIds() throws SQLException {
            if (config.modelDefinitionId != null) {
                return List.of(config.modelDefinitionId);
            }
            StringBuilder sql = new StringBuilder("SELECT md.model_definition_id FROM ")
                    .append(t("model_definition")).append(" md JOIN ")
                    .append(t("property_definition"))
                    .append(" p ON p.property_id=md.target_property_id JOIN ")
                    .append(t("feature_profile"))
                    .append(" fp ON fp.feature_profile_id=md.feature_profile_id WHERE ")
                    .append(definitionStatusPredicate(false))
                    .append(" ORDER BY md.model_definition_id");
            if (config.limit != null) {
                sql.append(" LIMIT ").append(config.limit);
            }
            List<Long> result = new ArrayList<>();
            try (Statement statement = read.createStatement();
                 ResultSet rows = statement.executeQuery(sql.toString())) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
            return result;
        }

        private Definition definition(long modelDefinitionId) throws SQLException {
            String sql = "SELECT md.model_definition_id,md.dataset_id,md.target_property_id,"
                    + "p.physical_column_name,md.model_name,md.algorithm_code,"
                    + "md.feature_selection_code,md.declared_class_labels_json,"
                    + "md.feature_profile_id,md.status,fp.status FROM " + t("model_definition")
                    + " md JOIN " + t("property_definition")
                    + " p ON p.property_id=md.target_property_id JOIN "
                    + t("feature_profile")
                    + " fp ON fp.feature_profile_id=md.feature_profile_id"
                    + " WHERE md.model_definition_id=?";
            try (PreparedStatement statement = read.prepareStatement(sql)) {
                statement.setLong(1, modelDefinitionId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    return new Definition(rows.getLong(1), rows.getLong(2), rows.getLong(3),
                            rows.getString(4), rows.getString(5), rows.getString(6),
                            rows.getString(7), rows.getString(8), rows.getLong(9),
                            rows.getString(10), rows.getString(11));
                }
            }
        }

        private void rebuild(Definition definition) throws Exception {
            BuildContext context = createBuild(definition);
            try {
                runstep(context,"CONFIGURE");
                List<Component> components = components(definition.featureProfileId);
                runstep(context,"LOAD_DATA");
                if (config.splitStrategy == SplitStrategy.SCAFFOLD) {
                    runstep(context,"SCAFFOLD");
                    ensureScaffoldsForDataset(definition.datasetId);
                }
                PreparedData data = prepareData(definition, components);
                if (data.train.numInstances() == 0 || data.train.numClasses() < 2) {
                    throw new IllegalStateException("training partition has fewer than two classes");
                }
                Algorithm algorithm = algorithm(definition.id, definition.algorithm,
                        definition.featureSelection, config.seed + definition.id, data.train);
                CrossValidationPlan crossValidation = crossValidationPlan(data);
                Classifier crossValidationTemplate = crossValidation.required
                        ? AbstractClassifier.makeCopy(algorithm.classifier) : null;
                persistMembership(context.buildId, data);
                runstep(context,"TRAIN");
                algorithm.classifier.buildClassifier(data.train);
                runstep(context,"EVALUATE");
                persistClasses(context.buildId, data.classSupport);
                evaluate(context.buildId, algorithm.classifier, data.train, "TRAIN");
                evaluate(context.buildId, algorithm.classifier, data.validation, "VALIDATION");
                evaluate(context.buildId, algorithm.classifier, data.holdout, "HOLDOUT");
                if (crossValidation.required) {
                    evaluateCrossValidation(context.buildId, crossValidationTemplate, data,
                            crossValidation.folds, config.seed + definition.id);
                }
                runstep(context,"SERIALIZE");
                byte[] model = serialize(algorithm.classifier);
                byte[] header = serialize(new Instances(data.train, 0));
                persistArtifact(context.buildId, "MODEL", model);
                persistArtifact(context.buildId, "HEADER", header);
                String scaffoldFields = config.splitStrategy == SplitStrategy.SCAFFOLD
                        ? ",\"scaffoldGenerationVersion\":" + quote(MurckoScaffoldCore.GENERATION_VERSION)
                            + ",\"scaffoldFallbackLabels\":[" + data.scaffoldFallbackLabels().stream()
                                .map(V3ModelRebuilder::quote).collect(java.util.stream.Collectors.joining(","))
                            + "]"
                        : "";
                String manifest = "{\"algorithmContract\":" + quote(algorithm.contract)
                        + ",\"featureSelection\":" + algorithm.featureSelection.manifestJson()
                        + ",\"featureProfileId\":" + definition.featureProfileId
                        + ",\"seed\":" + (config.seed + definition.id)
                        + ",\"splitStrategy\":" + quote(splitStrategyName(config.splitStrategy)) + scaffoldFields
                        + ",\"evaluationContract\":" + quote(EVALUATION_CONTRACT)
                        + ",\"crossValidation\":" + crossValidation.manifestJson()
                        + ",\"artifactFormat\":\"JAVA_SERIALIZATION_WEKA_3_8_7_GZIP\""
                        + ",\"smote\":" + algorithm.smoteExecution.manifestJson()
                        + ",\"workerThreads\":" + config.threads + "}";
                completeBuild(context, definition, data, manifest);
                System.out.println("Model definition " + definition.id + " rebuilt as build "
                        + context.buildId + "; awaiting approval.");
            } catch (UnsupportedOperationException exception) {
                failBuild(context, definition, "UNSUPPORTED_CONFIGURATION", exception);
                System.err.println("Model " + definition.id + " unsupported: " + exception.getMessage());
            } catch (Exception exception) {
                failBuild(context, definition, "REBUILD_FAILED", exception);
                System.err.println("Model " + definition.id + " failed: " + rootMessage(exception));
            }
        }

        private BuildContext createBuild(Definition definition) throws SQLException {
            long jobId;
            String jobSql = "INSERT INTO " + t("job")
                    + " (job_type,status,runstep,priority,payload_json,available_at,attempt_count,maximum_attempts,created_at,started_at)"
                    + " VALUES ('MODEL_REBUILD','RUNNING','PREPARE',0,?,NOW(6),1,1,NOW(6),NOW(6))";
            try (PreparedStatement statement = write.prepareStatement(jobSql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "{\"modelDefinitionId\":" + definition.id + "}");
                statement.executeUpdate(); jobId = key(statement);
            }
            int generationNumber=1;Long parentBuildId=null;
            try (PreparedStatement statement = write.prepareStatement(
                    "SELECT model_build_id,generation_number FROM " + t("model_build")
                            + " WHERE model_definition_id=?"
                            + " ORDER BY generation_number DESC LIMIT 1 FOR UPDATE")) {
                statement.setLong(1, definition.id);
                try (ResultSet result=statement.executeQuery()) {
                    if(result.next()){parentBuildId=result.getLong(1);generationNumber=result.getInt(2)+1;}
                }
            }
            String sql = "INSERT INTO " + t("model_build")
                    + " (model_definition_id,parent_model_build_id,job_id,generation_label,generation_number,status,runstep,java_version,cdk_version,weka_version,code_revision,database_schema_version,random_seed,split_strategy,split_configuration_json,training_count,validation_count,holdout_count,excluded_count,created_at,started_at)"
                    + " VALUES (?,?,?,?,?,'RUNNING','PREPARE',?,?,?,?,?,?,?,?,0,0,0,0,NOW(6),NOW(6))";
            long buildId;
            boolean scaffoldGrouped=config.splitStrategy==SplitStrategy.SCAFFOLD;
            String splitConfigurationJson=scaffoldGrouped
                    ? "{\"train\":80,\"validation\":10,\"holdout\":10,\"stratified\":true,\"scaffoldGrouped\":true}"
                    : "{\"train\":80,\"validation\":10,\"holdout\":10,\"stratified\":true}";
            try (PreparedStatement statement = write.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i=1;statement.setLong(i++,definition.id);
                if(parentBuildId==null)statement.setNull(i++,Types.BIGINT);else statement.setLong(i++,parentBuildId);
                statement.setLong(i++,jobId);statement.setString(i++,GENERATION_LABEL);statement.setInt(i++,generationNumber);
                statement.setString(i++,System.getProperty("java.version"));statement.setString(i++,CDK_VERSION);
                statement.setString(i++,WEKA_VERSION);statement.setString(i++,first(System.getenv("MOLCLASS_CODE_REVISION"),"working-tree"));
                statement.setString(i++,"V10");statement.setLong(i++,config.seed+definition.id);
                statement.setString(i++,splitStrategyName(config.splitStrategy));statement.setString(i,splitConfigurationJson);
                statement.executeUpdate();buildId=key(statement);
            }
            write.commit();return new BuildContext(jobId,buildId);
        }

        /**
         * Batch-computes and stores Murcko scaffolds for every distinct molecule in the
         * dataset before {@link #prepareData} joins against them. {@code scaffold_definition}/
         * {@code molecule_scaffold} dedup by hash, so re-running this on a dataset that was
         * already scaffolded (by a prior SCAFFOLD-strategy rebuild, or by prediction-time
         * applicability scoring) is cheap. A single molecule's scaffold computation failing
         * (some structures have no valid Kekule form -- a genuine chemistry/data property, not
         * a bug) must not abort the whole rebuild: it just falls back to a per-molecule
         * singleton group in prepareData(), the same treatment acyclic molecules already get.
         */
        private void ensureScaffoldsForDataset(long datasetId) throws Exception {
            MurckoScaffoldCore scaffolds = new MurckoScaffoldCore(config.schema);
            List<Long> moleculeIds = new ArrayList<>();
            String sql = "SELECT DISTINCT molecule_id FROM " + t("dataset_molecule") + " WHERE dataset_id=?";
            try (PreparedStatement statement = read.prepareStatement(sql)) {
                statement.setLong(1, datasetId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) moleculeIds.add(rows.getLong(1));
                }
            }
            for (long moleculeId : moleculeIds) {
                try {
                    scaffolds.ensureScaffold(write, moleculeId);
                } catch (Exception scaffoldFailure) {
                    System.err.println("scaffold computation failed for molecule " + moleculeId
                            + ": " + rootMessage(scaffoldFailure));
                }
            }
            write.commit();
        }

        private List<Component> components(long profileId) throws SQLException {
            String sql="SELECT c.component_order,c.descriptor_generation_id,c.fingerprint_definition_id,"
                    +"f.fingerprint_code,f.bit_length,s.descriptor_manifest_json FROM "+t("feature_profile_component")
                    +" c LEFT JOIN "+t("fingerprint_definition")+" f ON f.fingerprint_definition_id=c.fingerprint_definition_id"
                    +" LEFT JOIN "+t("descriptor_schema")+" s ON s.descriptor_generation_id=c.descriptor_generation_id"
                    +" WHERE c.feature_profile_id=? ORDER BY c.component_order";
            List<Component> result=new ArrayList<>();
            try(PreparedStatement statement=read.prepareStatement(sql)){statement.setLong(1,profileId);
                try(ResultSet rows=statement.executeQuery()){while(rows.next()){
                    Long descriptor=nullableLong(rows,2), fingerprint=nullableLong(rows,3);
                    result.add(new Component(rows.getInt(1),descriptor,fingerprint,rows.getString(4),rows.getInt(5),
                            descriptor==null?List.of():manifestNames(rows.getString(6))));
                }}
            }
            if(result.isEmpty())throw new IllegalStateException("feature profile has no components");
            return result;
        }

        private PreparedData prepareData(Definition definition,List<Component> components)throws Exception{
            List<String> labels=classLabels(definition);
            if(labels.size()<2)throw new IllegalStateException("target has fewer than two nonblank classes");
            ArrayList<Attribute> attributes=new ArrayList<>();
            for(Component component:components){
                if(component.descriptorId!=null)for(String name:component.descriptorNames)attributes.add(new Attribute(name));
                else for(int bit=0;bit<component.bitLength;bit++)attributes.add(new Attribute(component.code+"_"+bit,List.of("0","1")));
            }
            attributes.add(new Attribute("class",labels));
            Instances header=new Instances("model_"+definition.id,attributes,1024); header.setClassIndex(attributes.size()-1);
            boolean scaffoldGrouped=config.splitStrategy==SplitStrategy.SCAFFOLD;
            StringBuilder sql=new StringBuilder("SELECT dm.dataset_molecule_id,TRIM(CAST(p.`")
                    .append(definition.targetColumn).append("` AS CHAR))");
            int alias=0;
            for(Component component:components){
                if(component.descriptorId!=null)sql.append(",dv.descriptor_values,dv.missing_value_mask,dv.status");
                else sql.append(",f").append(alias).append(".fingerprint_bits,f").append(alias).append(".status");
                alias++;
            }
            if(scaffoldGrouped)sql.append(",dm.molecule_id,ms.scaffold_id");
            sql.append(" FROM ").append(t("dataset_molecule")).append(" dm JOIN ")
                    .append(t("dataset_molecule_properties")).append(" p ON p.dataset_molecule_id=dm.dataset_molecule_id");
            alias=0;
            for(Component component:components){
                if(component.descriptorId!=null)sql.append(" LEFT JOIN ").append(t("molecule_descriptor_vector"))
                        .append(" dv ON dv.molecule_id=dm.molecule_id AND dv.descriptor_generation_id=").append(component.descriptorId);
                else sql.append(" LEFT JOIN ").append(t("molecule_fingerprint")).append(" f").append(alias)
                        .append(" ON f").append(alias).append(".molecule_id=dm.molecule_id AND f").append(alias)
                        .append(".fingerprint_definition_id=").append(component.fingerprintId);
                alias++;
            }
            if(scaffoldGrouped){
                // Filtering to the current generation_version must happen on the molecule_scaffold
                // join *itself*, not a second join stage: molecule_scaffold can hold rows from
                // earlier GENERATION_VERSION bumps for the same molecule_id (each bump computes a
                // fresh scaffold_id, old rows are never deleted). Filtering only the follow-on
                // scaffold_definition join still lets molecule_scaffold fan out to one row per
                // historical generation first -- non-matching generations survive as extra result
                // rows with a NULL scaffold_id (a real bug hit and fixed while verifying this
                // feature live: it produced duplicate dataset_molecule_id rows and a duplicate-key
                // failure in model_training_member). Restricting the ms join's own ON clause to
                // scaffold_ids that belong to the current generation collapses it back to at most
                // one row per molecule_id up front.
                sql.append(" LEFT JOIN ").append(t("molecule_scaffold"))
                        .append(" ms ON ms.molecule_id=dm.molecule_id AND ms.primary_scaffold=1")
                        .append(" AND ms.scaffold_id IN (SELECT scaffold_id FROM ").append(t("scaffold_definition"))
                        .append(" WHERE generation_version='")
                        .append(MurckoScaffoldCore.GENERATION_VERSION.replace("'","''")).append("')");
            }
            sql.append(" WHERE dm.dataset_id=").append(definition.datasetId).append(" ORDER BY dm.dataset_molecule_id");
            Instances train=new Instances(header,0),validation=new Instances(header,0),holdout=new Instances(header,0);
            List<Long> trainIds=new ArrayList<>(),validationIds=new ArrayList<>(),holdoutIds=new ArrayList<>();
            List<Excluded> excluded=new ArrayList<>();Map<String,Long> support=new LinkedHashMap<>();
            record Candidate(long id,Instance instance,long score,String groupKey){}
            Map<String,List<Candidate>> candidates=new LinkedHashMap<>();
            try(Statement statement=read.createStatement();ResultSet rows=statement.executeQuery(sql.toString())){
                while(rows.next()){
                    long id=rows.getLong(1);String label=rows.getString(2);int column=3;String reason=null;
                    double[] values=new double[attributes.size()];int feature=0;
                    if(label==null||label.isBlank()||!labels.contains(label))reason="MISSING_OR_UNKNOWN_CLASS";
                    for(Component component:components){
                        if(component.descriptorId!=null){byte[] vector=rows.getBytes(column++),mask=rows.getBytes(column++);String status=rows.getString(column++);
                            if(vector==null||status==null||!status.startsWith("SUCCEEDED")){reason="MISSING_DESCRIPTOR";feature+=component.descriptorNames.size();continue;}
                            double[] decoded=decodeDoubles(vector,component.descriptorNames.size());
                            for(int i=0;i<decoded.length;i++)values[feature++]=bit(mask,i)?Utils.missingValue():decoded[i];
                        }else{byte[] bits=rows.getBytes(column++);String status=rows.getString(column++);
                            if(bits==null||!"SUCCEEDED".equals(status)){reason="MISSING_FINGERPRINT_"+component.code;feature+=component.bitLength;continue;}
                            for(int i=0;i<component.bitLength;i++)values[feature++]=bit(bits,i)?1.0:0.0;
                        }
                    }
                    String groupKey=null;
                    if(scaffoldGrouped){
                        long moleculeId=rows.getLong(column++);
                        long scaffoldId=rows.getLong(column++);boolean hasScaffold=!rows.wasNull();
                        // Acyclic/unscaffoldable molecules get a per-molecule singleton group,
                        // keyed by molecule_id (not dataset_molecule_id) so the same registered
                        // molecule occurring under two dataset_molecule_id rows still can't leak
                        // across partitions -- a leak that exists in today's HASH split too.
                        groupKey=hasScaffold?("S:"+scaffoldId):("M:"+moleculeId);
                    }
                    if(reason!=null){excluded.add(new Excluded(id,reason));continue;}
                    values[attributes.size()-1]=labels.indexOf(label);Instance instance=new SparseInstance(1.0,values);
                    long score=id^(config.seed+definition.id);score^=score>>>33;score*=0xff51afd7ed558ccdl;
                    score^=score>>>33;score*=0xc4ceb9fe1a85ec53l;score^=score>>>33;
                    candidates.computeIfAbsent(label,ignored->new ArrayList<>()).add(new Candidate(id,instance,score,groupKey));
                    support.merge(label,1L,Long::sum);
                }
            }
            List<String> scaffoldFallbackLabels=new ArrayList<>();
            for(Map.Entry<String,List<Candidate>> entry:candidates.entrySet()){
                List<Candidate> group=entry.getValue();
                int size=group.size();int holdoutCount=size>=5?Math.max(1,size/10):0;
                int validationCount=size>=10?Math.max(1,size/10):0;
                while(size-holdoutCount-validationCount<1){if(validationCount>0)validationCount--;else holdoutCount--;}
                long distinctGroups=scaffoldGrouped?group.stream().map(Candidate::groupKey).distinct().count():0;
                if(scaffoldGrouped&&distinctGroups>=MIN_DISTINCT_SCAFFOLD_GROUPS){
                    List<PartitionCandidate> inputs=group.stream()
                            .map(candidate->new PartitionCandidate(candidate.id(),candidate.groupKey(),candidate.score()))
                            .toList();
                    PartitionAssignment assignment=assignPartitions(inputs,validationCount,holdoutCount);
                    for(Candidate candidate:group){
                        if(assignment.validationIds().contains(candidate.id())){validation.add(candidate.instance());validationIds.add(candidate.id());}
                        else if(assignment.holdoutIds().contains(candidate.id())){holdout.add(candidate.instance());holdoutIds.add(candidate.id());}
                        else{train.add(candidate.instance());trainIds.add(candidate.id());}
                    }
                    continue;
                }
                if(scaffoldGrouped)scaffoldFallbackLabels.add(entry.getKey());
                group.sort((left,right)->Long.compareUnsigned(left.score(),right.score()));
                for(int index=0;index<size;index++){
                    Candidate candidate=group.get(index);
                    if(index<validationCount){validation.add(candidate.instance());validationIds.add(candidate.id());}
                    else if(index<validationCount+holdoutCount){holdout.add(candidate.instance());holdoutIds.add(candidate.id());}
                    else{train.add(candidate.instance());trainIds.add(candidate.id());}
                }
            }
            return new PreparedData(train,validation,holdout,trainIds,validationIds,holdoutIds,excluded,support,scaffoldFallbackLabels);
        }

        private List<String> classLabels(Definition definition)throws SQLException{
            List<String> observed=new ArrayList<>();
            String sql="SELECT DISTINCT TRIM(CAST(p.`"+definition.targetColumn+"` AS CHAR)) value FROM "
                    +t("dataset_molecule")+" dm JOIN "+t("dataset_molecule_properties")
                    +" p ON p.dataset_molecule_id=dm.dataset_molecule_id WHERE dm.dataset_id=? AND p.`"
                    +definition.targetColumn+"` IS NOT NULL ORDER BY value";
            try(PreparedStatement statement=read.prepareStatement(sql)){statement.setLong(1,definition.datasetId);
                try(ResultSet rows=statement.executeQuery()){while(rows.next())observed.add(rows.getString(1));}}
            return resolveClassLabels(definition.declaredClasses,observed);
        }

        private Algorithm algorithm(long modelDefinitionId,String code,String selection,long seed,Instances training)throws Exception{
            String className,options="";Classifier base;
            switch(code){
                case "RandomForest"->{
                    className="weka.classifiers.trees.RandomForest";options="-I 100 -K 0 -S 2 -num-slots "+config.threads;
                    weka.classifiers.trees.RandomForest classifier=new weka.classifiers.trees.RandomForest();
                    classifier.setNumIterations(100);classifier.setNumFeatures(0);classifier.setSeed(2);
                    classifier.setNumExecutionSlots(config.threads);base=classifier;
                }
                case "LMT"->{
                    className="weka.classifiers.trees.LMT";options="-I 1 -M 15 -W 0.0";
                    weka.classifiers.trees.LMT classifier=new weka.classifiers.trees.LMT();
                    classifier.setNumBoostingIterations(1);classifier.setMinNumInstances(15);
                    classifier.setWeightTrimBeta(0.0);base=classifier;
                }
                case "LibSVM","LibSVM2"->{
                    className="weka.classifiers.functions.LibSVM";
                    boolean extended="LibSVM2".equals(code);
                    options=extended?"-Z -S 0 -K 2 -D 3 -R 0.0 -N 0.5 -M 40.0 -C 1.0 -E 0.001 -P 0.1":"-S 0 -C 2 -Z";
                    weka.classifiers.functions.LibSVM classifier=new weka.classifiers.functions.LibSVM();
                    classifier.setSVMType(new weka.core.SelectedTag(
                            weka.classifiers.functions.LibSVM.SVMTYPE_C_SVC,
                            weka.classifiers.functions.LibSVM.TAGS_SVMTYPE));
                    classifier.setNormalize(true);
                    if(extended){
                        classifier.setKernelType(new weka.core.SelectedTag(
                                weka.classifiers.functions.LibSVM.KERNELTYPE_RBF,
                                weka.classifiers.functions.LibSVM.TAGS_KERNELTYPE));
                        classifier.setDegree(3);classifier.setGamma(0.0);classifier.setCoef0(0.0);
                        classifier.setNu(0.5);classifier.setCacheSize(40.0);classifier.setCost(1.0);
                        classifier.setEps(0.001);classifier.setLoss(0.1);
                    }else classifier.setCost(2.0);
                    base=classifier;
                }
                case "SMO"->{
                    className="weka.classifiers.meta.CVParameterSelection";options="-P \"C 0.0001 10 10\" -W weka.classifiers.functions.SMO -- -M -V 10";
                    weka.classifiers.functions.SMO inner=new weka.classifiers.functions.SMO();
                    inner.setBuildCalibrationModels(true);inner.setNumFolds(10);
                    weka.classifiers.meta.CVParameterSelection classifier=new weka.classifiers.meta.CVParameterSelection();
                    classifier.setClassifier(inner);classifier.addCVParameter("C 0.0001 10 10");base=classifier;
                }
                case "KNN"->{
                    weka.classifiers.lazy.IBk inner=new weka.classifiers.lazy.IBk();
                    KnnTuningContract.Plan tuning=KnnTuningContract.forTrainingInstances(training.numInstances());
                    if(!tuning.tuned()){
                        className="weka.classifiers.lazy.IBk";options="-K 1";
                        inner.setKNN(1);base=inner;
                    }else{
                        String parameter=tuning.parameter();
                        className="weka.classifiers.meta.CVParameterSelection";
                        options="-X "+tuning.folds()+" -P \""+parameter+"\" -W weka.classifiers.lazy.IBk";
                        weka.classifiers.meta.CVParameterSelection classifier=new weka.classifiers.meta.CVParameterSelection();
                        classifier.setNumFolds(tuning.folds());classifier.setClassifier(inner);
                        classifier.addCVParameter(parameter);base=classifier;
                    }
                }
                case "J48"->{
                    className="weka.classifiers.meta.CVParameterSelection";options="-P \"C 0.05 0.4 8\" -W weka.classifiers.trees.J48 -- -M 2 -A";
                    weka.classifiers.trees.J48 inner=new weka.classifiers.trees.J48();inner.setMinNumObj(2);inner.setUseLaplace(true);
                    weka.classifiers.meta.CVParameterSelection classifier=new weka.classifiers.meta.CVParameterSelection();
                    classifier.setClassifier(inner);classifier.addCVParameter("C 0.05 0.4 8");base=classifier;
                }
                case "LogitBoost"->{className="weka.classifiers.meta.LogitBoost";base=new weka.classifiers.meta.LogitBoost();}
                case "Bagging"->{
                    className="weka.classifiers.meta.Bagging";options="-P 100 -S 1 -num-slots "+config.threads+" -I 100 -W weka.classifiers.trees.J48 -- -C 0.25 -M 2";
                    base=createBaggingClassifier(config.threads);
                }
                case "AdaBoostM1"->{
                    className="weka.classifiers.meta.AdaBoostM1";options="-P 100 -S 1 -I 50 -W weka.classifiers.trees.J48 -- -C 0.25 -M 2";
                    base=createAdaBoostM1Classifier();
                }
                case "Ensemble"->{
                    className="weka.classifiers.meta.StackingC";options="-B \"weka.classifiers.trees.J48\" -B \"weka.classifiers.lazy.IBk -K 25\" -M \"weka.classifiers.functions.LinearRegression\"";
                    weka.classifiers.lazy.IBk neighbour=new weka.classifiers.lazy.IBk();neighbour.setKNN(25);
                    weka.classifiers.meta.StackingC classifier=new weka.classifiers.meta.StackingC();
                    classifier.setClassifiers(new Classifier[]{new weka.classifiers.trees.J48(),neighbour});
                    classifier.setMetaClassifier(new weka.classifiers.functions.LinearRegression());base=classifier;
                }
                case "NaiveBayes"->{className="weka.classifiers.bayes.NaiveBayes";base=new weka.classifiers.bayes.NaiveBayes();}
                case "NBTree"->{className="weka.classifiers.trees.NBTree";base=new weka.classifiers.trees.NBTree();}
                case "DecisionTreeNaiveBayes"->{
                    className="weka.classifiers.rules.DTNB";options="-X 1";
                    weka.classifiers.rules.DTNB classifier=new weka.classifiers.rules.DTNB();classifier.setCrossVal(1);base=classifier;
                }
                case "BayesNet"->{className="weka.classifiers.bayes.BayesNet";base=new weka.classifiers.bayes.BayesNet();}
                case "NeuralNet"->{
                    className="weka.classifiers.functions.MultilayerPerceptron";options="-S "+seed;
                    weka.classifiers.functions.MultilayerPerceptron classifier=new weka.classifiers.functions.MultilayerPerceptron();
                    classifier.setSeed((int)seed);base=classifier;
                }
                case "Ensemble2"->{
                    className="weka.classifiers.meta.StackingC";
                    weka.classifiers.meta.StackingC classifier=createEnsemble2Classifier();
                    options=Utils.joinOptions(classifier.getOptions());base=classifier;
                }

                default->throw new UnsupportedOperationException("unknown classifier "+code);
            }
            FeatureSelection featureSelection=featureSelectionForModel(
                    modelDefinitionId,selection,config.threads,
                    training.classAttribute().isNominal());
            SmoteExecution smoteExecution=smoteExecution(
                    training.numInstances(),config.threads,config.parallelSmoteMinInstances);
            FilteredClassifier result=commonTrainingPipeline(
                    base,featureSelection,seed,smoteExecution.filter());
            String classifierContract=(className+" "+options).trim();
            return new Algorithm(result,classifierContract+"; "+featureSelection.algorithmContractFragment()
                    +"; imbalance=SpreadSubsample+SMOTE",featureSelection,smoteExecution);
        }

        private void runstep(BuildContext context,String step)throws SQLException{
            try(PreparedStatement statement=write.prepareStatement("UPDATE "+t("model_build")
                    +" SET runstep=? WHERE model_build_id=?")){
                statement.setString(1,step);statement.setLong(2,context.buildId);statement.executeUpdate();
            }
            try(PreparedStatement statement=write.prepareStatement("UPDATE "+t("job")
                    +" SET runstep=?,heartbeat_at=NOW(6) WHERE job_id=?")){
                statement.setString(1,step);statement.setLong(2,context.jobId);statement.executeUpdate();
            }
            write.commit();
        }

        private void persistMembership(long buildId,PreparedData data)throws SQLException{
            String sql="INSERT INTO "+t("model_training_member")
                    +" (model_build_id,dataset_molecule_id,partition_name,fold_number,exclusion_reason) VALUES (?,?,?,NULL,?)";
            try(PreparedStatement statement=write.prepareStatement(sql)){
                addMembers(statement,buildId,data.trainIds,"TRAIN",null);addMembers(statement,buildId,data.validationIds,"VALIDATION",null);
                addMembers(statement,buildId,data.holdoutIds,"HOLDOUT",null);
                for(Excluded excluded:data.excluded){statement.setLong(1,buildId);statement.setLong(2,excluded.datasetMoleculeId);statement.setString(3,"EXCLUDED");statement.setString(4,excluded.reason);statement.addBatch();}
                statement.executeBatch();
            }write.commit();
        }
        private void addMembers(PreparedStatement s,long b,List<Long> ids,String p,String reason)throws SQLException{
            for(long id:ids){s.setLong(1,b);s.setLong(2,id);s.setString(3,p);if(reason==null)s.setNull(4,Types.VARCHAR);else s.setString(4,reason);s.addBatch();}
        }
        private void persistClasses(long buildId,Map<String,Long> support)throws SQLException{
            try(PreparedStatement s=write.prepareStatement("INSERT INTO "+t("model_class")+" (model_build_id,class_order,class_label,support_count) VALUES (?,?,?,?)")){
                int order=0;for(var e:support.entrySet()){s.setLong(1,buildId);s.setInt(2,order++);s.setString(3,e.getKey());s.setLong(4,e.getValue());s.addBatch();}s.executeBatch();}
        }
        private void evaluate(long buildId,Classifier classifier,Instances data,String set)throws Exception{
            if(data.numInstances()==0)return;Evaluation evaluation=new Evaluation(data);evaluation.evaluateModel(classifier,data);
            persistEvaluation(buildId,set,null,evaluation,data.numInstances(),null,null);
        }
        private void evaluateCrossValidation(long buildId,Classifier template,PreparedData data,
                int folds,long seed)throws Exception{
            Instances all=new Instances(data.train,data.train.numInstances()+data.validation.numInstances()+data.holdout.numInstances());
            for(int index=0;index<data.train.numInstances();index++)all.add(data.train.instance(index));
            for(int index=0;index<data.validation.numInstances();index++)all.add(data.validation.instance(index));
            for(int index=0;index<data.holdout.numInstances();index++)all.add(data.holdout.instance(index));
            Instances randomized=new Instances(all);Random random=new Random(seed);randomized.randomize(random);
            if(randomized.classAttribute().isNominal())randomized.stratify(folds);
            Evaluation aggregate=new Evaluation(randomized);
            for(int fold=0;fold<folds;fold++){
                Instances training=randomized.trainCV(folds,fold,random);Instances test=randomized.testCV(folds,fold);
                Classifier classifier=AbstractClassifier.makeCopy(template);classifier.buildClassifier(training);
                Evaluation foldEvaluation=new Evaluation(training);foldEvaluation.evaluateModel(classifier,test);
                persistEvaluation(buildId,"CROSS_VALIDATION",fold+1,foldEvaluation,test.numInstances(),folds,seed);
                aggregate.evaluateModel(classifier,test);
            }
            persistEvaluation(buildId,"CROSS_VALIDATION",null,aggregate,randomized.numInstances(),folds,seed);
        }
        private void persistEvaluation(long buildId,String set,Integer fold,Evaluation evaluation,
                int support,Integer folds,Long seed)throws Exception{
            String sql="INSERT INTO "+t("model_evaluation")+" (model_build_id,evaluation_set,fold_number,class_label,metric_code,metric_value,support_count,metric_details_json,created_at) VALUES (?,?,?,NULL,?,?,?,?,NOW(6))";
            try(PreparedStatement s=write.prepareStatement(sql)){
                for(EvaluationMetric metric:evaluationMetrics(evaluation)){
                    s.setLong(1,buildId);s.setString(2,set);if(fold==null)s.setNull(3,Types.SMALLINT);else s.setInt(3,fold);
                    s.setString(4,metric.code);if(metric.value==null)s.setNull(5,Types.DOUBLE);else s.setDouble(5,metric.value);
                    s.setLong(6,support);s.setString(7,metricDetails(metric,fold,folds,seed));s.addBatch();
                }
                s.executeBatch();
            }
        }
        private String metricDetails(EvaluationMetric metric,Integer fold,Integer folds,Long seed){
            StringBuilder json=new StringBuilder("{\"contract\":").append(quote(EVALUATION_CONTRACT))
                    .append(",\"status\":").append(quote(metric.value==null?"NOT_APPLICABLE":"AVAILABLE"));
            if(metric.unavailableReason!=null)json.append(",\"reason\":").append(quote(metric.unavailableReason));
            if(folds!=null)json.append(",\"folds\":").append(folds).append(",\"seed\":").append(seed)
                    .append(",\"stratified\":true");
            if(fold!=null)json.append(",\"fold\":").append(fold);
            return json.append('}').toString();
        }
        private void persistArtifact(long buildId,String kind,byte[] payload)throws SQLException{
            try(PreparedStatement s=write.prepareStatement("INSERT INTO "+t("model_artifact")+" (model_build_id,artifact_kind,artifact_format,media_type,artifact_size,artifact_sha256,artifact_payload,created_at) VALUES (?,?,'JAVA_SERIALIZATION_WEKA_3_8_7_GZIP','application/gzip',?,?,?,NOW(6))")){
                s.setLong(1,buildId);s.setString(2,kind);s.setLong(3,payload.length);s.setBytes(4,sha(payload));s.setBytes(5,payload);s.executeUpdate();}
        }
        private void completeBuild(BuildContext c,Definition d,PreparedData x,String manifest)throws SQLException{
            try(PreparedStatement s=write.prepareStatement("UPDATE "+t("model_build")+" SET status='AWAITING_APPROVAL',runstep='COMPLETE',training_count=?,validation_count=?,holdout_count=?,excluded_count=?,build_manifest_json=?,manifest_sha256=?,finished_at=NOW(6) WHERE model_build_id=?")){
                s.setLong(1,x.train.numInstances());s.setLong(2,x.validation.numInstances());s.setLong(3,x.holdout.numInstances());s.setLong(4,x.excluded.size());s.setString(5,manifest);s.setBytes(6,sha(manifest.getBytes(StandardCharsets.UTF_8)));s.setLong(7,c.buildId);s.executeUpdate();}
            try(PreparedStatement s=write.prepareStatement("UPDATE "+t("model_definition")+" SET status='AWAITING_APPROVAL' WHERE model_definition_id=?")){s.setLong(1,d.id);s.executeUpdate();}
            finishJob(c.jobId,"COMPLETED",null,null);write.commit();
        }
        private void failBuild(BuildContext c,Definition d,String status,Exception e)throws SQLException{
            write.rollback();try(PreparedStatement s=write.prepareStatement("UPDATE "+t("model_build")+" SET status=?,runstep='FAILED',error_code=?,error_message=?,finished_at=NOW(6) WHERE model_build_id=?")){
                s.setString(1,status);s.setString(2,e.getClass().getSimpleName());s.setString(3,limit(diagnostic(e),2048));s.setLong(4,c.buildId);s.executeUpdate();}
            try(PreparedStatement s=write.prepareStatement("UPDATE "+t("model_definition")+" SET status=? WHERE model_definition_id=?")){s.setString(1,status);s.setLong(2,d.id);s.executeUpdate();}
            finishJob(c.jobId,"FAILED",e.getClass().getSimpleName(),diagnostic(e));write.commit();
        }
        private void finishJob(long id,String status,String code,String message)throws SQLException{
            try(PreparedStatement s=write.prepareStatement("UPDATE "+t("job")+" SET status=?,runstep='COMPLETE',error_code=?,error_message=?,finished_at=NOW(6) WHERE job_id=?")){
                s.setString(1,status);nullable(s,2,code);nullable(s,3,limit(message,2048));s.setLong(4,id);s.executeUpdate();}
        }
        private boolean acquireDefinitionLock(long modelDefinitionId) throws SQLException {
            try (PreparedStatement statement = write.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1, definitionLockName(config.schema, modelDefinitionId));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("GET_LOCK returned no result");
                    }
                    int result = rows.getInt(1);
                    if (rows.wasNull()) {
                        throw new SQLException("GET_LOCK returned NULL");
                    }
                    return result == 1;
                }
            }
        }

        private void releaseDefinitionLock(long modelDefinitionId) throws SQLException {
            try (PreparedStatement statement = write.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, definitionLockName(config.schema, modelDefinitionId));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("RELEASE_LOCK returned no result");
                    }
                    int result = rows.getInt(1);
                    if (rows.wasNull() || result != 1) {
                        throw new SQLException("definition advisory lock was not released");
                    }
                }
            }
        }

        private String t(String table) {
            return qualifiedTable(config.schema, table);
        }
    }

    private static List<String> manifestNames(String json){List<String> names=new ArrayList<>();Matcher m=MANIFEST_NAME.matcher(json);while(m.find())names.add(unescape(m.group(1)));return names;}
    private static List<String> parseJsonStrings(String json){List<String> result=new ArrayList<>();if(json==null)return result;Matcher m=Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);while(m.find())result.add(unescape(m.group(1)));return result;}
    static List<String> resolveClassLabels(String declaredClasses,List<String> observedLabels){
        LinkedHashSet<String> labels=new LinkedHashSet<>(parseJsonStrings(declaredClasses));
        List<String> normalized=new ArrayList<>();
        for(String observed:observedLabels)if(observed!=null&&!observed.isBlank())normalized.add(observed.trim());
        normalized.sort(String::compareTo);labels.addAll(normalized);return List.copyOf(labels);
    }
    private static String resolvedOptions(OptionHandler handler){return Utils.joinOptions(handler.getOptions()).trim();}
    private static String unescape(String value){return value.replace("\\\"","\"").replace("\\\\","\\").replace("\\n","\n").replace("\\t","\t");}
    private static double[] decodeDoubles(byte[] bytes,int count)throws Exception{if(bytes.length!=count*8)throw new IllegalStateException("descriptor vector length mismatch");double[] v=new double[count];try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){for(int i=0;i<count;i++)v[i]=in.readDouble();}return v;}
    private static boolean bit(byte[] bytes,int bit){return bytes!=null&&(bytes[bit>>>3]&(1<<(bit&7)))!=0;}
    private static byte[] serialize(Object value)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeObject(value);}return bytes.toByteArray();}
    private static byte[] sha(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static long key(PreparedStatement s)throws SQLException{try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("missing generated key");return r.getLong(1);}}
    private static Long nullableLong(ResultSet r,int i)throws SQLException{long v=r.getLong(i);return r.wasNull()?null:v;}
    private static void nullable(PreparedStatement s,int i,String v)throws SQLException{if(v==null)s.setNull(i,Types.VARCHAR);else s.setString(i,v);}
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String first(String a,String b){if(a!=null&&!a.isBlank())return a;return b==null||b.isBlank()?null:b;}
    static String qualifiedTable(String schema,String table){validate(schema);validate(table);return "`"+schema+"`.`"+table+"`";}
    private static void validate(String value){if(!SAFE.matcher(value).matches())throw new IllegalArgumentException("unsafe identifier");}
    private static String diagnostic(Throwable e){java.io.StringWriter text=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(text));return text.toString();}
    private static String rootMessage(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();return first(e.getMessage(),e.getClass().getSimpleName());}
    private static String limit(String value,int n){return value==null||value.length()<=n?value:value.substring(0,n);}
}
