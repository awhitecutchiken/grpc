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

package io.grpc.gcp.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.grpc.ManagedChannelProvider;
import io.grpc.ServerProvider;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.logging.Sink;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GcpObservabilityTest {
  
  @Test
  public void initFinish() {
    ManagedChannelProvider prevChannelProvider = ManagedChannelProvider.provider();
    ServerProvider prevServerProvider = ServerProvider.provider();
    Sink sink = mock(Sink.class);
    InternalLoggingChannelInterceptor.Factory channelInterceptorFactory = mock(
        InternalLoggingChannelInterceptor.Factory.class);
    InternalLoggingServerInterceptor.Factory serverInterceptorFactory = mock(
        InternalLoggingServerInterceptor.Factory.class);
    GcpObservability observability1;
    try (GcpObservability observability = GcpObservability.grpcInit(sink, channelInterceptorFactory,
        serverInterceptorFactory)) {
      assertThat(ManagedChannelProvider.provider()).isInstanceOf(LoggingChannelProvider.class);
      assertThat(ServerProvider.provider()).isInstanceOf(ServerProvider.class);
      observability1 = GcpObservability.grpcInit(sink, channelInterceptorFactory,
              serverInterceptorFactory);
      assertThat(observability1).isSameInstanceAs(observability);

    }
    verify(sink).close();
    assertThat(ManagedChannelProvider.provider()).isSameInstanceAs(prevChannelProvider);
    assertThat(ServerProvider.provider()).isSameInstanceAs(prevServerProvider);
    try {
      observability1.close();
      fail("should have failed for calling close() second time");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("GcpObservability already closed!");
    }
  }
}
