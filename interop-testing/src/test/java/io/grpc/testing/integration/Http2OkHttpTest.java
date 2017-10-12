/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import com.google.protobuf.EmptyProtos.Empty;
import com.squareup.okhttp.ConnectionSpec;
import com.squareup.okhttp.TlsVersion;
import io.grpc.ManagedChannel;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.internal.Platform;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.StreamRecorder;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.io.IOException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for GRPC over Http2 using the OkHttp framework.
 */
@RunWith(JUnit4.class)
public class Http2OkHttpTest extends AbstractInteropTest {

  private static final String BAD_HOSTNAME = "I.am.a.bad.hostname";

  @BeforeClass
  public static void loadConscrypt() throws Exception {
    // Load conscrypt if it is available. Either Conscrypt or Jetty ALPN needs to be available for
    // OkHttp to negotiate.
    Util.installConscryptIfAvailable();
  }

  /** Starts the server with HTTPS. */
  @BeforeClass
  public static void startServer() throws Exception {
    try {
      SslProvider sslProvider = SslContext.defaultServerProvider();
      if (sslProvider == SslProvider.OPENSSL && !OpenSsl.isAlpnSupported()) {
        // OkHttp only supports Jetty ALPN on OpenJDK. So if OpenSSL doesn't support ALPN, then we
        // are forced to use Jetty ALPN for Netty instead of OpenSSL.
        sslProvider = SslProvider.JDK;
      }
      SslContextBuilder contextBuilder = SslContextBuilder
          .forServer(TestUtils.loadCert("server1.pem"), TestUtils.loadCert("server1.key"));
      GrpcSslContexts.configure(contextBuilder, sslProvider);
      contextBuilder.ciphers(TestUtils.preferredTestCiphers(), SupportedCipherSuiteFilter.INSTANCE);
      startStaticServer(NettyServerBuilder.forPort(0)
          .flowControlWindow(65 * 1024)
          .maxMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
          .sslContext(contextBuilder.build()));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @AfterClass
  public static void stopServer() throws Exception {
    stopStaticServer();
  }

  @Override
  protected ManagedChannel createChannel() {
    return createChannelBuilder().build();
  }

  private OkHttpChannelBuilder createChannelBuilder() {
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("::1", getPort())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .connectionSpec(new ConnectionSpec.Builder(OkHttpChannelBuilder.DEFAULT_CONNECTION_SPEC)
            .cipherSuites(TestUtils.preferredTestCiphers().toArray(new String[0]))
            .tlsVersions(ConnectionSpec.MODERN_TLS.tlsVersions().toArray(new TlsVersion[0]))
            .build())
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            TestUtils.TEST_SERVER_HOST, getPort()));
    io.grpc.internal.TestingAccessor.setStatsContextFactory(builder, getClientStatsFactory());
    try {
      builder.sslSocketFactory(TestUtils.newSslSocketFactoryForCa(Platform.get().getProvider(),
          TestUtils.loadCert("ca.pem")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return builder;
  }

  @Test
  public void receivedDataForFinishedStream() throws Exception {
    Messages.ResponseParameters.Builder responseParameters =
        Messages.ResponseParameters.newBuilder()
        .setSize(1);
    Messages.StreamingOutputCallRequest.Builder requestBuilder =
        Messages.StreamingOutputCallRequest.newBuilder()
            .setResponseType(Messages.PayloadType.COMPRESSABLE);
    for (int i = 0; i < 1000; i++) {
      requestBuilder.addResponseParameters(responseParameters);
    }

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        asyncStub.fullDuplexCall(recorder);
    Messages.StreamingOutputCallRequest request = requestBuilder.build();
    requestStream.onNext(request);
    recorder.firstValue().get();
    requestStream.onError(new Exception("failed"));

    recorder.awaitCompletion();

    assertEquals(EMPTY, blockingStub.emptyCall(EMPTY));
  }

  @Test
  public void wrongHostNameFailHostnameVerification() throws Exception {
    ManagedChannel channel = createChannelBuilder()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            BAD_HOSTNAME, getPort()))
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    Throwable actualThrown = null;
    try {
      blockingStub.emptyCall(Empty.getDefaultInstance());
    } catch (Throwable t) {
      actualThrown = t;
    }
    assertNotNull("The rpc should have been failed due to hostname verification", actualThrown);
    Throwable cause = Throwables.getRootCause(actualThrown);
    assertTrue(
        "Failed by unexpected exception: " + cause, cause instanceof SSLPeerUnverifiedException);
    channel.shutdown();
  }

  @Test
  public void hostnameVerifierWithBadHostname() throws Exception {
    ManagedChannel channel = createChannelBuilder()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            BAD_HOSTNAME, getPort()))
        .hostnameVerifier(new HostnameVerifier() {
          @Override
          public boolean verify(String hostname, SSLSession session) {
            return true;
          }
        })
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    blockingStub.emptyCall(Empty.getDefaultInstance());

    channel.shutdown();
  }

  @Test
  public void hostnameVerifierWithCorrectHostname() throws Exception {
    ManagedChannel channel = createChannelBuilder()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            TestUtils.TEST_SERVER_HOST, getPort()))
        .hostnameVerifier(new HostnameVerifier() {
          @Override
          public boolean verify(String hostname, SSLSession session) {
            return false;
          }
        })
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    Throwable actualThrown = null;
    try {
      blockingStub.emptyCall(Empty.getDefaultInstance());
    } catch (Throwable t) {
      actualThrown = t;
    }
    assertNotNull("The rpc should have been failed due to hostname verification", actualThrown);
    Throwable cause = Throwables.getRootCause(actualThrown);
    assertTrue(
        "Failed by unexpected exception: " + cause, cause instanceof SSLPeerUnverifiedException);
    channel.shutdown();
  }
}
