/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link XdsLrsClient}. */
public class XdsLrsClientTest {

  private static final String SERVICE_AUTHORITY = "api.google.com";
  private static final FakeClock.TaskFilter LOAD_REPORTING_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(XdsLrsClient.LoadReportingTask.class.getSimpleName());
        }
      };
  private static final FakeClock.TaskFilter LRS_RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(XdsLrsClient.LrsRpcRetryTask.class.getSimpleName());
        }
      };
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final ArrayList<String> logs = new ArrayList<>();
  private final ChannelLogger channelLogger = new ChannelLogger() {
    @Override
    public void log(ChannelLogLevel level, String msg) {
      logs.add(level + ": " + msg);
    }

    @Override
    public void log(ChannelLogLevel level, String template, Object... args) {
      log(level, MessageFormat.format(template, args));
    }
  };
  private LoadReportingServiceGrpc.LoadReportingServiceImplBase mockLoadReportingService;
  private FakeClock fakeClock = new FakeClock();
  private final LinkedList<StreamObserver<LoadStatsRequest>> lrsRequestObservers =
      new LinkedList<>();
  @Captor
  private ArgumentCaptor<StreamObserver<LoadStatsResponse>> lrsResponseObserverCaptor;

  @Mock
  private Helper helper;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  private static final LoadStatsRequest EXPECTED_INITIAL_REQ = LoadStatsRequest.newBuilder()
      .setNode(Node.newBuilder()
          .setMetadata(Struct.newBuilder()
              .putFields(
                  XdsLrsClient.TRAFFICDIRECTOR_HOSTNAME_FIELD,
                  Value.newBuilder().setStringValue(SERVICE_AUTHORITY).build())))
      .build();
  @Mock
  private BackoffPolicy backoffPolicy1;
  private XdsLrsClient lrsClient;
  @Mock
  private BackoffPolicy backoffPolicy2;

  private static ClusterStats buildEmptyClusterStats(long loadReportIntervalNanos) {
    return ClusterStats.newBuilder()
        .setClusterName(SERVICE_AUTHORITY)
        .setLoadReportInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  private static LoadStatsResponse buildLrsResponse(long loadReportIntervalNanos) {
    return LoadStatsResponse.newBuilder()
        .addClusters(SERVICE_AUTHORITY)
        .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    mockLoadReportingService = mock(LoadReportingServiceGrpc.LoadReportingServiceImplBase.class,
        delegatesTo(
            new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
              @Override
              public StreamObserver<LoadStatsRequest> streamLoadStats(
                  final StreamObserver<LoadStatsResponse> responseObserver) {
                StreamObserver<LoadStatsRequest> requestObserver =
                    mock(StreamObserver.class);
                Answer<Void> closeRpc = new Answer<Void>() {
                  @Override
                  public Void answer(InvocationOnMock invocation) {
                    responseObserver.onCompleted();
                    return null;
                  }
                };
                doAnswer(closeRpc).when(requestObserver).onCompleted();
                lrsRequestObservers.add(requestObserver);
                return requestObserver;
              }
            }
        ));
    cleanupRule.register(InProcessServerBuilder.forName("fakeLoadReportingServer").directExecutor()
        .addService(mockLoadReportingService).build().start());
    ManagedChannel channel = cleanupRule.register(
        InProcessChannelBuilder.forName("fakeLoadReportingServer").directExecutor().build());
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    when(helper.getChannelLogger()).thenReturn(channelLogger);
    when(helper.getAuthority()).thenReturn(SERVICE_AUTHORITY);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(10L, 100L);
    lrsClient = new XdsLrsClient(channel, helper, fakeClock.getStopwatchSupplier(),
        backoffPolicyProvider, new XdsLoadReportStore(SERVICE_AUTHORITY));
    lrsClient.startLoadReporting();
  }

  @After
  public void tearDown() {
    lrsClient.stopLoadReporting();
  }

  private void assertNextReport(InOrder inOrder, StreamObserver<LoadStatsRequest> requestObserver,
      ClusterStats expectedStats) {
    long loadReportIntervalNanos = Durations.toNanos(expectedStats.getLoadReportInterval());
    assertEquals(0, fakeClock.forwardTime(loadReportIntervalNanos - 1, TimeUnit.NANOSECONDS));
    inOrder.verifyNoMoreInteractions();
    assertEquals(1, fakeClock.forwardTime(1, TimeUnit.NANOSECONDS));
    // A second load report is scheduled upon the first is sent.
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    inOrder.verify(requestObserver).onNext(
        eq(LoadStatsRequest.newBuilder().setNode(Node.newBuilder()
            .setMetadata(Struct.newBuilder()
                .putFields(
                    XdsLrsClient.TRAFFICDIRECTOR_HOSTNAME_FIELD,
                    Value.newBuilder().setStringValue(SERVICE_AUTHORITY).build())))
            .addClusterStats(expectedStats)
            .build()));
  }

  @Test
  public void loadReportInitialRequest() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    // No more request should be sent until receiving initial response. No load reporting
    // should be scheduled.
    assertThat(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER)).isEmpty();
    verifyNoMoreInteractions(requestObserver);
  }

  @Test
  public void loadReportActualIntervalAsSpecified() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    InOrder inOrder = inOrder(requestObserver);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    responseObserver.onNext(buildLrsResponse(1453));
    assertThat(logs).containsExactly(
        "DEBUG: Got an LRS response: " + buildLrsResponse(1453));
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(1453));
  }

  @Test
  public void loadReportIntervalUpdate() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    InOrder inOrder = inOrder(requestObserver);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    responseObserver.onNext(buildLrsResponse(1362));
    assertThat(logs).containsExactly(
        "DEBUG: Got an LRS response: " + buildLrsResponse(1362));
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(1362));

    responseObserver.onNext(buildLrsResponse(2183345));
    // Updated load reporting interval becomes effective immediately.
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(2183345));
  }

  @Test
  public void lrsStreamClosedAndRetried() {
    InOrder inOrder = inOrder(mockLoadReportingService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertEquals(1, lrsRequestObservers.size());
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // First balancer RPC
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it immediately (erroneously)
    responseObserver.onCompleted();

    // Will start backoff sequence 1 (10ns)
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry
    fakeClock.forwardNanos(9);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertEquals(1, lrsRequestObservers.size());
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it with an error.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    // Will continue the backoff sequence 1 (100ns)
    verifyNoMoreInteractions(backoffPolicyProvider);
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry
    fakeClock.forwardNanos(100 - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertEquals(1, lrsRequestObservers.size());
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer sends initial response.
    responseObserver.onNext(buildLrsResponse(0));

    // Then breaks the RPC
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertEquals(1, lrsRequestObservers.size());
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));

    // Fail the retry after spending 4ns
    fakeClock.forwardNanos(4);
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will be on the first retry (10ns) of backoff sequence 2.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(10 - 4 - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertEquals(1, lrsRequestObservers.size());
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(2)).nextBackoffNanos();
    verify(backoffPolicy2, times(1)).nextBackoffNanos();
  }

  @Test
  public void raceBetweenLoadReportingAndLbStreamClosure() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertEquals(1, lrsRequestObservers.size());
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    InOrder inOrder = inOrder(requestObserver);

    // First balancer RPC
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Simulate receiving LB response
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    responseObserver.onNext(buildLrsResponse(1983));
    // Load reporting task is scheduled
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    FakeClock.ScheduledTask scheduledTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER));
    assertEquals(1983, scheduledTask.getDelay(TimeUnit.NANOSECONDS));

    // Close lbStream
    requestObserver.onCompleted();

    // Reporting task cancelled
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));

    // Simulate a race condition where the task has just started when its cancelled
    scheduledTask.command.run();

    // No report sent. No new task scheduled
    inOrder.verify(requestObserver, never()).onNext(any(LoadStatsRequest.class));
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
  }
}