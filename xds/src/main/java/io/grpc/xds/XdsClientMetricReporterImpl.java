/*
 * Copyright 2024 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Internal;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceCallback;
import io.grpc.xds.client.XdsClient.ServerConnectionCallback;
import io.grpc.xds.client.XdsClientMetricReporter;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * XdsClientMetricReporter implementation.
 */
@Internal
public class XdsClientMetricReporterImpl implements XdsClientMetricReporter {

  private static final Logger logger = Logger.getLogger(
      XdsClientMetricReporterImpl.class.getName());
  private static final LongCounterMetricInstrument SERVER_FAILURE_COUNTER;
  private static final LongCounterMetricInstrument RESOURCE_UPDATES_VALID_COUNTER;
  private static final LongCounterMetricInstrument RESOURCE_UPDATES_INVALID_COUNTER;
  private static final LongGaugeMetricInstrument CONNECTED_GAUGE;
  private static final LongGaugeMetricInstrument RESOURCES_GAUGE;

  private final MetricRecorder metricRecorder;
  @Nullable
  private Registration gaugeRegistration = null;
  @Nullable
  private XdsClient xdsClient = null;

  static {
    MetricInstrumentRegistry metricInstrumentRegistry
        = MetricInstrumentRegistry.getDefaultRegistry();
    SERVER_FAILURE_COUNTER = metricInstrumentRegistry.registerLongCounter(
       "grpc.xds_client.server_failure",
        "EXPERIMENTAL. A counter of xDS servers going from healthy to unhealthy. A server goes"
            + " unhealthy when we have a connectivity failure or when the ADS stream fails without"
            + " seeing a response message, as per gRFC A57.", "{failure}",
        Arrays.asList("grpc.target", "grpc.xds.server"), Collections.emptyList(), false);
    RESOURCE_UPDATES_VALID_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.xds_client.resource_updates_valid",
        "EXPERIMENTAL. A counter of resources received that were considered valid. The counter will"
            + " be incremented even for resources that have not changed.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.server", "grpc.xds.resource_type"),
        Collections.emptyList(), false);
    RESOURCE_UPDATES_INVALID_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.xds_client.resource_updates_invalid",
        "EXPERIMENTAL. A counter of resources received that were considered invalid.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.server", "grpc.xds.resource_type"),
        Collections.emptyList(), false);
    CONNECTED_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.xds_client.connected",
        "EXPERIMENTAL. Whether or not the xDS client currently has a working ADS stream to the xDS"
            + " server. For a given server, this will be set to 1 when the stream is initially"
            + " created. It will be set to 0 when we have a connectivity failure or when the ADS"
            + " stream fails without seeing a response message, as per gRFC A57. Once set to 0, it"
            + " will be reset to 1 when we receive the first response on an ADS stream.", "{bool}",
        Arrays.asList("grpc.target", "grpc.xds.server"), Collections.emptyList(), false);
    RESOURCES_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.xds_client.resources",
        "EXPERIMENTAL.  Number of xDS resources.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.authority", "grpc.xds.cache_state",
            "grpc.xds.resource_type"), Collections.emptyList(), false);
  }

  XdsClientMetricReporterImpl(MetricRecorder metricRecorder) {
    this.metricRecorder = metricRecorder;
  }

  @Override
  public void reportResourceUpdates(long validResourceCount, long invalidResourceCount,
      String target, String xdsServer, String resourceType) {
    metricRecorder.addLongCounter(RESOURCE_UPDATES_VALID_COUNTER, validResourceCount,
        Arrays.asList(target, xdsServer, resourceType), Collections.emptyList());
    metricRecorder.addLongCounter(RESOURCE_UPDATES_INVALID_COUNTER, invalidResourceCount,
        Arrays.asList(target, xdsServer, resourceType), Collections.emptyList());
  }

  @Override
  public void reportServerFailure(long serverFailure, String target, String xdsServer) {
    metricRecorder.addLongCounter(SERVER_FAILURE_COUNTER, serverFailure,
        Arrays.asList(target, xdsServer), Collections.emptyList());
  }

  void setXdsClient(XdsClient client) {
    this.xdsClient = client;
    // register gauge here
    this.gaugeRegistration = metricRecorder.registerBatchCallback(new BatchCallback() {
      @Override
      public void accept(BatchRecorder recorder) {
        reportCallbackMetrics(recorder);
      }
    }, CONNECTED_GAUGE, RESOURCES_GAUGE);
  }

  void close() {
    if (gaugeRegistration != null) {
      gaugeRegistration.close();
    }
  }

  void reportCallbackMetrics(BatchRecorder recorder) {
    MetricReporterCallback callback = new MetricReporterCallback(recorder);
    try {
      SettableFuture<Void> reportResourceCountsCompleted = this.xdsClient.reportResourceCounts(
          callback);
      SettableFuture<Void> reportServerConnectionsCompleted =
          this.xdsClient.reportServerConnections(callback);
      // Normally this shouldn't take long, but adding a timeout to avoid indefinite blocking
      Void unused1 = reportResourceCountsCompleted.get(5, TimeUnit.SECONDS);
      Void unused2 = reportServerConnectionsCompleted.get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // re-set the current thread's interruption state
      }
      logger.log(Level.WARNING, "Failed to report gauge metrics", e);
    }
  }

  @VisibleForTesting
  static final class MetricReporterCallback implements ResourceCallback,
      ServerConnectionCallback {
    private final BatchRecorder recorder;

    MetricReporterCallback(BatchRecorder recorder) {
      this.recorder = recorder;
    }

    // TODO(@dnvindhya): include the "authority" label once xds.authority is available.
    @Override
    public void reportResourceCountGauge(long resourceCount, String cacheState, String resourceType,
        String target) {
      recorder.recordLongGauge(RESOURCES_GAUGE, resourceCount,
          Arrays.asList(target, cacheState, resourceType), Collections.emptyList());
    }

    @Override
    public void reportServerConnectionGauge(int isConnected, String target, String xdsServer) {
      recorder.recordLongGauge(CONNECTED_GAUGE, isConnected, Arrays.asList(target, xdsServer),
          Collections.emptyList());
    }
  }
}
