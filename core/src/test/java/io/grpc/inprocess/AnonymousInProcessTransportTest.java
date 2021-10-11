/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.inprocess;

import io.grpc.ServerStreamTracer;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InProcessTransport} with an anonymous server. */
@RunWith(JUnit4.class)
public final class AnonymousInProcessTransportTest extends InProcessTransportTest {

  @Override
  protected InternalServer newServer(
      List<ServerStreamTracer.Factory> streamTracerFactories) {
    InProcessServerBuilder builder = InProcessServerBuilder
        .anonymous(TRANSPORT_NAME)
        .maxInboundMetadataSize(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);
    return new InProcessServer(builder, streamTracerFactories);
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    return new InProcessTransport(
        server.getListenSocketAddress(), GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
        testAuthority(server), USER_AGENT, eagAttrs(), false);
  }

  @Test
  @Ignore("This test isn't appropriate for anonymous servers.")
  @Override
  public void serverAlreadyListening() throws Exception {
    // Since anonymous servers aren't registered anywhere, they can never clash with each other.
  }

}
