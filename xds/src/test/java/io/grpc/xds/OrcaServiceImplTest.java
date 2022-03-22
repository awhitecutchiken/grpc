/*
 * Copyright 2022 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.github.xds.service.orca.v3.OpenRcaServiceGrpc;
import com.github.xds.service.orca.v3.OrcaLoadReportRequest;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.testing.GrpcCleanupRule;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class OrcaServiceImplTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
  private ManagedChannel channel;
  private Server oobserver;
  private final FakeClock fakeClock = new FakeClock();
  private OrcaOobService defaultTestService;
  private final Random random = new Random();
  @Mock
  ClientCall.Listener<OrcaLoadReport> listener;

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);
    defaultTestService = new OrcaOobService(1, TimeUnit.SECONDS,
        fakeClock.getScheduledExecutorService());
    startServerAndGetChannel(defaultTestService.getService());
  }

  @After
  public void teardown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (oobserver != null) {
      oobserver.shutdownNow();
    }
    try {
      channel.awaitTermination(5, TimeUnit.SECONDS);
      oobserver.awaitTermination();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void startServerAndGetChannel(BindableService orcaService) throws Exception {
    oobserver = grpcCleanup.register(
        InProcessServerBuilder.forName("orca-service-test")
            .addService(orcaService)
            .directExecutor()
            .build()
            .start());
    channel = grpcCleanup.register(
        InProcessChannelBuilder.forName("orca-service-test")
            .directExecutor().build());
  }

  @Test
  public void testReportingLifeCycle() {
    defaultTestService.setCpuUtilizationMetric(0.1);
    Iterator<OrcaLoadReport> reports = OpenRcaServiceGrpc.newBlockingStub(channel)
        .streamCoreMetrics(OrcaLoadReportRequest.newBuilder().build());
    assertThat(reports.next()).isEqualTo(
        OrcaLoadReport.newBuilder().setCpuUtilization(0.1).build());
    assertThat(defaultTestService.getClientsCount()).isEqualTo(1);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(reports.next()).isEqualTo(
        OrcaLoadReport.newBuilder().setCpuUtilization(0.1).build());
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    channel.shutdownNow();
    assertThat(defaultTestService.getClientsCount()).isEqualTo(0);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testReportingLifeCycle_serverShutdown() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.setUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(0).setNanos(500).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    assertThat(defaultTestService.getClientsCount()).isEqualTo(1);
    verify(listener).onMessage(eq(expect));
    reset(listener);
    oobserver.shutdownNow();
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    assertThat(defaultTestService.getClientsCount()).isEqualTo(0);
    ArgumentCaptor<Status> callCloseCaptor = ArgumentCaptor.forClass(null);
    verify(listener).onClose(callCloseCaptor.capture(), any());
    assertThat(callCloseCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalLess() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.setUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(0).setNanos(500).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.deleteUtilizationMetric("buffer0");
    assertThat(fakeClock.forwardTime(500, TimeUnit.NANOSECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    verify(listener).onMessage(eq(expect));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalGreater() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.setUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(10).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.deleteUtilizationMetric("buffer0");
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(9, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    verify(listener).onMessage(eq(expect));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalDefault() throws Exception {
    defaultTestService = new OrcaOobService(fakeClock.getScheduledExecutorService());
    oobserver.shutdownNow();
    startServerAndGetChannel(defaultTestService.getService());
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.setUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(10).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.deleteUtilizationMetric("buffer0");
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(20, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    verify(listener).onMessage(eq(expect));
  }

  @Test
  public void testMultipleClients() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.setUtilizationMetric("omg", 100);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder().build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("omg", 100).build();
    verify(listener).onMessage(eq(expect));
    defaultTestService.setMemoryUtilizationMetric(0.5);
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call2 = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    call2.start(listener, new Metadata());
    call2.sendMessage(OrcaLoadReportRequest.newBuilder().build());
    call2.halfClose();
    call2.request(1);
    expect = OrcaLoadReport.newBuilder(expect).setMemUtilization(0.5).build();
    verify(listener).onMessage(eq(expect));
    assertThat(defaultTestService.getClientsCount()).isEqualTo(2);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(2);
    channel.shutdownNow();
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    assertThat(defaultTestService.getClientsCount()).isEqualTo(0);
    ArgumentCaptor<Status> callCloseCaptor = ArgumentCaptor.forClass(null);
    verify(listener, times(2)).onClose(callCloseCaptor.capture(), any());
    assertThat(callCloseCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  public void testApis() throws Exception {
    Map<String, Double> firstUtilization = ImmutableMap.of("util", 0.1);
    OrcaLoadReport goldenReport = OrcaLoadReport.newBuilder()
        .setCpuUtilization(random.nextDouble())
        .setMemUtilization(random.nextDouble())
        .putAllUtilization(firstUtilization)
        .putUtilization("queue", 1.0)
        .build();
    defaultTestService.setCpuUtilizationMetric(goldenReport.getCpuUtilization());
    defaultTestService.setMemoryUtilizationMetric(goldenReport.getMemUtilization());
    defaultTestService.setAllUtilizationMetrics(firstUtilization);
    defaultTestService.setUtilizationMetric("queue", 1.0);
    Iterator<OrcaLoadReport> reports = OpenRcaServiceGrpc.newBlockingStub(channel)
        .streamCoreMetrics(OrcaLoadReportRequest.newBuilder().build());
    assertThat(reports.next()).isEqualTo(goldenReport);

    defaultTestService.deleteCpuUtilizationMetric();
    defaultTestService.deleteMemoryUtilizationMetric();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    goldenReport = OrcaLoadReport.newBuilder()
        .putAllUtilization(firstUtilization)
        .putUtilization("queue", 1.0)
        .putUtilization("util", 0.1)
        .build();
    assertThat(reports.next()).isEqualTo(goldenReport);
    defaultTestService.deleteUtilizationMetric("util-not-exist");
    defaultTestService.deleteUtilizationMetric("queue-not-exist");
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(reports.next()).isEqualTo(goldenReport);

    CountDownLatch latch = new CountDownLatch(1);
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        defaultTestService.deleteUtilizationMetric("util");
        defaultTestService.setMemoryUtilizationMetric(0.4);
        defaultTestService.setAllUtilizationMetrics(firstUtilization);
        latch.countDown();
      }
    });
    latch.await(5, TimeUnit.SECONDS);
    goldenReport = OrcaLoadReport.newBuilder()
        .putAllUtilization(firstUtilization)
        .setMemUtilization(0.4)
        .build();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(reports.next()).isEqualTo(goldenReport);
  }
}
