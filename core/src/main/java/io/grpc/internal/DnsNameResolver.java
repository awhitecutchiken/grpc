/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import io.grpc.Attributes;
import io.grpc.NameResolver;
import io.grpc.RefreshingNameResolver;
import io.grpc.ResolvedServerInfo;
import io.grpc.ResolvedServerInfoGroup;
import io.grpc.internal.SharedResourceHolder.Resource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

/**
 * A DNS-based {@link NameResolver}.
 *
 * @see DnsNameResolverProvider
 */
class DnsNameResolver extends RefreshingNameResolver {
  private final String authority;
  private final String host;
  private final int port;

  DnsNameResolver(@Nullable String nsAuthority, String name, Attributes params,
                  Resource<ScheduledExecutorService> timerServiceResource,
                  Resource<ExecutorService> executorResource) {

    super(timerServiceResource, executorResource);
    // TODO: if a DNS server is provided as nsAuthority, use it.
    // https://www.captechconsulting.com/blogs/accessing-the-dusty-corners-of-dns-with-java
    // Must prepend a "//" to the name when constructing a URI, otherwise it will be treated as an
    // opaque URI, thus the authority and host of the resulted URI would be null.
    URI nameUri = URI.create("//" + name);
    authority = Preconditions.checkNotNull(nameUri.getAuthority(),
            "nameUri (%s) doesn't have an authority", nameUri);
    host = Preconditions.checkNotNull(nameUri.getHost(), "host");
    if (nameUri.getPort() == -1) {
      Integer defaultPort = params.get(NameResolver.Factory.PARAMS_DEFAULT_PORT);
      if (defaultPort != null) {
        port = defaultPort;
      } else {
        throw new IllegalArgumentException(
          "name '" + name + "' doesn't contain a port, and default port is not set in params");
      }
    } else {
      port = nameUri.getPort();
    }
  }

  @Override
  public final String getServiceAuthority() {
    return authority;
  }

  @Override
  protected List<ResolvedServerInfoGroup> getResolvedServerInfoGroups()
    throws UnknownHostException {

    InetAddress[] inetAddrs;
    inetAddrs = getAllByName(host);
    ResolvedServerInfoGroup.Builder servers = ResolvedServerInfoGroup.builder();
    for (int i = 0; i < inetAddrs.length; i++) {
      InetAddress inetAddr = inetAddrs[i];
      servers.add(new ResolvedServerInfo(new InetSocketAddress(inetAddr, port), Attributes.EMPTY));
    }
    return Collections.singletonList(servers.build());
  }

  // To be mocked out in tests
  @VisibleForTesting
  InetAddress[] getAllByName(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }

  final int getPort() {
    return port;
  }

}
