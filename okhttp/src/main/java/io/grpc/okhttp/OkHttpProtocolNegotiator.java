/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.okhttp.internal.OptionalMethod;
import io.grpc.okhttp.internal.Platform;
import io.grpc.okhttp.internal.Platform.TlsExtensionType;
import io.grpc.okhttp.internal.Protocol;
import io.grpc.okhttp.internal.Util;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * A helper class located in package com.squareup.okhttp.internal for TLS negotiation.
 */
class OkHttpProtocolNegotiator {
  private static final Logger logger = Logger.getLogger(OkHttpProtocolNegotiator.class.getName());
  private static final Platform DEFAULT_PLATFORM = Platform.get();
  private static OkHttpProtocolNegotiator NEGOTIATOR =
      createNegotiator(OkHttpProtocolNegotiator.class.getClassLoader());

  protected final Platform platform;

  @VisibleForTesting
  OkHttpProtocolNegotiator(Platform platform) {
    this.platform = checkNotNull(platform, "platform");
  }

  public static OkHttpProtocolNegotiator get() {
    return NEGOTIATOR;
  }

  /**
   * Creates corresponding negotiator according to whether on Android.
   */
  @VisibleForTesting
  static OkHttpProtocolNegotiator createNegotiator(ClassLoader loader) {
    boolean android = true;
    try {
      // Attempt to find Android 2.3+ APIs.
      loader.loadClass("com.android.org.conscrypt.OpenSSLSocketImpl");
    } catch (ClassNotFoundException e1) {
      logger.log(Level.FINE, "Unable to find Conscrypt. Skipping", e1);
      try {
        // Older platform before being unbundled.
        loader.loadClass("org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl");
      } catch (ClassNotFoundException e2) {
        logger.log(Level.FINE, "Unable to find any OpenSSLSocketImpl. Skipping", e2);
        android = false;
      }
    }
    return android
        ? new AndroidNegotiator(DEFAULT_PLATFORM)
        : new OkHttpProtocolNegotiator(DEFAULT_PLATFORM);
  }

  /**
   * Start and wait until the negotiation is done, returns the negotiated protocol.
   *
   * @throws IOException if an IO error was encountered during the handshake.
   * @throws RuntimeException if the negotiation completed, but no protocol was selected.
   */
  public String negotiate(
      SSLSocket sslSocket, String hostname, @Nullable List<Protocol> protocols) throws IOException {
    if (protocols != null) {
      configureTlsExtensions(sslSocket, hostname, protocols);
    }
    try {
      // Force handshake.
      sslSocket.startHandshake();

      String negotiatedProtocol = getSelectedProtocol(sslSocket);
      if (negotiatedProtocol == null) {
        throw new RuntimeException("TLS ALPN negotiation failed with protocols: " + protocols);
      }
      return negotiatedProtocol;
    } finally {
      platform.afterHandshake(sslSocket);
    }
  }

  /** Configure TLS extensions. */
  protected void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    platform.configureTlsExtensions(sslSocket, hostname, protocols);
  }

  /** Returns the negotiated protocol, or null if no protocol was negotiated. */
  public String getSelectedProtocol(SSLSocket socket) {
    return platform.getSelectedProtocol(socket);
  }

  @VisibleForTesting
  static final class AndroidNegotiator extends OkHttpProtocolNegotiator {
    // setUseSessionTickets(boolean)
    private static final OptionalMethod<Socket> SET_USE_SESSION_TICKETS =
        new OptionalMethod<>(null, "setUseSessionTickets", Boolean.TYPE);
    // setHostname(String)
    private static final OptionalMethod<Socket> SET_HOSTNAME =
        new OptionalMethod<>(null, "setHostname", String.class);
    // byte[] getAlpnSelectedProtocol()
    private static final OptionalMethod<Socket> GET_ALPN_SELECTED_PROTOCOL =
        new OptionalMethod<>(byte[].class, "getAlpnSelectedProtocol");
    // setAlpnProtocol(byte[])
    private static final OptionalMethod<Socket> SET_ALPN_PROTOCOLS =
        new OptionalMethod<>(null, "setAlpnProtocols", byte[].class);
    // byte[] getNpnSelectedProtocol()
    private static final OptionalMethod<Socket> GET_NPN_SELECTED_PROTOCOL =
        new OptionalMethod<>(byte[].class, "getNpnSelectedProtocol");
    // setNpnProtocol(byte[])
    private static final OptionalMethod<Socket> SET_NPN_PROTOCOLS =
        new OptionalMethod<>(null, "setNpnProtocols", byte[].class);
    // setApplicationProtocols(String[])
    private static final OptionalMethod<SSLParameters> SET_APPLICATION_PROTOCOLS =
        new OptionalMethod<>(null, "setApplicationProtocols", String[].class);
    // getApplicationProtocol()
    private static final OptionalMethod<SSLSocket> GET_APPLICATION_PROTOCOL =
        new OptionalMethod<>(String.class, "getApplicationProtocol");
    // setServerNames(List<SNIServerName>)
    private static final OptionalMethod<SSLParameters> SET_SERVER_NAMES =
        new OptionalMethod<>(null, "setServerNames", List.class);
    // SNIHostName(String)
    private static Constructor<?> sniHostNameConstructor;
    // Non-null on Android 10.0+.
    // SSLSockets.isSupportedSocket(SSLSocket)
    private static Method sslSocketsIsSupportedSocket;
    // SSLSockets.setUseSessionTickets(SSLSocket, boolean)
    private static Method sslSocketsSetUseSessionTickets;

    static {
      try {
        Class<?> sslSockets = Class.forName("android.net.ssl.SSLSockets");
        sslSocketsIsSupportedSocket = sslSockets.getMethod("isSupportedSocket", SSLSocket.class);
        sslSocketsSetUseSessionTickets =
            sslSockets.getMethod("setUseSessionTickets", SSLSocket.class, boolean.class);
        sniHostNameConstructor =
            Class.forName("javax.net.ssl.SNIHostName").getConstructor(String.class);
      } catch (ClassNotFoundException ignored) {
      } catch (NoSuchMethodException ignored) {
      }
    }

    AndroidNegotiator(Platform platform) {
      super(platform);
    }

    @Override
    public String negotiate(SSLSocket sslSocket, String hostname, List<Protocol> protocols)
        throws IOException {
      // First check if a protocol has already been selected, since it's possible that the user
      // provided SSLSocketFactory has already done the handshake when creates the SSLSocket.
      String negotiatedProtocol = getSelectedProtocol(sslSocket);
      if (negotiatedProtocol == null) {
        negotiatedProtocol = super.negotiate(sslSocket, hostname, protocols);
      }
      return negotiatedProtocol;
    }

    /**
     * Override {@link Platform}'s configureTlsExtensions for Android older than 5.0, since OkHttp
     * (2.3+) only support such function for Android 5.0+.
     */
    @Override
    protected void configureTlsExtensions(
        SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
      SSLParameters sslParams = sslSocket.getSSLParameters();
      // Enable SNI and session tickets.
      if (hostname != null) {
        try {
          if (sslSocketsIsSupportedSocket != null
              && (boolean) sslSocketsIsSupportedSocket.invoke(null, sslSocket)) {
            sslSocketsSetUseSessionTickets.invoke(sslSocket, true);
          } else {
            SET_USE_SESSION_TICKETS.invokeOptionalWithoutCheckedException(sslSocket, true);
          }
          if (sniHostNameConstructor != null) {
            SET_SERVER_NAMES
                .invokeOptionalWithoutCheckedException(
                    sslParams,
                    Collections.singletonList(sniHostNameConstructor.newInstance(hostname)));
          }
        } catch (InstantiationException ignored) {
        } catch (IllegalAccessException ignored) {
        } catch (InvocationTargetException ignored) {
        }
        if (!isPlatformSocket(sslSocket)) {
          SET_HOSTNAME.invokeOptionalWithoutCheckedException(sslSocket, hostname);
        }
      }

      Object[] parameters = {Platform.concatLengthPrefixed(protocols)};
      if (platform.getTlsExtensionType() == TlsExtensionType.ALPN_AND_NPN) {
        SET_APPLICATION_PROTOCOLS.invokeOptionalWithoutCheckedException(
            sslParams, (Object) protocolIds(protocols));
        if (!isPlatformSocket(sslSocket) && SET_ALPN_PROTOCOLS.isSupported(sslSocket)) {
          SET_ALPN_PROTOCOLS.invokeWithoutCheckedException(sslSocket, parameters);
        }
      }

      if (platform.getTlsExtensionType() != TlsExtensionType.NONE) {
        SET_NPN_PROTOCOLS.invokeWithoutCheckedException(sslSocket, parameters);
      } else {
        throw new RuntimeException("We can not do TLS handshake on this Android version, please"
            + " install the Google Play Services Dynamic Security Provider to use TLS");
      }
      sslSocket.setSSLParameters(sslParams);
    }

    @Override
    public String getSelectedProtocol(SSLSocket socket) {
      if (platform.getTlsExtensionType() == TlsExtensionType.ALPN_AND_NPN) {
        // This API was added in Android Q
        try {
          return (String) GET_APPLICATION_PROTOCOL.invokeOptionalWithoutCheckedException(socket);
        } catch (UnsupportedOperationException ignored) {
          // The socket doesn't support this API, try the old reflective method
        }
        try {
          byte[] alpnResult =
              (byte[]) GET_ALPN_SELECTED_PROTOCOL.invokeWithoutCheckedException(socket);
          if (alpnResult != null) {
            return new String(alpnResult, Util.UTF_8);
          }
        } catch (Exception e) {
          logger.log(Level.FINE, "Failed calling getAlpnSelectedProtocol()", e);
          // In some implementations, querying selected protocol before the handshake will fail with
          // exception.
        }
      }

      if (platform.getTlsExtensionType() != TlsExtensionType.NONE) {
        try {
          byte[] npnResult =
              (byte[]) GET_NPN_SELECTED_PROTOCOL.invokeWithoutCheckedException(socket);
          if (npnResult != null) {
            return new String(npnResult, Util.UTF_8);
          }
        } catch (Exception e) {
          logger.log(Level.FINE, "Failed calling getNpnSelectedProtocol()", e);
          // In some implementations, querying selected protocol before the handshake will fail with
          // exception.
        }
      }
      return null;
    }

    private static boolean isPlatformSocket(SSLSocket socket) {
      return socket.getClass().getName().startsWith("com.android.org.conscrypt");
    }
  }

  private static String[] protocolIds(List<Protocol> protocols) {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < protocols.size(); i++) {
      Protocol protocol = protocols.get(i);
      if (protocol == Protocol.HTTP_1_0) continue; // No HTTP/1.0 for ALPN.
      result.add(protocol.toString());
    }
    return result.toArray(new String[0]);
  }
}
