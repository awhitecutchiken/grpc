/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc;

import com.google.common.base.Preconditions;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods for working with {@link ServerInterceptor}s.
 */
public class ServerInterceptors {
  // Prevent instantiation
  private ServerInterceptors() {}

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
   * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The first
   * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
   *
   * @param serviceDef   the service definition for which to intercept all its methods.
   * @param interceptors array of interceptors to apply to the service.
   * @return a wrapped version of {@code serviceDef} with the interceptors applied.
   */
  public static ServerServiceDefinition interceptForward(ServerServiceDefinition serviceDef,
                                                         ServerInterceptor... interceptors) {
    return interceptForward(serviceDef, Arrays.asList(interceptors));
  }

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1701")
  public static ServerServiceDefinition interceptForward(BindableService bindableService,
      ServerInterceptor... interceptors) {
    return interceptForward(bindableService.bindService(), Arrays.asList(interceptors));
  }

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
   * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The first
   * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
   *
   * @param serviceDef   the service definition for which to intercept all its methods.
   * @param interceptors list of interceptors to apply to the service.
   * @return a wrapped version of {@code serviceDef} with the interceptors applied.
   */
  public static ServerServiceDefinition interceptForward(
      ServerServiceDefinition serviceDef,
      List<? extends ServerInterceptor> interceptors) {
    List<? extends ServerInterceptor> copy = new ArrayList<ServerInterceptor>(interceptors);
    Collections.reverse(copy);
    return intercept(serviceDef, copy);
  }

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
   * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The last
   * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
   *
   * @param serviceDef   the service definition for which to intercept all its methods.
   * @param interceptors array of interceptors to apply to the service.
   * @return a wrapped version of {@code serviceDef} with the interceptors applied.
   */
  public static ServerServiceDefinition intercept(ServerServiceDefinition serviceDef,
                                                  ServerInterceptor... interceptors) {
    return intercept(serviceDef, Arrays.asList(interceptors));
  }

  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1701")
  public static ServerServiceDefinition intercept(BindableService bindableService,
      ServerInterceptor... interceptors) {
    return intercept(bindableService.bindService(), Arrays.asList(interceptors));
  }

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
   * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The last
   * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
   *
   * @param serviceDef   the service definition for which to intercept all its methods.
   * @param interceptors list of interceptors to apply to the service.
   * @return a wrapped version of {@code serviceDef} with the interceptors applied.
   */
  public static ServerServiceDefinition intercept(ServerServiceDefinition serviceDef,
                                                  List<? extends ServerInterceptor> interceptors) {
    Preconditions.checkNotNull(serviceDef);
    return new ServerServiceDefinition(
        serviceDef.getDescriptor(),
        wrapServerCallHandler(serviceDef.getCallHandler(), interceptors));
  }

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link MethodDescriptor} serializes to
   * and from InputStream for all methods.  The InputStream is guaranteed return true for
   * markSupported().  The {@code ServerCallHandler} created will automatically
   * convert back to the original types for request and response before calling the existing
   * {@code ServerCallHandler}.  Calling this method combined with the intercept methods will
   * allow the developer to choose whether to intercept messages of InputStream, or the modeled
   * types of their application.
   *
   * @param serviceDef the service definition to convert messages to InputStream
   * @return a wrapped version of {@code serviceDef} with the InputStream conversion applied.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1712")
  public static ServerServiceDefinition useInputStreamMessages(
      final ServerServiceDefinition serviceDef) {
    final MethodDescriptor.Marshaller<InputStream> marshaller =
        new MethodDescriptor.Marshaller<InputStream>() {
      @Override
      public InputStream stream(final InputStream value) {
        return value;
      }

      @Override
      public InputStream parse(final InputStream stream) {
        if (stream.markSupported()) {
          return stream;
        } else {
          return new BufferedInputStream(stream);
        }
      }
    };

    return useMarshalledMessages(serviceDef, marshaller);
  }

  /**
   * Create a new {@code ServerServiceDefinition} whose {@link MethodDescriptor} serializes to
   * and from T for all methods.  The {@code ServerCallHandler} created will automatically
   * convert back to the original types for request and response before calling the existing
   * {@code ServerCallHandler}.  Calling this method combined with the intercept methods will
   * allow the developer to choose whether to intercept messages of T, or the modeled types
   * of their application.  This can also be chained to allow for interceptors to handle messages
   * as multiple different T types within the chain if the added cost of serialization is not
   * a concern.
   *
   * @param serviceDef the service definition to convert messages to T
   * @return a wrapped version of {@code serviceDef} with the T conversion applied.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1712")
  public static <T> ServerServiceDefinition useMarshalledMessages(
      final ServerServiceDefinition serviceDef,
      final MethodDescriptor.Marshaller<T> marshaller) {
    List<MethodDescriptor> wrappedMethods = new ArrayList<MethodDescriptor>(
        serviceDef.getDescriptor().getMethods().size());
    for (final MethodDescriptor<?, ?> originalMethod : serviceDef.getDescriptor().getMethods()) {
      MethodDescriptor<T, T> wrappedMethodDescriptor = MethodDescriptor
          .create(originalMethod.getType(),
              originalMethod.getFullMethodName(),
              marshaller,
              marshaller,
              originalMethod.getServiceIndex());
      wrappedMethods.add(wrappedMethodDescriptor);
    }
    return new ServerServiceDefinition(
        new ServiceDescriptor(serviceDef.getDescriptor().getName(), wrappedMethods),
        wrapHandler(serviceDef.getDescriptor(), serviceDef.getCallHandler()));
  }

  private static ServerCallHandler wrapServerCallHandler(
      ServerCallHandler toWrap,
      List<? extends ServerInterceptor> interceptors) {
    for (ServerInterceptor interceptor : interceptors) {
      toWrap = InterceptCallHandler.create(interceptor, toWrap);
    }
    return toWrap;
  }

  private static class InterceptCallHandler implements ServerCallHandler {

    public static <ReqT, RespT> InterceptCallHandler create(
        ServerInterceptor interceptor, ServerCallHandler callHandler) {
      return new InterceptCallHandler(interceptor, callHandler);
    }

    private final ServerInterceptor interceptor;
    private final ServerCallHandler callHandler;

    private InterceptCallHandler(ServerInterceptor interceptor,
                                 ServerCallHandler callHandler) {
      this.interceptor = Preconditions.checkNotNull(interceptor, "interceptor");
      this.callHandler = callHandler;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> startCall(
        MethodDescriptor<ReqT, RespT> method,
        ServerCall<RespT> call,
        Metadata headers) {
      return interceptor.interceptCall(method, call, headers, callHandler);
    }
  }

  private static ServerCallHandler wrapHandler(
      final ServiceDescriptor originalDescriptor,
      final ServerCallHandler originalHandler) {
    return new ServerCallHandler() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> startCall(
          final MethodDescriptor<ReqT, RespT> method,
          final ServerCall<RespT> call,
          final Metadata headers) {
        final MethodDescriptor originalMethod =
            originalDescriptor.getMethod(method.getFullMethodName());
        final ServerCall unwrappedCall = new PartialForwardingServerCall() {
          @Override
          protected ServerCall delegate() {
            return call;
          }

          @SuppressWarnings("unchecked")
          @Override
          public void sendMessage(Object message) {
            final InputStream is = originalMethod.streamResponse(message);
            final RespT wrappedMessage = method.parseResponse(is);
            delegate().sendMessage(wrappedMessage);
          }
        };

        final ServerCall.Listener originalListener = originalHandler
            .startCall(originalMethod, unwrappedCall, headers);

        return new PartialForwardingServerCallListener<ReqT>() {
          @Override
          protected ServerCall.Listener delegate() {
            return originalListener;
          }

          @SuppressWarnings("unchecked")
          @Override
          public void onMessage(ReqT message) {
            final InputStream is = method.streamRequest(message);
            final Object originalMessage = originalMethod.parseRequest(is);
            delegate().onMessage(originalMessage);
          }
        };
      }
    };
  }
}
