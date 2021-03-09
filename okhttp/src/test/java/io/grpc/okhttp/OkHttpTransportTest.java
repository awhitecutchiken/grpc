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

package io.grpc.okhttp;

import io.grpc.ChannelLogger;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for OkHttp transport. */
@RunWith(JUnit4.class)
public class OkHttpTransportTest extends AbstractTransportTest {
  private final FakeClock fakeClock = new FakeClock();
  private ClientTransportFactory clientFactory =
      OkHttpChannelBuilder
          // Although specified here, address is ignored because we never call build.
          .forAddress("localhost", 0)
          .usePlaintext()
          .setTransportTracerFactory(fakeClockTransportTracer)
          .maxInboundMetadataSize(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE)
          .buildTransportFactory();
  private ChannelLogger channelLogger = new NoopChannelLogger();

  @After
  public void releaseClientFactory() {
    clientFactory.close();
  }

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    NettyServerBuilder builder = NettyServerBuilder
        .forPort(0)
        .flowControlWindow(AbstractTransportTest.TEST_FLOW_CONTROL_WINDOW);
    InternalNettyServerBuilder.setTransportTracerFactory(builder, fakeClockTransportTracer);
    return InternalNettyServerBuilder.buildTransportServers(builder, streamTracerFactories,
        channelLogger);
  }

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    NettyServerBuilder builder = NettyServerBuilder
        .forAddress(new InetSocketAddress(port))
        .flowControlWindow(AbstractTransportTest.TEST_FLOW_CONTROL_WINDOW);
    InternalNettyServerBuilder.setTransportTracerFactory(builder, fakeClockTransportTracer);
    return InternalNettyServerBuilder.buildTransportServers(builder, streamTracerFactories,
        channelLogger);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return "thebestauthority:" + server.getListenSocketAddress();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    int port = ((InetSocketAddress) server.getListenSocketAddress()).getPort();
    return clientFactory.newClientTransport(
        new InetSocketAddress("localhost", port),
        new ClientTransportFactory.ClientTransportOptions()
          .setAuthority(testAuthority(server))
          .setEagAttributes(eagAttrs()),
        transportLogger());
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
  protected boolean haveTransportTracer() {
    return true;
  }

  @Override
  @org.junit.Test
  @org.junit.Ignore
  public void clientChecksInboundMetadataSize_trailer() {
    // Server-side is flaky due to https://github.com/netty/netty/pull/8332
  }
}
