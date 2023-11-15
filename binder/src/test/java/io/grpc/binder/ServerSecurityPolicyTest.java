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

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import static org.junit.Assert.fail;
import android.os.Process;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;

import io.grpc.Status;
import io.grpc.Status.Code;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

@RunWith(RobolectricTestRunner.class)
public final class ServerSecurityPolicyTest {

  private static final String SERVICE1 = "service_one";
  private static final String SERVICE2 = "service_two";
  private static final String SERVICE3 = "service_three";

  private static final int MY_UID = Process.myUid();
  private static final int OTHER_UID = MY_UID + 1;

  ServerSecurityPolicy policy;

  @Test
  public void testDefaultInternalOnly() throws Exception {
    policy = new ServerSecurityPolicy();
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE2)).isEqualTo(Code.OK);
  }

  @Test
  public void testDefaultInternalOnly_legacyApi() {
    policy = new ServerSecurityPolicy();
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode()).isEqualTo(Code.OK);
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode()).isEqualTo(Code.OK);
  }

  @Test
  public void testInternalOnly_AnotherUid() throws Exception {
    policy = new ServerSecurityPolicy();
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE2))
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  @Test
  public void testInternalOnly_AnotherUid_legacyApi() {
    policy = new ServerSecurityPolicy();
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  @Test
  public void testBuilderDefault() throws Exception {
    policy = ServerSecurityPolicy.newBuilder().build();
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  @Test
  public void testBuilderDefault_legacyApi() {
    policy = ServerSecurityPolicy.newBuilder().build();
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode()).isEqualTo(Code.OK);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  @Test
  public void testPerService() throws Exception {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE2, policy((uid) -> Status.OK))
            .build();

    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE2)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE2)).isEqualTo(Code.OK);
  }


  @Test
  public void testPerService_legacyApi() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE2, policy((uid) -> Status.OK))
            .build();

    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode()).isEqualTo(Code.OK);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode()).isEqualTo(Code.OK);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Code.OK);
  }

  @Test
  public void testPerServiceAsync() throws Exception {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE2, asyncPolicy(uid -> {
              // Add some extra future transformation to confirm that a chain
              // of futures gets properly handled.
              ListenableFuture<Void> dependency = Futures.immediateVoidFuture();
              return Futures
                      .transform(dependency, unused -> Status.OK, MoreExecutors.directExecutor());
            }))
            .build();

    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE2)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE2)).isEqualTo(Code.OK);
  }

  @Test
  public void testPerService_failedSecurityPolicyFuture_returnsAFailedFuture() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, asyncPolicy(uid ->
                Futures
                    .immediateFailedFuture(new IllegalStateException("something went wrong"))
            ))
            .build();

    ListenableFuture<Status> statusFuture =
        policy.checkAuthorizationForServiceAsync(MY_UID, SERVICE1);

    assertThrows(ExecutionException.class, statusFuture::get);
  }

  @Test
  public void testPerServiceAsync_cancelledFuture_propagatesStatus() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, asyncPolicy(unused -> Futures.immediateCancelledFuture()))
            .build();

    ListenableFuture<Status> statusFuture =
        policy.checkAuthorizationForServiceAsync(MY_UID, SERVICE1);

    assertThrows(CancellationException.class, statusFuture::get);
  }

  @Test
  public void testPerServiceAsync_interrupted_cancelledFuture() {
    ListeningExecutorService listeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    CountDownLatch unsatisfiedLatch = new CountDownLatch(1);
    ListenableFuture<Status> toBeInterruptedFuture = listeningExecutorService.submit(() -> {
      unsatisfiedLatch.await();  // waits forever
      return null;
    });

    CyclicBarrier barrier = new CyclicBarrier(2);
    Thread testThread = Thread.currentThread();
    new Thread(() -> {
      awaitOrFail(barrier);
      testThread.interrupt();
    }).start();

    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, asyncPolicy(unused -> {
              awaitOrFail(barrier);
              return toBeInterruptedFuture;
            }))
            .build();
    ListenableFuture<Status> statusFuture =
        policy.checkAuthorizationForServiceAsync(MY_UID, SERVICE1);

    assertThrows(InterruptedException.class, statusFuture::get);
    listeningExecutorService.shutdownNow();
  }

  @Test
  public void testPerServiceNoDefault() throws Exception {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, policy((uid) -> Status.INTERNAL))
            .servicePolicy(
                SERVICE2, policy((uid) -> uid == OTHER_UID ? Status.OK : Status.PERMISSION_DENIED))
            .build();

    // Uses the specified policy for service1.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1))
        .isEqualTo(Code.INTERNAL);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.INTERNAL);

    // Uses the specified policy for service2.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE2))
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE2)).isEqualTo(Code.OK);

    // Falls back to the default.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE3)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE3))
        .isEqualTo(Code.PERMISSION_DENIED);
  }
  @Test
  public void testPerServiceNoDefault_legacyApi() {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(SERVICE1, policy((uid) -> Status.INTERNAL))
            .servicePolicy(
                SERVICE2, policy((uid) -> uid == OTHER_UID ? Status.OK : Status.PERMISSION_DENIED))
            .build();

    // Uses the specified policy for service1.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE1).getCode())
        .isEqualTo(Code.INTERNAL);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE1).getCode())
        .isEqualTo(Code.INTERNAL);

    // Uses the specified policy for service2.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE2).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE2).getCode())
        .isEqualTo(Code.OK);

    // Falls back to the default.
    assertThat(policy.checkAuthorizationForService(MY_UID, SERVICE3).getCode())
        .isEqualTo(Code.OK);
    assertThat(policy.checkAuthorizationForService(OTHER_UID, SERVICE3).getCode())
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  @Test
  public void testPerServiceNoDefaultAsync() throws Exception {
    policy =
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(
                SERVICE1,
                asyncPolicy((uid) -> Futures.immediateFuture(Status.INTERNAL)))
            .servicePolicy(
                SERVICE2, asyncPolicy((uid) -> {
                  // Add some extra future transformation to confirm that a chain
                  // of futures gets properly handled.
                  ListenableFuture<Boolean> anotherUidFuture =
                      Futures.immediateFuture(uid == OTHER_UID);
                  return Futures
                      .transform(
                          anotherUidFuture,
                          anotherUid -> anotherUid ? Status.OK : Status.PERMISSION_DENIED,
                          MoreExecutors.directExecutor());
                }))
                .build();

    // Uses the specified policy for service1.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE1)).isEqualTo(Code.INTERNAL);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE1))
        .isEqualTo(Code.INTERNAL);

    // Uses the specified policy for service2.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE2))
        .isEqualTo(Code.PERMISSION_DENIED);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE2)).isEqualTo(Code.OK);

    // Falls back to the default.
    assertThat(checkAuthorizationForServiceAsync(policy, MY_UID, SERVICE3)).isEqualTo(Code.OK);
    assertThat(checkAuthorizationForServiceAsync(policy, OTHER_UID, SERVICE3))
        .isEqualTo(Code.PERMISSION_DENIED);
  }

  /**
   * Shortcut for invoking {@link ServerSecurityPolicy#checkAuthorizationForServiceAsync} without
   * dealing with concurrency details. Returns a {link @Status.Code} for convenience.
   */
  private static Status.Code checkAuthorizationForServiceAsync(
          ServerSecurityPolicy policy,
          int callerUid,
          String service) throws ExecutionException {
    ListenableFuture<Status> statusFuture =
        policy.checkAuthorizationForServiceAsync(callerUid, service);
    return Uninterruptibles.getUninterruptibly(statusFuture).getCode();
  }

  private static SecurityPolicy policy(Function<Integer, Status> func) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return func.apply(uid);
      }
    };
  }

  private static AsyncSecurityPolicy asyncPolicy(Function<Integer, ListenableFuture<Status>> func) {
    return new AsyncSecurityPolicy() {
      @Override
      public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
        return func.apply(uid);
      }
    };
  }

  private static void awaitOrFail(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (BrokenBarrierException e) {
      fail(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail(e.getMessage());
    }
  }
}
