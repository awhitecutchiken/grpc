/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.handshaker.tokenmanager;

import io.grpc.s2a.handshaker.S2AIdentity;
import java.lang.reflect.Method;
import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;

/** Manages access tokens for authenticating to the S2A. */
@ThreadSafe
public final class AccessTokenManager {
  private final TokenFetcher tokenFetcher;

  /** Creates an {@code AccessTokenManager} based on the environment where the application runs. */
  @SuppressWarnings("RethrowReflectiveOperationExceptionAsLinkageError")
  public static Optional<AccessTokenManager> create() {
    Optional<?> tokenFetcher;
    try {
      Class<?> singleTokenFetcherClass =
          Class.forName("io.grpc.s2a.handshaker.tokenmanager.SingleTokenFetcher");
      Method createTokenFetcher = singleTokenFetcherClass.getMethod("create");
      tokenFetcher = (Optional) createTokenFetcher.invoke(null);
    } catch (ClassNotFoundException e) {
      tokenFetcher = Optional.empty();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
    return tokenFetcher.isPresent()
        ? Optional.of(new AccessTokenManager((TokenFetcher) tokenFetcher.get()))
        : Optional.empty();
  }

  private AccessTokenManager(TokenFetcher tokenFetcher) {
    this.tokenFetcher = tokenFetcher;
  }

  /** Returns an access token when no identity is specified. */
  public String getDefaultToken() {
    return tokenFetcher.getDefaultToken();
  }

  /** Returns an access token for the given identity. */
  public String getToken(S2AIdentity identity) {
    return tokenFetcher.getToken(identity);
  }
}