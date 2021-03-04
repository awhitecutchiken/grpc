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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import io.grpc.ClientInterceptor;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.ServerInterceptor;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Defines the parsing functionality of an HTTP filter. A Filter may optionally implement either
 * {@link ClientInterceptorBuilder} or {@link ServerInterceptorBuilder} or both, indicating it is
 * capable of working on the client side or server side or both, respectively.
 */
interface Filter {

  /**
   * The proto message types supported by this filter. A filter will be registered by each of its
   * supported message types.
   */
  String[] typeUrls();

  /**
   * Parses the top-level filter config from raw proto message.
   */
  StructOrError<? extends FilterConfig> parseFilterConfig(Any rawProtoMessage);

  /** Parses the per-filter override filter config from raw proto message. */
  StructOrError<? extends FilterConfig> parseFilterConfigOverride(Any rawProtoMessage);

  /** Represents an opaque data structure holding configuration for a filter. */
  interface FilterConfig {
    String typeUrl();
  }

  /** Uses the FilterConfigs produced above to produce an HTTP filter interceptor for clients. */
  interface ClientInterceptorBuilder {
    @Nullable
    ClientInterceptor buildClientInterceptor(
        FilterConfig config, @Nullable FilterConfig overrideConfig, PickSubchannelArgs args,
        ScheduledExecutorService scheduler);
  }

  // Server side filters are not currently supported, but this interface is defined for clarity.
  interface ServerInterceptorBuilder {
    ServerInterceptor buildServerInterceptor(
        FilterConfig config, @Nullable FilterConfig overrideConfig);
  }

  final class StructOrError<T> {
    /**
     * Returns a {@link StructOrError} for the successfully converted data object.
     */
    static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    static <T> StructOrError<T> fromError(String errorDetail) {
      return new StructOrError<>(errorDetail);
    }

    final String errorDetail;
    final T struct;

    private StructOrError(T struct) {
      this.struct = checkNotNull(struct, "struct");
      this.errorDetail = null;
    }

    private StructOrError(String errorDetail) {
      this.struct = null;
      this.errorDetail = checkNotNull(errorDetail, "errorDetail");
    }
  }

  /**
   * A registry for all supported {@link Filter}s. Filters can by queried from the registry
   * by any of the {@link Filter#typeUrls(), type URLs}.
   */
  final class Registry {
    static Registry GLOBAL_REGISTRY =
        newRegistry().register(FaultFilter.INSTANCE, RouterFilter.INSTANCE);

    private final Map<String, Filter> supportedFilters = new HashMap<>();

    private Registry() {}

    @VisibleForTesting
    static Registry newRegistry() {
      return new Registry();
    }

    @VisibleForTesting
    Registry register(Filter... filters) {
      for (Filter filter : filters) {
        for (String typeUrl : filter.typeUrls()) {
          supportedFilters.put(typeUrl, filter);
        }
      }
      return this;
    }

    @Nullable
    Filter get(String typeUrl) {
      return supportedFilters.get(typeUrl);
    }
  }
}
