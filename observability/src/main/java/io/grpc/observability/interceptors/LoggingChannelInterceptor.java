/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability.interceptors;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ExperimentalApi;
import io.grpc.MethodDescriptor;

/** A channel provider that injects logging interceptor. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8869")
public final class LoggingChannelInterceptor implements ClientInterceptor {

  public interface Factory {
    public ClientInterceptor create();
  }

  public static class FactoryImpl implements Factory {

    @Override
    public ClientInterceptor create() {
      return new LoggingChannelInterceptor();
    }
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    // TODO(dnvindhya) implement the interceptor
    return null;
  }
}
