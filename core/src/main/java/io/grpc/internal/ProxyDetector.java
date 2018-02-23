/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * A utility class to detect which proxy, if any, should be used for a given
 * {@link java.net.SocketAddress}. This class performs network requests to resolve address names,
 * and should only be used in places that are expected to do IO such as the
 * {@link io.grpc.NameResolver}.
 */
public interface ProxyDetector {
  /**
   * Given a target address, returns which proxy address should be used. If no proxy should be
   * used, then return value will be null. The address of the {@link ProxyParameters} is always
   * resolved. This throws if the proxy address cannot be resolved.
   */
  @Nullable
  ProxyParameters proxyFor(SocketAddress targetServerAddress) throws IOException;

  // This class does not extend from InetSocketAddress because there is no public constructor
  // that allows subclass instances be unresolved.
  final class ProxiedInetSocketAddress extends SocketAddress {
    private static final long serialVersionUID = -6854992294603212793L;

    private final InetSocketAddress destination;
    private final ProxyParameters proxyParams;

    @VisibleForTesting
    public ProxiedInetSocketAddress(InetSocketAddress destination, ProxyParameters proxyParams) {
      this.destination = destination;
      this.proxyParams = proxyParams;
    }

    public ProxyParameters getProxyParameters() {
      return proxyParams;
    }

    public InetSocketAddress getDestination() {
      return destination;
    }
  }
}
