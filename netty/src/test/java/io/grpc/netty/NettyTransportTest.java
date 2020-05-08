/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.net.InetSocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Netty transport. */
@RunWith(JUnit4.class)
public class NettyTransportTest extends AbstractTransportTest {
  private final FakeClock fakeClock = new FakeClock();
  // Avoid LocalChannel for testing because LocalChannel can fail with
  // io.netty.channel.ChannelException instead of java.net.ConnectException which breaks
  // serverNotListening test.
  private final ClientTransportFactory clientFactory = NettyChannelBuilder
      // Although specified here, address is ignored because we never call build.
      .forAddress("localhost", 0)
      .flowControlWindow(65 * 1024)
      .negotiationType(NegotiationType.PLAINTEXT)
      .setTransportTracerFactory(fakeClockTransportTracer)
      .buildTransportFactory();

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Override
  protected boolean haveTransportTracer() {
    return true;
  }

  @After
  public void releaseClientFactory() {
    clientFactory.close();
  }

  @Override
  protected List<? extends InternalServer> newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    return NettyServerBuilder
        .forAddress(new InetSocketAddress("localhost", 0))
        .flowControlWindow(65 * 1024)
        .setTransportTracerFactory(fakeClockTransportTracer)
        .buildTransportServers(streamTracerFactories);
  }

  @Override
  protected List<? extends InternalServer> newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return NettyServerBuilder
        .forAddress(new InetSocketAddress("localhost", port))
        .flowControlWindow(65 * 1024)
        .setTransportTracerFactory(fakeClockTransportTracer)
        .buildTransportServers(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return "localhost:" + server.getListenSocketAddress();
  }

  @Override
  protected void advanceClock(long offset, TimeUnit unit) {
    fakeClock.forwardNanos(unit.toNanos(offset));
  }

  @Override
  protected long fakeCurrentTimeNanos() {
    return fakeClock.getTicker().read();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {

    return clientFactory.newClientTransport(
        server.getListenSocketAddress(),
        new ClientTransportFactory.ClientTransportOptions()
            .setAuthority(testAuthority(server))
            .setEagAttributes(eagAttrs()),
        transportLogger());
  }

  @org.junit.Ignore
  @org.junit.Test
  @Override
  public void clientChecksInboundMetadataSize_trailer() throws Exception {
    // Server-side is flaky due to https://github.com/netty/netty/pull/8332
  }

  @Test
  public void channelHasUnresolvedHostname() throws Exception {
    server = null;
    ManagedChannel channel = NettyChannelBuilder
        .forAddress(new InetSocketAddress("invalid", 1234))
        .build();
    grpcCleanupRule.register(channel);
    try {
      ClientCalls.blockingUnaryCall(channel, TestMethodDescriptors.voidMethod(),
          CallOptions.DEFAULT, null);
      fail("exception should have been thrown");
    } catch (StatusRuntimeException e) {
      Status status = e.getStatus();
      assertEquals(Status.Code.UNAVAILABLE, status.getCode());
      assertTrue(status.getCause() instanceof UnresolvedAddressException);
      assertEquals("unresolved address", status.getDescription());
    }
  }
}
