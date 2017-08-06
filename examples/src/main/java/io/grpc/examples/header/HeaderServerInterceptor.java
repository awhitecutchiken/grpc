/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc.examples.header;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.logging.Logger;

/**
 * A interceptor to handle server header.
 */
public class HeaderServerInterceptor implements ServerInterceptor {

  private static final Logger logger = Logger.getLogger(HeaderServerInterceptor.class.getName());

  @VisibleForTesting
  static final Metadata.Key<String> CUSTOM_HEADER_KEY =
      Metadata.Key.of("custom_server_header_key", Metadata.ASCII_STRING_MARSHALLER);


  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      final Metadata requestHeaders,
      ServerCallHandler<ReqT, RespT> next) {
    logger.info("header received from client:" + requestHeaders);
    // Decorate the ServerCall that will be used in the outbound path.
    SimpleForwardingServerCall<ReqT, RespT> decoratedServerCall =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
          @Override
          public void sendHeaders(Metadata responseHeaders) {
            responseHeaders.put(CUSTOM_HEADER_KEY, "customRespondValue");
            super.sendHeaders(responseHeaders);
          }
        };
    // Pass the decorated server call into grpc so that any remaining interceptors may be applied.
    // We receive a ServerCall.Listener that will be used in the inbound path, and have an
    // opportunity to decorate it here.
    ServerCall.Listener<ReqT> listener = next.startCall(decoratedServerCall, requestHeaders);
    // Note that because the inbound and outbound handlers execute in separate threads, it is not
    // safe for the ServerCall and ServerCall.Listener to interact here without synchronization.
    return listener;
  }
}
