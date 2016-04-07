/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.testing.integration;

import static java.util.Collections.shuffle;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;

import com.google.common.base.Preconditions;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A stress test client following the
 * <a href="https://github.com/grpc/grpc/blob/master/tools/run_tests/stress_test/STRESS_CLIENT_SPEC.md">
 * specifications</a> of the gRPC stress testing framework.
 */
public class StressTestClient {

  /**
   * The main application allowing this client to be launched from the command line.
   */
  public static void main(String... args) throws Exception {
    final StressTestClient client = new StressTestClient();
    client.parseArgs(args);

    // Attempt an orderly shutdown, if the JVM is shutdown via a signal.
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        for (ManagedChannel channel : client.channels) {
          if (!channel.isShutdown()) {
            channel.shutdownNow();
          }
        }
      }
    });

    try {
      client.startMetricsService();
      client.runStressTest();
      client.blockUntilStressTestComplete();
    } finally {
      client.shutdown();
    }
  }

  // Grace period to wait for until seconds is
  private static final int WORKER_GRACE_PERIOD_SECS = 30;

  private List<InetSocketAddress> addresses =
      singletonList(new InetSocketAddress("localhost", 8080));
  private List<TestCaseWeightPair> testCaseWeightPairs = new ArrayList<TestCaseWeightPair>();
  private int durationSecs = -1;
  private int channelsPerServer = 1;
  private int stubsPerChannel = 1;
  private int metricsPort = 8081;

  private Server metricsServer;
  private final Map<String, Metrics.GaugeResponse> gauges =
      new ConcurrentHashMap<String, Metrics.GaugeResponse>();

  private volatile boolean shutdown;

  /**
   * List of futures that {@link #blockUntilStressTestComplete()} waits for.
   */
  private final List<Future<?>> workerFutures = new ArrayList<Future<?>>();
  private final List<ManagedChannel> channels = new ArrayList<ManagedChannel>();
  private ExecutorService threadpool;

  @VisibleForTesting
  void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("server_addresses".equals(key)) {
        addresses = parseServerAddresses(value);
        usage = addresses.isEmpty();
      } else if ("test_cases".equals(key)) {
        testCaseWeightPairs = parseTestCases(value);
      } else if ("test_duration-secs".equals(key)) {
        durationSecs = Integer.valueOf(value);
      } else if ("num_channels_per_server".equals(key)) {
        channelsPerServer = Integer.valueOf(value);
      } else if ("num_stubs_per_channel".equals(key)) {
        stubsPerChannel = Integer.valueOf(value);
      } else if ("metrics_port".equals(key)) {
        metricsPort = Integer.valueOf(value);
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      System.exit(1);
    }
  }

  @VisibleForTesting
  void startMetricsService() throws IOException {
    Preconditions.checkState(!shutdown, "client was shutdown.");

    metricsServer = ServerBuilder.forPort(metricsPort)
        .addService(MetricsServiceGrpc.bindService(new MetricsServiceImpl()))
        .build()
        .start();
  }

  @VisibleForTesting
  void runStressTest() throws ExecutionException, InterruptedException {
    Preconditions.checkState(!shutdown, "client was shutdown.");

    int numChannels = addresses.size() * channelsPerServer;
    int numThreads =  numChannels * stubsPerChannel;
    threadpool = Executors.newFixedThreadPool(numThreads);
    try {
      int server_idx = -1;
      for (InetSocketAddress address : addresses) {
        server_idx++;
        for (int i = 0; i < channelsPerServer; i++) {
          ManagedChannel channel = createChannel(address);
          channels.add(channel);
          for (int j = 0; j < stubsPerChannel; j++) {
            String gaugeName =
                String.format("/stress_test/server_%d/channel_%d/stub_%d/qps", server_idx, i, j);
            Worker worker =
                new Worker(channel, testCaseWeightPairs, durationSecs, gaugeName);

            workerFutures.add(threadpool.submit(worker));
          }
        }
      }
    } catch (Exception e) {
      System.err.println("The stress test client encountered an error:");
      e.printStackTrace();
    }
  }

  @VisibleForTesting
  void blockUntilStressTestComplete() throws Exception {
    Preconditions.checkState(!shutdown, "client was shutdown.");

    // The deadline is just so that client doesn't run endlessly in case a worker crashed.
    long workerDeadline = System.nanoTime() + SECONDS.toNanos(durationSecs)
        + SECONDS.toNanos(WORKER_GRACE_PERIOD_SECS);
    for (Future<?> worker : workerFutures) {
      long timeout = workerDeadline - System.nanoTime();
      if (durationSecs < 0) {
        // -1 indicates that we should wait forever.
        worker.get();
      } else {
        worker.get(timeout, TimeUnit.NANOSECONDS);
      }
    }
  }

  @VisibleForTesting
  void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;

    for (ManagedChannel ch : channels) {
      try {
        ch.shutdownNow();
      } catch (Throwable t) {
        System.err.println("Error shutting down channel.");
        t.printStackTrace();
      }
    }

    try {
      metricsServer.shutdownNow();
    } catch (Throwable t) {
      System.err.println("Error shutting down metrics service.");
      t.printStackTrace();
    }

    try {
      if (threadpool != null) {
        threadpool.shutdownNow();
      }
    } catch (Throwable t) {
      System.err.println("Error shutting down threadpool.");
      t.printStackTrace();
    }
  }

  private static List<InetSocketAddress> parseServerAddresses(String addressesStr) {
    List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();

    for (String[] namePort : parseCommaSeparatedTuples(addressesStr)) {
      String name = namePort[0];
      int port = Integer.valueOf(namePort[1]);
      addresses.add(new InetSocketAddress(name, port));
    }

    return addresses;
  }

  private static List<TestCaseWeightPair> parseTestCases(String testCasesStr) {
    List<TestCaseWeightPair> testCaseWeightPairs = new ArrayList<TestCaseWeightPair>();

    for (String[] nameWeight : parseCommaSeparatedTuples(testCasesStr)) {
      TestCases testCase = TestCases.fromString(nameWeight[0]);
      int weight = Integer.valueOf(nameWeight[1]);
      testCaseWeightPairs.add(new TestCaseWeightPair(testCase, weight));
    }

    return testCaseWeightPairs;
  }

  private static List<String[]> parseCommaSeparatedTuples(String str) {
    List<String[]> tuples = new ArrayList<String[]>();

    String[] tuples0 = str.split(",");
    for (String tupleStr : tuples0) {
      String[] tuple = tupleStr.split(":");
      if (tuple.length != 2) {
        throw new IllegalArgumentException("Illegal tuple format: " + tupleStr);
      }
      tuples.add(tuple);
    }

    return tuples;
  }

  private static ManagedChannel createChannel(InetSocketAddress address) {
    return NettyChannelBuilder.forAddress(address)
        .negotiationType(NegotiationType.PLAINTEXT)
        .build();
  }

  /**
   * A stress test worker. Every stub has its own stress test worker.
   */
  private class Worker implements Runnable {

    // Interval at which the QPS stats of metrics service are updated.
    private static final long METRICS_COLLECTION_INTERVAL_SECS = 5;

    private final ManagedChannel channel;
    private final List<TestCaseWeightPair> testCaseWeightPairs;
    private final int durationSec;
    private final String gaugeName;

    Worker(ManagedChannel channel, List<TestCaseWeightPair> testCaseWeightPairs,
        int durationSec, String gaugeName) {
      this.channel = channel;
      this.testCaseWeightPairs = testCaseWeightPairs;
      this.durationSec = durationSec;
      this.gaugeName = gaugeName;
    }

    @Override
    public void run() {
      // Simplify debugging if the worker crashes / never terminates.
      Thread.currentThread().setName(gaugeName);

      final Tester tester = new Tester();
      tester.setUp();
      final WeightedTestCaseSelector testCaseSelector =
          new WeightedTestCaseSelector(testCaseWeightPairs);
      final long endTime = durationSec < 0
          ? -1
          : System.nanoTime() + SECONDS.toNanos(durationSec);

      // Set it so that the stats are published after the first loop iteration.
      long lastMetricsCollectionTime = System.nanoTime()
          - SECONDS.toNanos(METRICS_COLLECTION_INTERVAL_SECS);
      long testCasesSinceLastMetricsCollection = 0;
      while (!shutdown && (endTime == -1 || endTime > System.nanoTime())) {
        try {
          runTestCase(tester, testCaseSelector.nextTestCase());
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }

        testCasesSinceLastMetricsCollection++;

        if (lastMetricsCollectionTime
            + SECONDS.toNanos(METRICS_COLLECTION_INTERVAL_SECS) <= System.nanoTime()) {
          double durationSeconds = (System.nanoTime() - lastMetricsCollectionTime) / 1000000000.0;
          long qps = (long) Math.ceil(testCasesSinceLastMetricsCollection / durationSeconds);

          Metrics.GaugeResponse gauge = Metrics.GaugeResponse
              .newBuilder()
              .setName(gaugeName)
              .setLongValue(qps)
              .build();

          gauges.put(gaugeName, gauge);

          lastMetricsCollectionTime = System.nanoTime();
          testCasesSinceLastMetricsCollection = 0;
        }
      }
    }

    private void runTestCase(Tester tester, TestCases testCase) throws Exception {
      // TODO(buchgr): Implement tests requiring auth, once C++ supports it.
      switch (testCase) {
        case EMPTY_UNARY:
          tester.emptyUnary();
          break;

        case LARGE_UNARY:
          tester.largeUnary();
          break;

        case CLIENT_STREAMING:
          tester.clientStreaming();
          break;

        case SERVER_STREAMING:
          tester.serverStreaming();
          break;

        case PING_PONG:
          tester.pingPong();
          break;

        case EMPTY_STREAM:
          tester.emptyStream();
          break;

        case UNIMPLEMENTED_METHOD: {
          tester.unimplementedMethod();
          break;
        }

        case CANCEL_AFTER_BEGIN: {
          tester.cancelAfterBegin();
          break;
        }

        case CANCEL_AFTER_FIRST_RESPONSE: {
          tester.cancelAfterFirstResponse();
          break;
        }

        case TIMEOUT_ON_SLEEPING_SERVER: {
          tester.timeoutOnSleepingServer();
          break;
        }

        default:
          throw new IllegalArgumentException("Unknown test case: " + testCase);
      }
    }

    class Tester extends AbstractInteropTest {
      @Override
      protected ManagedChannel createChannel() {
        return Worker.this.channel;
      }
    }

    class WeightedTestCaseSelector {
      // Randomly shuffled list that contains each testcase as often as
      // the testcase's weight.
      final List<TestCases> testCases = new ArrayList<TestCases>();
      int idx;

      WeightedTestCaseSelector(List<TestCaseWeightPair> testCaseWeightPairs) {
        for (TestCaseWeightPair testCaseWeightPair : testCaseWeightPairs) {
          for (int i = 0; i < testCaseWeightPair.weight; i++) {
            testCases.add(testCaseWeightPair.testCase);
          }
        }

        shuffle(testCases);
      }

      TestCases nextTestCase() {
        return testCases.get(idx++ % testCases.size());
      }
    }
  }

  /**
   * Service that exports the QPS metrics of the stress test.
   */
  private class MetricsServiceImpl implements MetricsServiceGrpc.MetricsService {

    @Override
    public void getAllGauges(Metrics.EmptyMessage request,
        StreamObserver<Metrics.GaugeResponse> responseObserver) {
      for (Metrics.GaugeResponse gauge : gauges.values()) {
        responseObserver.onNext(gauge);
      }
      responseObserver.onCompleted();
    }

    @Override
    public void getGauge(Metrics.GaugeRequest request,
        StreamObserver<Metrics.GaugeResponse> responseObserver) {
      String gaugeName = request.getName();
      Metrics.GaugeResponse gauge = gauges.get(gaugeName);
      if (gauge != null) {
        responseObserver.onNext(gauge);
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(new Throwable("Gauge '" + gaugeName + "' does not exist."));
      }
    }
  }

  private static class TestCaseWeightPair {
    final TestCases testCase;
    final int weight;

    TestCaseWeightPair(TestCases testCase, int weight) {
      this.testCase = Preconditions.checkNotNull(testCase);
      this.weight = weight;
    }
  }
}
