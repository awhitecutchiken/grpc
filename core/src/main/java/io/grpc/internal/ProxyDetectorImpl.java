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
import com.google.common.base.Supplier;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A utility class that detects proxies using {@link ProxySelector} and detects authentication
 * credentials using {@link Authenticator}.
 *
 */
class ProxyDetectorImpl implements ProxyDetector {
  private static final Logger log = Logger.getLogger(ProxyDetectorImpl.class.getName());
  private static final AuthenticationProvider DEFAULT_AUTHENTICATOR = new AuthenticationProvider() {
    @Override
    public PasswordAuthentication requestPasswordAuthentication(
        String host, InetAddress addr, int port, String protocol, String prompt, String scheme) {
      // TODO(spencerfang): consider using java.security.AccessController here
      return Authenticator.requestPasswordAuthentication(
          host, addr, port, protocol, prompt, scheme);
    }
  };
  private static final Supplier<ProxySelector> DEFAULT_PROXY_SELECTOR =
      new Supplier<ProxySelector>() {
        @Override
        public ProxySelector get() {
          // TODO(spencerfang): consider using java.security.AccessController here
          return ProxySelector.getDefault();
        }
      };

  @Deprecated
  private static final String GRPC_PROXY_ENV_VAR = "GRPC_PROXY_EXP";
  // Do not hard code a ProxySelector because the global default ProxySelector can change
  private final Supplier<ProxySelector> proxySelector;
  private final AuthenticationProvider authenticationProvider;
  private final ProxyParameters override;

  // We want an HTTPS proxy, which operates on the entire data stream (See IETF rfc2817).
  static final String PROXY_SCHEME = "https";
  static final String URI_FORMAT = PROXY_SCHEME + "://%s";

  /**
   * A proxy selector that uses the global {@link ProxySelector#getDefault()} and
   * {@link ProxyDetectorImpl.AuthenticationProvider} to detect proxy parameters.
   */
  public ProxyDetectorImpl() {
    this(DEFAULT_PROXY_SELECTOR, DEFAULT_AUTHENTICATOR, System.getenv(GRPC_PROXY_ENV_VAR));
  }

  @VisibleForTesting
  ProxyDetectorImpl(
      Supplier<ProxySelector> proxySelector,
      AuthenticationProvider authenticationProvider,
      @Nullable String proxyEnvString) {
    this.proxySelector = proxySelector;
    this.authenticationProvider = authenticationProvider;
    if (proxyEnvString != null) {
      override = new ProxyParameters(overrideProxy(proxyEnvString), null, null);
    } else {
      override = null;
    }
  }

  @Nullable
  @Override
  public ProxyParameters proxyFor(SocketAddress targetServerAddress) {
    if (override != null) {
      return override;
    }
    if (!(targetServerAddress instanceof InetSocketAddress)) {
      return null;
    }
    return detectProxy((InetSocketAddress) targetServerAddress);
  }

  private ProxyParameters detectProxy(InetSocketAddress targetAddr) {
    String hostPort =
        GrpcUtil.authorityFromHostAndPort(targetAddr.getHostName(), targetAddr.getPort());
    URI uri;
    try {
      uri = new URI(String.format(URI_FORMAT, hostPort));
    } catch (final URISyntaxException e) {
      log.log(
          Level.SEVERE,
          "Failed to construct URI for proxy lookup, proceeding without proxy",
          e);
      return null;
    }

    List<Proxy> proxies = proxySelector.get().select(uri);
    if (proxies.size() > 1) {
      log.warning("More than 1 proxy detected, gRPC will select the first one");
    }
    Proxy proxy = proxies.get(0);

    if (proxy.type() == Proxy.Type.DIRECT) {
      return null;
    }
    InetSocketAddress proxyAddr = (InetSocketAddress) proxy.address();
    PasswordAuthentication auth = authenticationProvider.requestPasswordAuthentication(
        proxyAddr.getHostName(),
        proxyAddr.getAddress(),
        proxyAddr.getPort(),
        PROXY_SCHEME,
        "proxy authentication required by gRPC",
        null);

    if (auth == null) {
      return new ProxyParameters(proxyAddr, null, null);
    }

    // TODO(spencerfang): users of ProxyParameters should clear the password when done
    return new ProxyParameters(proxyAddr, auth.getUserName(), new String(auth.getPassword()));
  }

  /**
   * GRPC_PROXY_EXP is deprecated but let's maintain compatibility for now.
   */
  private static InetSocketAddress overrideProxy(String proxyHostPort) {
    if (proxyHostPort == null) {
      return null;
    }

    String[] parts = proxyHostPort.split(":", 2);
    int port = 80;
    if (parts.length > 1) {
      port = Integer.parseInt(parts[1]);
    }
    log.warning(
        "Detected GRPC_PROXY_EXP and will honor it, but this feature will "
            + "be removed in a future release. Use the JVM flags "
            + "\"-Dhttps.proxyHost=HOST -Dhttps.proxyPort=PORT\" to set the https proxy for "
            + "this JVM.");
    return new InetSocketAddress(parts[0], port);
  }

  /**
   * This interface makes unit testing easier by avoiding direct calls to static methods.
   */
  interface AuthenticationProvider {
    PasswordAuthentication requestPasswordAuthentication(
        String host,
        InetAddress addr,
        int port,
        String protocol,
        String prompt,
        String scheme);
  }
}
