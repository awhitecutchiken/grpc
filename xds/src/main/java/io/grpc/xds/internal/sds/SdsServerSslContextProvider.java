/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.core.Node;
import java.util.concurrent.Executor;

/** A server SslContext provider that uses SDS to fetch secrets. */
final class SdsServerSslContextProvider extends ServerSslContextProvider {

  private SdsSslContextProviderHelper sdsSslContextProviderHelper;

  private SdsServerSslContextProvider(
      Node node,
      SdsSecretConfig certSdsConfig,
      SdsSecretConfig validationContextSdsConfig,
      Executor watcherExecutor,
      Executor channelExecutor,
      DownstreamTlsContext downstreamTlsContext) {
    super(downstreamTlsContext);
    this.sdsSslContextProviderHelper =
        new SdsSslContextProviderHelper(
            node,
            certSdsConfig,
            validationContextSdsConfig,
            null,
            watcherExecutor,
            channelExecutor,
            this);
  }

  static SdsServerSslContextProvider getProvider(
      DownstreamTlsContext downstreamTlsContext,
      Node node,
      Executor watcherExecutor,
      Executor channelExecutor) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();

    SdsSecretConfig certSdsConfig = null;
    if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
      certSdsConfig = commonTlsContext.getTlsCertificateSdsSecretConfigs(0);
    }

    SdsSecretConfig validationContextSdsConfig = null;
    if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
      validationContextSdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
    }
    return new SdsServerSslContextProvider(
        node,
        certSdsConfig,
        validationContextSdsConfig,
        watcherExecutor,
        channelExecutor,
        downstreamTlsContext);
  }

  @Override
  public void addCallback(Callback callback, Executor executor) {
    sdsSslContextProviderHelper.addCallback(callback, executor);
  }

  @Override
  void close() {
    sdsSslContextProviderHelper.close();
  }
}
