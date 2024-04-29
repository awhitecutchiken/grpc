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

import static com.google.common.truth.Truth.assertThat;

import com.beust.jcommander.JCommander;
import io.grpc.s2a.handshaker.S2AIdentity;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SingleTokenAccessTokenManagerTest {
  private static final S2AIdentity IDENTITY = S2AIdentity.fromSpiffeId("spiffe_id");
  private static final String TOKEN = "token";
  private static final String[] SET_TOKEN = {"--s2a_access_token", TOKEN};
  private static final SingleTokenFetcher.Flags FLAGS = new SingleTokenFetcher.Flags();

  @Before
  public void setUp() {
    FLAGS.reset();
  }

  @Test
  public void getDefaultToken_success() throws Exception {
    JCommander.newBuilder().addObject(FLAGS).build().parse(SET_TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getDefaultToken()).isEqualTo(TOKEN);
  }

  @Test
  public void getToken_success() throws Exception {
    JCommander.newBuilder().addObject(FLAGS).build().parse(SET_TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getToken(IDENTITY)).isEqualTo(TOKEN);
  }

  @Test
  public void getToken_noEnvironmentVariable() throws Exception {
    assertThat(SingleTokenFetcher.create()).isEmpty();
  }

  @Test
  public void create_success() throws Exception {
    JCommander.newBuilder().addObject(FLAGS).build().parse(SET_TOKEN);
    Optional<AccessTokenManager> manager = AccessTokenManager.create();
    assertThat(manager).isPresent();
    assertThat(manager.get().getToken(IDENTITY)).isEqualTo(TOKEN);
  }

  @Test
  public void create_noEnvironmentVariable() throws Exception {
    assertThat(AccessTokenManager.create()).isEmpty();
  }
}