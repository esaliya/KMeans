package org.saliya.ompi.kmeans;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.primitives.Doubles;
import mpi.MPI;
import mpi.MPIException;
import net.openhft.affinity.Affinity;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static edu.rice.hj.Module0.launchHabaneroApp;
import static edu.rice.hj.Module1.forallChunked;

public class Program {
    private static DateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
    private static Options programOptions = new Options();

    static {
        programOptions.addOption("n", true, "Number of points");
        programOptions.addOption("d", true, "Dimensionality");
        programOptions.addOption("k", true, "Number of centers");
        programOptions.addOption("t", true, "Error threshold");
        programOptions.addOption("m", true, "Max iteration count");
        programOptions.addOption("b", true, "Is big-endian?");
        programOptions.addOption("T", true, "Number of threads");
        programOptions.addOption("c", true, "Initial center file");
        programOptions.addOption("p", true, "Points file");
        programOptions.addOption("o", true, "Cluster assignment output file");
        programOptions.addOption("mmpn", true, "mmaps per node");
        programOptions.addOption("mmdir", true, "mmaps dir");
        programOptions.addOption("bind", true, "Bind threads [true/false]");
    }

    public static void main(String[] args) {
        Optional<CommandLine> parserResult = Utils.parseCommandLineArguments(args, programOptions);
        if (!parserResult.isPresent()) {
            System.out.println(Utils.ERR_PROGRAM_ARGUMENTS_PARSING_FAILED);
            new HelpFormatter().printHelp(Utils.PROGRAM_NAME, programOptions);
            return;
        }

        CommandLine cmd = parserResult.get();
        if (!(cmd.hasOption("n") && cmd.hasOption("d") && cmd.hasOption("k") &&
                cmd.hasOption("t") && cmd.hasOption("m") && cmd.hasOption("b") &&
                cmd.hasOption("c") && cmd.hasOption("p") && cmd.hasOption("T"))) {
            System.out.println(Utils.ERR_INVALID_PROGRAM_ARGUMENTS);
            new HelpFormatter().printHelp(Utils.PROGRAM_NAME, programOptions);
            return;
        }

        int numPoints = Integer.parseInt(cmd.getOptionValue("n"));
        int dimension = Integer.parseInt(cmd.getOptionValue("d"));
        int numCenters = Integer.parseInt(cmd.getOptionValue("k"));
        int maxIterations = Integer.parseInt(cmd.getOptionValue("m"));
        double errorThreshold = Double.parseDouble(cmd.getOptionValue("t"));
        int numThreads = Integer.parseInt(cmd.getOptionValue("T"));
        boolean isBigEndian = Boolean.parseBoolean(cmd.getOptionValue("b"));
        String outputFile = cmd.hasOption("o") ? cmd.getOptionValue("o") : "";
        String centersFile = cmd.hasOption("c") ? cmd.getOptionValue("c") : "";
        String pointsFile = cmd.hasOption("p") ? cmd.getOptionValue("p") : "";
        int mmapsPerNode = cmd.hasOption("mmpn") ? Integer.parseInt(cmd.getOptionValue("mmpn")) : 1;
        String mmapDir = cmd.hasOption("mmdir") ? cmd.getOptionValue("mmdir") : "/dev/shm";
        // the default is to bind
        boolean bind = !cmd.hasOption("bind") || Boolean.parseBoolean(cmd.getOptionValue("bind"));

        try {
            ParallelOps.setupParallelism(args, mmapsPerNode, mmapDir);
            ParallelOps.setParallelDecomposition(numPoints, dimension, numCenters, numThreads);

            Stopwatch mainTimer = Stopwatch.createStarted();

            print("=== Program Started on " + dateFormat.format(new Date()) + " ===");
            print("  Reading points ... ");

            Stopwatch timer = Stopwatch.createStarted();
            final double[] points = readPoints(pointsFile, dimension, ParallelOps.pointStartIdxForProc,
                    ParallelOps.pointsForProc, isBigEndian);

            timer.stop();
            print("    Done in " + timer.elapsed(TimeUnit.MILLISECONDS) + " ms");
            timer.reset();

            print("  Reading centers ...");
            timer.start();
            final double[] centers = readCenters(centersFile, numCenters, dimension, isBigEndian);
            timer.stop();
            print("    Done in " + timer.elapsed(TimeUnit.MILLISECONDS) + " ms");
            timer.reset();

            DoubleBuffer doubleBuffer = null;
            IntBuffer intBuffer2 = null;
            if (ParallelOps.worldProcsCount > 1) {
                print("  Allocating buffers");
                timer.start();
                intBuffer2 = MPI.newIntBuffer(numPoints);
                doubleBuffer = MPI.newDoubleBuffer(numCenters * (dimension+1));
                timer.stop();
                // This would be similar across
                // all processes, so no need to do average
                print("  Done in " + timer.elapsed(TimeUnit.MILLISECONDS));
                timer.reset();
            }

            final double[] centerSumsAndCountsForThread = new double[numThreads*numCenters*(dimension+1)];
            final int[] clusterAssignments = new int[ParallelOps.pointsForProc];

            // Bind all process case here
            if (numThreads == 1 && bind){
                BitSet bitSet = ThreadBitAssigner.getBitSet(ParallelOps.worldProcRank, 0, numThreads, (ParallelOps.nodeCount));
                Affinity.setAffinity(bitSet);
            }

            int itrCount = 0;
            boolean converged = false;
            print("  Computing K-Means .. ");
            Stopwatch loopTimer = Stopwatch.createStarted();
            Stopwatch commTimerWithCopy = Stopwatch.createUnstarted();
            Stopwatch commTimer = Stopwatch.createUnstarted();
            long[] times = new long[]{0, 0, 0};

            double[] threadComputTimes = new double[numThreads];
            IntStream.range(0, numThreads).forEach(i -> threadComputTimes[i] = 0.0);
//            while (!converged && itrCount < maxIterations) {
            while (itrCount < maxIterations) {
                ++itrCount;
                resetCenterSumsAndCounts(centerSumsAndCountsForThread);

                final int finalItrCount = itrCount;
                if (numThreads > 1) {
                    launchHabaneroApp(() -> forallChunked(0, numThreads - 1, (threadIdx) -> {
                        Thread.currentThread().setName("HJ"+threadIdx);
                        if (bind) {
                            BitSet bitSet = ThreadBitAssigner.getBitSet(ParallelOps.worldProcRank, threadIdx, numThreads, (ParallelOps.nodeCount));
                            Affinity.setAffinity(bitSet);
                        }

                        try {
                            double t = MPI.wtime();
                            findNearesetCenters(dimension, numCenters, points, centers, centerSumsAndCountsForThread,
                                    clusterAssignments, threadIdx);
                            threadComputTimes[threadIdx] += (MPI.wtime() - t)*1e3; //milliseconds
                        } catch (MPIException e) {
                            e.printStackTrace();
                        }
                    }));
                } else {
                    double t = MPI.wtime();
                    findNearesetCenters(dimension, numCenters, points, centers, centerSumsAndCountsForThread,
                            clusterAssignments, 0);
                    threadComputTimes[0] += (MPI.wtime() - t)*1e3;
                }

                if (numThreads > 1) {
                    // Sum over threads
                    // Place results to arrays of thread 0
                    for (int t = 1; t < numThreads; ++t) {
                        for (int c = 0; c < numCenters; ++c) {
                            for (int d = 0; d < (dimension + 1); ++d) {
                                int offsetWithinThread = (c * (dimension + 1)) + d;
                                centerSumsAndCountsForThread[offsetWithinThread] += centerSumsAndCountsForThread[
                                        (t * numCenters * (dimension + 1)) + offsetWithinThread];
                            }
                        }
                    }
                }

                if (ParallelOps.worldProcsCount > 1) {
//                    commTimerWithCopy.start();
//                    copyToBuffer(centerSumsAndCountsForThread, doubleBuffer, numCenters*(dimension+1));
//                    commTimer.start();
//                    ParallelOps.worldProcsComm.allReduce(doubleBuffer, (dimension+1) * numCenters, MPI.DOUBLE, MPI.SUM);
                    // NOTE - change to mmap call
                    ParallelOps.allReduceSum(centerSumsAndCountsForThread, 0, numCenters*(dimension+1));
//                    commTimer.stop();
//                    copyFromBuffer(doubleBuffer, centerSumsAndCountsForThread, numCenters*(dimension+1));
//                    commTimerWithCopy.stop();
//                    times[0] += commTimerWithCopy.elapsed(TimeUnit.MILLISECONDS);
//                    times[1] += commTimer.elapsed(TimeUnit.MILLISECONDS);
//                    commTimerWithCopy.reset();
//                    commTimer.reset();
                }

                converged = true;
                for (int i = 0; i < numCenters; ++i) {
                    final int c = i;
                    IntStream.range(0, dimension).forEach(j -> centerSumsAndCountsForThread[(c * (dimension + 1)) +
                            j] /= centerSumsAndCountsForThread[(c * (dimension + 1)) + dimension]);
                    double dist = getEuclideanDistance(centerSumsAndCountsForThread, centers, dimension, (c * (dimension + 1)), c*dimension);
                    if (dist > errorThreshold) {
                        // Can't break as center sums need to be divided to
                        // form new centers
                        converged = false;
                    }
                    IntStream.range(0, dimension).forEach(j -> centers[(c * dimension) + j] = centerSumsAndCountsForThread[(c * (dimension + 1)) + j]);
                }
            }
            loopTimer.stop();
            times[2] = loopTimer.elapsed(TimeUnit.MILLISECONDS);
            loopTimer.reset();


            /* Find min and max computing times across threads*/
            double[] tmpMin = new double[]{threadComputTimes[0]};
            double[] tmpMax = new double[]{threadComputTimes[0]};
            double t;
            for (int i = 1; i < numThreads; ++i){
                t = threadComputTimes[i];
                if (t > tmpMax[0]){
                    tmpMax[0] = t;
                }
                if (t < tmpMin[0]){
                    tmpMin[0] = t;
                }
            }


            if (ParallelOps.worldProcsCount > 1) {
                ParallelOps.worldProcsComm.reduce(times, 3, MPI.LONG, MPI.SUM, 0);
                ParallelOps.worldProcsComm.allReduce(tmpMin, 1, MPI.DOUBLE, MPI.MIN);
                ParallelOps.worldProcsComm.allReduce(tmpMax, 1, MPI.DOUBLE, MPI.MAX);
            }
            if (!converged) {
                print("    Stopping K-Means as max iteration count " +
                        maxIterations +
                        " has reached");
            }
            print("    Done in " + itrCount + " iterations and " +
                    times[2] * 1.0 / ParallelOps.worldProcsCount + " ms on average (across all MPI)");
            print("      Compute time ms (across all threads and procs) min " + tmpMin[0] + " max " + tmpMax[0] + " diff " + (tmpMax[0] - tmpMin[0]));
            /*if (ParallelOps.worldProcsCount > 1) {
                print("    Avg. comm time " +
                        times[1] * 1.0 / ParallelOps.worldProcsCount +
                        " ms (across all MPI)");
                print("    Avg. comm time w/ copy " +
                        times[0] * 1.0 / ParallelOps.worldProcsCount + " ms (across all MPI)");
            }*/

            if (!Strings.isNullOrEmpty(outputFile)) {
                if (ParallelOps.worldProcsCount > 1) {
                    // Gather cluster assignments
                    print("  Gathering cluster assignments ...");
                    timer.start();
                    int[] lengths = ParallelOps.getLengthsArray(numPoints);
                    int[] displas = new int[ParallelOps.worldProcsCount];
                    displas[0] = 0;
                    System.arraycopy(lengths, 0, displas, 1, ParallelOps.worldProcsCount - 1);
                    Arrays.parallelPrefix(displas, (p, q) -> p + q);
                    intBuffer2.position(ParallelOps.pointStartIdxForProc);
                    intBuffer2.put(clusterAssignments);
                    ParallelOps.worldProcsComm.allGatherv(intBuffer2, lengths, displas, MPI.INT);
                    timer.stop();
                    long[] time = new long[]{timer.elapsed(TimeUnit.MILLISECONDS)};
                    timer.reset();
                    ParallelOps.worldProcsComm.reduce(time, 1, MPI.LONG, MPI.SUM, 0);
                    print("    Done in " + time[0] * 1.0 / ParallelOps.worldProcsCount +
                            " ms on average");
                }

                if (ParallelOps.worldProcRank == 0) {
                    print("  Writing output file ...");
                    timer.start();
                    try (PrintWriter writer = new PrintWriter(
                            Files.newBufferedWriter(Paths.get(outputFile), Charset.defaultCharset(),
                                    StandardOpenOption.CREATE, StandardOpenOption.WRITE), true)) {
                        PointReader reader = PointReader.readRowRange(pointsFile, 0, numPoints, dimension,
                                isBigEndian);
                        double[] point = new double[dimension];
                        for (int i = 0; i < numPoints; ++i) {
                            reader.getPoint(i, point, dimension, 0);
                            writer.println(i + "\t" + Doubles.join("\t", point) + "\t" +
                                    ((ParallelOps.worldProcsCount > 1) ? intBuffer2.get(i) : clusterAssignments[i]));
                        }
                    }
                    timer.stop();
                    print("    Done in " + timer.elapsed(TimeUnit.MILLISECONDS) +
                            "ms");
                    timer.reset();
                }
            }
            mainTimer.stop();
            print("=== Program terminated successfully on " +
                    dateFormat.format(new Date()) + " took " +
                    (mainTimer.elapsed(TimeUnit.MILLISECONDS)) + " ms ===");

            ParallelOps.endParallelism();
        } catch (MPIException | IOException e) {
            e.printStackTrace();
        }
    }

    private static void findNearesetCenters(int dimension, int numCenters, double[] points, double[] centers, double[] centerSumsAndCountsForThread, int[] clusterAssignments, Integer threadIdx) {
        int pointsForThread = ParallelOps.pointsForThread[threadIdx];
        int pointStartIdxForThread = ParallelOps.pointStartIdxForThread[threadIdx];

        for (int i = 0; i < pointsForThread; ++i) {
            int pointOffset = (pointStartIdxForThread + i) * dimension;
            int centerWithMinDist = findCenterWithMinDistance(points, centers, dimension,
                    pointOffset);

            // TODO - debugs
            /*if (finalItrCount ==1 &&
                    (ParallelOps.worldProcsCount > 1 ? ParallelOps.worldProcRank == 1 : ParallelOps.worldProcRank == 0) &&
                    (numThreads > 1 ? threadIdx == 1 : threadIdx == 0)){
                System.out.println(
                        "Rank: " + ParallelOps.worldProcRank + " threadIdx: " + threadIdx + " point " + i +
                                " closest center " + centerWithMinDist);
            }*/
            int centerOffset = threadIdx*numCenters*(dimension+1) + centerWithMinDist*(dimension+1);
            ++centerSumsAndCountsForThread[centerOffset+dimension];
            accumulate(points, centerSumsAndCountsForThread, pointOffset, centerOffset, dimension);
            clusterAssignments[i + pointStartIdxForThread] = centerWithMinDist;
        }
    }

    private static void resetPointsPerCenter(int[][] pointsPerCenterForThread) {
        for (int[] tmp : pointsPerCenterForThread) {
            for (int j = 0; j < tmp.length; ++j) {
                tmp[j] = 0;
            }
        }
    }


    private static void copyFromBuffer(IntBuffer buffer, int[] pointsPerCenter) {
        buffer.position(0);
        buffer.get(pointsPerCenter);
    }

    private static void copyFromBuffer(DoubleBuffer buffer, double[][] centerSums) {
        buffer.position(0);
        for (double[] centerSum : centerSums) {
            buffer.get(centerSum);
        }
    }

    private static void copyToBuffer(int[] pointsPerCenter, IntBuffer buffer) {
        buffer.position(0);
        buffer.put(pointsPerCenter);
    }

    private static void copyFromBuffer(DoubleBuffer buffer, double[] centerSumsAndCounts, int length) {
        buffer.position(0);
        buffer.get(centerSumsAndCounts, 0, length);
    }

    private static void copyToBuffer(double[] centerSumsAndCounts, DoubleBuffer buffer, int length) {
        buffer.position(0);
        buffer.put(centerSumsAndCounts, 0, length);
    }

    private static void print(String msg) {
        if (ParallelOps.worldProcRank == 0) {
            System.out.println(msg);
        }
    }

    private static int findCenterWithMinDistance(double[] points, double[] centers, int dimension, int pointOffset) {
        int k = centers.length/dimension;
        double dMin = Double.MAX_VALUE;
        int dMinIdx = -1;
        for (int j = 0; j < k; ++j) {
            double dist = getEuclideanDistance(points, centers, dimension, pointOffset, j*dimension);
            if (dist < dMin) {
                dMin = dist;
                dMinIdx = j;
            }
        }
        return dMinIdx;
    }

    private static void accumulate(double[] points, double[] centerSumsAndCounts, int pointOffset, int centerOffset, int dimension) {
        for (int i = 0; i < dimension; ++i) {
            centerSumsAndCounts[centerOffset+i] += points[pointOffset+i];
        }
    }

    private static double getEuclideanDistance(double[] point1, double[] point2, int dimension, int point1Offset, int point2Offset) {
        double d = 0.0;
        for (int i = 0; i < dimension; ++i) {
            d += Math.pow(point1[i+point1Offset] - point2[i+point2Offset], 2);
        }
        return Math.sqrt(d);
    }

    private static void resetCenterSumsAndCounts(double[] centerSumsAndCountsForThread) {
        IntStream.range(0, centerSumsAndCountsForThread.length).forEach(i -> centerSumsAndCountsForThread[i] = 0.0);
    }

    private static double[] readPoints(String pointsFile, int dimension, int pointStartIdxForProc, int pointCountForProc, boolean isBigEndian) throws IOException {
        double[] points = new double[pointCountForProc*dimension];
        PointReader reader = PointReader.readRowRange(pointsFile, pointStartIdxForProc, pointCountForProc, dimension, isBigEndian);
        for (int i = 0; i < pointCountForProc; i++) {
            reader.getPoint(i + pointStartIdxForProc, points, dimension, i*dimension);
        }
        return points;
    }

    private static double[] readCenters(String centersFile, int k, int dimension, boolean isBigEndian) throws IOException {
        double[] centers = new double[k*dimension];
        PointReader reader = PointReader.readRowRange(centersFile, 0, k, dimension, isBigEndian);
        for (int i = 0; i < k; i++) {
            reader.getPoint(i, centers, dimension, i*dimension);
        }
        return centers;
    }


}
