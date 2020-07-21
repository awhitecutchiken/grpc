/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.census;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Internal;
import io.grpc.InternalCensus;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;

/**
 * Accessor for getting {@link ClientInterceptor} or {@link ServerStreamTracer.Factory} with
 * default Census tracing implementation.
 */
@Internal
public final class InternalCensusTracingAccessor {

  // Prevent instantiation.
  private InternalCensusTracingAccessor() {
  }

  /**
   * Returns a {@link ClientInterceptor} with default tracing implementation.
   */
  public static ClientInterceptor getClientInterceptor() {
    CensusTracingModule censusTracing =
        new CensusTracingModule();
    final ClientInterceptor interceptor = censusTracing.getClientInterceptor();
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        if (callOptions.getOption(
            InternalCensus.DISABLE_CLIENT_DEFAULT_CENSUS) != null) {
          return next.newCall(method, callOptions);
        }
        return interceptor.interceptCall(method, callOptions, next);
      }
    };
  }

  /**
   * Returns a {@link ServerStreamTracer.Factory} with default stats implementation.
   */
  public static ServerStreamTracer.Factory getServerStreamTracerFactory() {
    CensusTracingModule censusTracing =
        new CensusTracingModule();
    return censusTracing.getServerTracerFactory();
  }
}
