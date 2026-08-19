package molclass.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.supervised.instance.SMOTE;

/**
 * SMOTE 1.0.3/revision 8108 with deterministic, bounded parallel discovery of
 * each minority instance's nearest neighbours.
 *
 * <p>The random-dependent synthetic-instance loop is intentionally the stock
 * implementation in its original source-instance order. Neighbour candidates
 * are accumulated and stably sorted in source-row order, preserving ties and
 * every value emitted by the bundled filter.
 */
public final class DeterministicParallelSmote extends SMOTE {
    private static final long serialVersionUID = 1L;

    static final String IMPLEMENTATION_ID =
            "MOLCLASS_WEKA_SMOTE_1.0.3_REVISION_8108_PARALLEL_NEIGHBOURS_V1";
    static final String THREAD_NAME_PREFIX = "molclass-smote-neighbour-";

    private final int threadCount;

    public DeterministicParallelSmote() {
        this(1);
    }

    public DeterministicParallelSmote(int threadCount) {
        if (threadCount < 1 || threadCount > 64) {
            throw new IllegalArgumentException("SMOTE threads must be between 1 and 64");
        }
        this.threadCount = threadCount;
    }

    public int getThreadCount() {
        return threadCount;
    }

    @Override
    public Instances getOutputFormat() {
        Instances output = super.getOutputFormat();
        String parallelToken = "-" + DeterministicParallelSmote.class.getName();
        int tokenIndex = output.relationName().lastIndexOf(parallelToken);
        if (tokenIndex >= 0) {
            String relationName = output.relationName();
            output.setRelationName(
                    relationName.substring(0, tokenIndex)
                            + "-" + SMOTE.class.getName()
                            + relationName.substring(tokenIndex + parallelToken.length()));
        }
        return output;
    }

    @Override
    protected void doSMOTE() throws Exception {
        int minIndex = 0;
        int min = Integer.MAX_VALUE;
        if (m_DetectMinorityClass) {
            int[] classCounts = getInputFormat().attributeStats(
                    getInputFormat().classIndex()).nominalCounts;
            for (int index = 0; index < classCounts.length; index++) {
                if (classCounts[index] != 0 && classCounts[index] < min) {
                    min = classCounts[index];
                    minIndex = index;
                }
            }
        } else {
            String classValue = getClassValue();
            if (classValue.equalsIgnoreCase("first")) {
                minIndex = 1;
            } else if (classValue.equalsIgnoreCase("last")) {
                minIndex = getInputFormat().numClasses();
            } else {
                minIndex = Integer.parseInt(classValue);
            }
            if (minIndex > getInputFormat().numClasses()) {
                throw new Exception("value index must be <= the number of classes");
            }
            minIndex--;
        }

        int nearestNeighbors;
        if (min <= getNearestNeighbors()) {
            nearestNeighbors = min - 1;
        } else {
            nearestNeighbors = getNearestNeighbors();
        }
        if (nearestNeighbors < 1) {
            throw new Exception("Cannot use 0 neighbors!");
        }

        Instances sample = getInputFormat().stringFreeStructure();
        Enumeration<Instance> instances = getInputFormat().enumerateInstances();
        while (instances.hasMoreElements()) {
            Instance instance = instances.nextElement();
            push((Instance) instance.copy());
            if ((int) instance.classValue() == minIndex) {
                sample.add(instance);
            }
        }

        Map<Attribute, double[][]> valueDistanceMatrices = createValueDistanceMatrices();

        Random random = new Random(getRandomSeed());
        List<Integer> extraIndices = new LinkedList<>();
        double percentageRemainder = (getPercentage() / 100)
                - Math.floor(getPercentage() / 100.0);
        int extraIndicesCount = (int) (percentageRemainder * sample.numInstances());
        if (extraIndicesCount >= 1) {
            for (int index = 0; index < sample.numInstances(); index++) {
                extraIndices.add(index);
            }
        }
        Collections.shuffle(extraIndices, random);
        extraIndices = extraIndices.subList(0, extraIndicesCount);
        Set<Integer> extraIndexSet = new HashSet<>(extraIndices);

        DistanceAttribute[] distanceAttributes = distanceAttributes(valueDistanceMatrices);
        Instance[][] discoveredNeighbors = discoverNeighbors(
                sample, distanceAttributes, nearestNeighbors);

        // Stock SMOTE reuses this array. Preserve its under-filled explicit-class behavior.
        Instance[] nearestNeighborArray = new Instance[nearestNeighbors];
        for (int sourceIndex = 0; sourceIndex < sample.numInstances(); sourceIndex++) {
            Instance source = sample.instance(sourceIndex);
            Instance[] discovered = discoveredNeighbors[sourceIndex];
            System.arraycopy(discovered, 0, nearestNeighborArray, 0, discovered.length);

            int remaining = (int) Math.floor(getPercentage() / 100);
            while (remaining > 0 || extraIndexSet.remove(sourceIndex)) {
                double[] values = new double[sample.numAttributes()];
                int neighborIndex = random.nextInt(nearestNeighbors);
                Enumeration<Attribute> attributes = getInputFormat().enumerateAttributes();
                while (attributes.hasMoreElements()) {
                    Attribute attribute = attributes.nextElement();
                    if (!attribute.equals(getInputFormat().classAttribute())) {
                        if (attribute.isNumeric()) {
                            double difference = nearestNeighborArray[neighborIndex]
                                    .value(attribute) - source.value(attribute);
                            double gap = random.nextDouble();
                            values[attribute.index()] = source.value(attribute)
                                    + gap * difference;
                        } else if (attribute.isDate()) {
                            double difference = nearestNeighborArray[neighborIndex]
                                    .value(attribute) - source.value(attribute);
                            double gap = random.nextDouble();
                            values[attribute.index()] = (long) (source.value(attribute)
                                    + gap * difference);
                        } else {
                            int[] valueCounts = new int[attribute.numValues()];
                            int sourceValue = (int) source.value(attribute);
                            valueCounts[sourceValue]++;
                            for (int neighbor = 0; neighbor < nearestNeighbors; neighbor++) {
                                int value = (int) nearestNeighborArray[neighbor].value(attribute);
                                valueCounts[value]++;
                            }
                            int maxIndex = 0;
                            int max = Integer.MIN_VALUE;
                            for (int index = 0; index < attribute.numValues(); index++) {
                                if (valueCounts[index] > max) {
                                    max = valueCounts[index];
                                    maxIndex = index;
                                }
                            }
                            values[attribute.index()] = maxIndex;
                        }
                    }
                }
                values[sample.classIndex()] = minIndex;
                push(new DenseInstance(1.0, values));
                remaining--;
            }
        }
    }

    private Map<Attribute, double[][]> createValueDistanceMatrices() {
        Map<Attribute, double[][]> result = new HashMap<>();
        Enumeration<Attribute> attributes = getInputFormat().enumerateAttributes();
        while (attributes.hasMoreElements()) {
            Attribute attribute = attributes.nextElement();
            if (!attribute.equals(getInputFormat().classAttribute())
                    && (attribute.isNominal() || attribute.isString())) {
                double[][] matrix = new double[attribute.numValues()][attribute.numValues()];
                result.put(attribute, matrix);
                int[] valueCounts = new int[attribute.numValues()];
                int[][] valueCountsByClass = new int[
                        getInputFormat().classAttribute().numValues()][attribute.numValues()];
                Enumeration<Instance> instances = getInputFormat().enumerateInstances();
                while (instances.hasMoreElements()) {
                    Instance instance = instances.nextElement();
                    int value = (int) instance.value(attribute);
                    int classValue = (int) instance.classValue();
                    valueCounts[value]++;
                    valueCountsByClass[classValue][value]++;
                }
                for (int left = 0; left < attribute.numValues(); left++) {
                    for (int right = 0; right < attribute.numValues(); right++) {
                        double sum = 0;
                        for (int classIndex = 0;
                                classIndex < getInputFormat().numClasses(); classIndex++) {
                            double leftClassCount = valueCountsByClass[classIndex][left];
                            double rightClassCount = valueCountsByClass[classIndex][right];
                            double leftCount = valueCounts[left];
                            double rightCount = valueCounts[right];
                            double leftTerm = leftClassCount / leftCount;
                            double rightTerm = rightClassCount / rightCount;
                            sum += Math.abs(leftTerm - rightTerm);
                        }
                        matrix[left][right] = sum;
                    }
                }
            }
        }
        return result;
    }

    private DistanceAttribute[] distanceAttributes(
            Map<Attribute, double[][]> valueDistanceMatrices) {
        List<DistanceAttribute> result = new ArrayList<>();
        Enumeration<Attribute> attributes = getInputFormat().enumerateAttributes();
        while (attributes.hasMoreElements()) {
            Attribute attribute = attributes.nextElement();
            if (!attribute.equals(getInputFormat().classAttribute())) {
                result.add(new DistanceAttribute(
                        attribute, attribute.isNumeric(), valueDistanceMatrices.get(attribute)));
            }
        }
        return result.toArray(DistanceAttribute[]::new);
    }

    private Instance[][] discoverNeighbors(
            Instances sample,
            DistanceAttribute[] attributes,
            int nearestNeighbors) throws Exception {
        Instance[][] result = new Instance[sample.numInstances()][];
        if (threadCount == 1 || sample.numInstances() <= 1) {
            for (int source = 0; source < sample.numInstances(); source++) {
                result[source] = discoverNeighborsForSource(
                        sample, attributes, nearestNeighbors, source, false);
            }
            return result;
        }

        int workers = Math.min(threadCount, sample.numInstances());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers),
                new SmoteThreadFactory());
        executor.prestartAllCoreThreads();
        CompletionService<Void> completion = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(workers);
        Throwable primaryFailure = null;
        boolean completed = false;
        try {
            for (int worker = 0; worker < workers; worker++) {
                int from = worker * sample.numInstances() / workers;
                int to = (worker + 1) * sample.numInstances() / workers;
                futures.add(completion.submit(() -> {
                    for (int source = from; source < to; source++) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("parallel SMOTE was cancelled");
                        }
                        result[source] = discoverNeighborsForSource(
                                sample, attributes, nearestNeighbors, source, true);
                    }
                    return null;
                }));
            }
            for (int worker = 0; worker < workers; worker++) {
                completion.take().get();
            }
            completed = true;
            return result;
        } catch (InterruptedException exception) {
            primaryFailure = exception;
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            primaryFailure = cause;
            if (cause instanceof Exception workerFailure) {
                throw workerFailure;
            }
            if (cause instanceof Error workerError) {
                throw workerError;
            }
            throw new IllegalStateException("parallel SMOTE worker failed", cause);
        } catch (RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            if (!completed) {
                for (Future<Void> future : futures) {
                    future.cancel(true);
                }
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            awaitTermination(executor, primaryFailure);
        }
    }

    private Instance[] discoverNeighborsForSource(
            Instances sample,
            DistanceAttribute[] attributes,
            int nearestNeighbors,
            int sourceIndex,
            boolean cancellable) throws InterruptedException {
        Instance source = sample.instance(sourceIndex);
        List<NeighborDistance> distances = new ArrayList<>(
                Math.max(0, sample.numInstances() - 1));
        for (int candidateIndex = 0;
                candidateIndex < sample.numInstances(); candidateIndex++) {
            if (cancellable && (candidateIndex & 31) == 0
                    && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("parallel SMOTE was cancelled");
            }
            if (sourceIndex == candidateIndex) {
                continue;
            }
            Instance candidate = sample.instance(candidateIndex);
            double distance = 0;
            for (DistanceAttribute distanceAttribute : attributes) {
                Attribute attribute = distanceAttribute.attribute();
                double sourceValue = source.value(attribute);
                double candidateValue = candidate.value(attribute);
                if (distanceAttribute.numeric()) {
                    distance += Math.pow(sourceValue - candidateValue, 2);
                } else {
                    distance += distanceAttribute.valueDistance()
                            [(int) sourceValue][(int) candidateValue];
                }
            }
            distance = Math.pow(distance, 0.5);
            distances.add(new NeighborDistance(distance, candidate));
        }

        // Stable sorting preserves source-row order for equal distances.
        distances.sort((left, right) -> Double.compare(left.distance(), right.distance()));
        int count = Math.min(nearestNeighbors, distances.size());
        Instance[] result = new Instance[count];
        for (int index = 0; index < count; index++) {
            result[index] = distances.get(index).instance();
        }
        return result;
    }

    private static void awaitTermination(
            ThreadPoolExecutor executor, Throwable primaryFailure) throws Exception {
        boolean restoreInterrupt = Thread.interrupted();
        Exception cleanupFailure = null;
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                cleanupFailure = new IllegalStateException(
                        "parallel SMOTE executor did not terminate within 30 seconds");
            }
        } catch (InterruptedException exception) {
            restoreInterrupt = true;
            cleanupFailure = exception;
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        if (cleanupFailure != null) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private record DistanceAttribute(
            Attribute attribute, boolean numeric, double[][] valueDistance) { }

    private record NeighborDistance(double distance, Instance instance) { }

    private static final class SmoteThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable, THREAD_NAME_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
