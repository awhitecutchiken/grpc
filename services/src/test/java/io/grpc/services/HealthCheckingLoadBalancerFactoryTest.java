/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.services;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcAttributes;
import io.grpc.stub.StreamObserver;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Tests for {@link HealthCheckingLoadBalancerFactory}. */
@RunWith(JUnit4.class)
public class HealthCheckingLoadBalancerFactoryTest {
  private static final Attributes.Key<String> SUBCHANNEL_ATTR_KEY =
      Attributes.Key.create("subchannel-attr-for-test");
  private static final int NUM_SUBCHANNELS = 1;
  private final EquivalentAddressGroup[] eags = new EquivalentAddressGroup[NUM_SUBCHANNELS];
  @SuppressWarnings({"rawtypes", "unchecked"})
  private final List<EquivalentAddressGroup>[] eagLists = new List[NUM_SUBCHANNELS];
  private List<EquivalentAddressGroup> resolvedAddressList;
  private final Subchannel[] subchannels = new Subchannel[NUM_SUBCHANNELS];
  private final ManagedChannel[] channels = new ManagedChannel[NUM_SUBCHANNELS];
  private final Server[] servers = new Server[NUM_SUBCHANNELS];
  private final HealthImpl[] healthImpls = new HealthImpl[NUM_SUBCHANNELS];
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock clock = new FakeClock();

  @Mock
  private Factory origLbFactory;
  @Mock
  private LoadBalancer origLb;
  @Mock
  private Helper origHelper;
  // The helper seen by the origLb
  private Helper wrappedHelper;
  @Captor
  ArgumentCaptor<Helper> helperCaptor;
  @Captor
  ArgumentCaptor<Attributes> attrsCaptor;
  @Captor
  ArgumentCaptor<ConnectivityStateInfo> stateCaptor;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;

  private HealthCheckingLoadBalancerFactory hcLbFactory;
  private LoadBalancer hcLbEventDelivery;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      EquivalentAddressGroup eag = new EquivalentAddressGroup(mock(SocketAddress.class));
      eags[i] = eag;
      List<EquivalentAddressGroup> eagList = Arrays.asList(eag);
      eagLists[i] = eagList;
      final Subchannel subchannel = mock(Subchannel.class);
      subchannels[i] = subchannel;
      when(subchannel.getAllAddresses()).thenReturn(eagList);
      HealthImpl healthImpl = new HealthImpl();
      healthImpls[i] = healthImpl;
      Server server =
          InProcessServerBuilder.forName("health-check-test-" + i)
          .addService(healthImpl).directExecutor().build().start();
      servers[i] = server;
      ManagedChannel channel =
          InProcessChannelBuilder.forName("health-check-test-" + i).directExecutor().build();

      channels[i] = channel;
      when(subchannel.asChannel()).thenReturn(channel);
      doAnswer(new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            Attributes attrs = (Attributes) invocation.getArguments()[1];
            when(subchannel.getAttributes()).thenReturn(attrs);
            return subchannel;
          }
        }).when(origHelper).createSubchannel(same(eagList), any(Attributes.class));
    }
    resolvedAddressList = Arrays.asList(eags);
    
    when(origLbFactory.newLoadBalancer(any(Helper.class))).thenReturn(origLb);

    when(origHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(origHelper.getScheduledExecutorService()).thenReturn(clock.getScheduledExecutorService());
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(11L, 21L, 31L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(12L, 22L, 32L);

    hcLbFactory = new HealthCheckingLoadBalancerFactory(
        origLbFactory, backoffPolicyProvider, clock.getTimeProvider());
    final LoadBalancer hcLb = hcLbFactory.newLoadBalancer(origHelper);
    // Make sure all calls into the hcLb is from the syncContext
    hcLbEventDelivery = new LoadBalancer() {
        @Override
        public void handleResolvedAddressGroups(
            final List<EquivalentAddressGroup> servers, final Attributes attributes) {
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                hcLb.handleResolvedAddressGroups(servers, attributes);
              }
            });
        }

        @Override
        public void handleSubchannelState(
            final Subchannel subchannel, final ConnectivityStateInfo stateInfo) {
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                hcLb.handleSubchannelState(subchannel, stateInfo);
              }
            });
        }

        @Override
        public void handleNameResolutionError(Status error) {
          throw new AssertionError("Not supposed to be called");
        }

        @Override
        public void shutdown() {
          throw new AssertionError("Not supposed to be called");
        }
      };
    verify(origLbFactory).newLoadBalancer(helperCaptor.capture());
    final Helper helperSeenByOrigLb = helperCaptor.getValue();
    // Make sure all calls to helperSeenByOrigLb is from the syncContext
    wrappedHelper = new Helper() {
        @Override
        public Subchannel createSubchannel(
            final List<EquivalentAddressGroup> addrs, final Attributes attrs) {
          final AtomicReference<Subchannel> returnedSubchannel = new AtomicReference<Subchannel>();
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                returnedSubchannel.set(helperSeenByOrigLb.createSubchannel(addrs, attrs));
              }
            });
          return returnedSubchannel.get();
        }

        @Override
        public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
          throw new AssertionError("Not supposed to be called");
        }

        @Override
        public void updateBalancingState(
            final ConnectivityState newState, final SubchannelPicker newPicker) {
          syncContext.execute(new Runnable() {
              @Override
              public void run() {
                helperSeenByOrigLb.updateBalancingState(newState, newPicker);
              }
            });
        }

        @Override
        public NameResolver.Factory getNameResolverFactory() {
          throw new AssertionError("Not supposed to be called");
        }

        @Override
        public String getAuthority() {
          throw new AssertionError("Not supposed to be called");
        }
      };
  }

  @After
  public void teardown() throws Exception {
    // Health-check streams are usually not closed in the tests.  Force closing for clean up.
    for (Server server : servers) {
      server.shutdownNow();
      assertThat(server.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }
    for (ManagedChannel channel : channels) {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }
    for (HealthImpl impl : healthImpls) {
      assertThat(impl.checkCalled).isFalse();
    }
  }

  @Test
  public void healthCheckWorks() {
    Attributes resolutionAttrs = attrsWithHealthCheckService("FooService");
    hcLbEventDelivery.handleResolvedAddressGroups(resolvedAddressList, resolutionAttrs);

    verify(origLb).handleResolvedAddressGroups(same(resolvedAddressList), same(resolutionAttrs));
    verify(origHelper, atLeast(0)).getSynchronizationContext();
    verify(origHelper, atLeast(0)).getScheduledExecutorService();
    verifyNoMoreInteractions(origHelper);

    // Simulate that the orignal LB creates Subchannels
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      // EAG attributes are 
      String subchannelAttrValue = "eag attr " + i;
      Attributes attrs = Attributes.newBuilder()
          .set(SUBCHANNEL_ATTR_KEY, subchannelAttrValue).build();
      assertThat(wrappedHelper.createSubchannel(eagLists[i], attrs)).isSameAs(subchannels[i]);
      verify(origHelper).createSubchannel(same(eagLists[i]), attrsCaptor.capture());
      assertThat(attrsCaptor.getValue().get(SUBCHANNEL_ATTR_KEY)).isEqualTo(subchannelAttrValue);
    }

    // Not starting health check until Subchannel is ready
    for (int i = NUM_SUBCHANNELS - 1; i >= 0; i--) {
      Subchannel subchannel = subchannels[i];
      HealthImpl healthImpl = healthImpls[i];
      InOrder inOrder = inOrder(origLb);
      hcLbEventDelivery.handleSubchannelState(
          subchannel, ConnectivityStateInfo.forNonError(CONNECTING));
      hcLbEventDelivery.handleSubchannelState(
          subchannel, ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
      hcLbEventDelivery.handleSubchannelState(
          subchannel, ConnectivityStateInfo.forNonError(IDLE));

      inOrder.verify(origLb).handleSubchannelState(
          same(subchannel), eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      inOrder.verify(origLb).handleSubchannelState(
          same(subchannel), eq(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE)));
      inOrder.verify(origLb).handleSubchannelState(
          same(subchannel), eq(ConnectivityStateInfo.forNonError(IDLE)));
      verifyNoMoreInteractions(origLb);

      assertThat(healthImpl.calls).isEmpty();
      verify(subchannel, never()).asChannel();

      hcLbEventDelivery.handleSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
      verify(subchannel).asChannel();
      assertThat(healthImpl.calls).hasSize(1);
      ServerSideCall serverCall = healthImpl.calls.poll();
      assertThat(serverCall.request).isEqualTo(makeRequest("FooService"));

      // Starting the health check will make the Subchannel appear CONNECTING to the origLb.
      inOrder.verify(origLb).handleSubchannelState(
          same(subchannel), eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      verifyNoMoreInteractions(origLb);

      // Any non-SERVING status will turn the Subchannel to TRANSIENT_FAILURE
      for (ServingStatus servingStatus : new ServingStatus[] {
            ServingStatus.UNKNOWN, ServingStatus.NOT_SERVING, ServingStatus.SERVICE_UNKNOWN}) {
        serverCall.responseObserver.onNext(makeResponse(servingStatus));
        inOrder.verify(origLb).handleSubchannelState(same(subchannel), stateCaptor.capture());
        assertThat(stateCaptor.getValue().getState()).isEqualTo(TRANSIENT_FAILURE);
        Status error = stateCaptor.getValue().getStatus();
        assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
        assertThat(error.getDescription()).isEqualTo(
            "Health-check service responded " + servingStatus + " for 'FooService'");
        verifyNoMoreInteractions(origLb);
      }

      // SERVING status will turn the Subchannel to READY
      serverCall.responseObserver.onNext(makeResponse(ServingStatus.SERVING));
      inOrder.verify(origLb).handleSubchannelState(
          same(subchannel), eq(ConnectivityStateInfo.forNonError(READY)));
      verifyNoMoreInteractions(origLb);
    }
  }

  @Test
  public void healthCheckDisabledWhenServiceNotImplemented() {
  }

  @Test
  public void backoffRetriesWhenServerErroneouslyClosesRpc() {
  }

  @Test
  public void serviceConfigHasNoHealthCheckingInitiallyButDoesLater() {
  }

  @Test
  public void serviceConfigHasHealthCheckingInitiallyButDoesNotLater() {
  }

  @Test
  public void serviceConfigChangesServiceNameWhenRpcActive() {
  }

  @Test
  public void serviceConfigChangesServiceNameWhenRpcInactive() {
  }

  @Test
  public void rpcClosedWhenSubchannelShutdown() {
  }

  private Attributes attrsWithHealthCheckService(@Nullable String serviceName) {
    HashMap<String, Object> serviceConfig = new HashMap<String, Object>();
    HashMap<String, Object> hcConfig = new HashMap<String, Object>();
    hcConfig.put("serviceName", serviceName);
    serviceConfig.put("healthCheckConfig", hcConfig);
    return Attributes.newBuilder()
        .set(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG, serviceConfig).build();
  }

  private HealthCheckRequest makeRequest(String service) {
    return HealthCheckRequest.newBuilder().setService(service).build();
  }

  private HealthCheckResponse makeResponse(ServingStatus status) {
    return HealthCheckResponse.newBuilder().setStatus(status).build();
  }

  private static class HealthImpl extends HealthGrpc.HealthImplBase {
    boolean isImplemented = true;
    boolean checkCalled;
    final LinkedList<ServerSideCall> calls = new LinkedList<ServerSideCall>();

    @Override
    public void check(HealthCheckRequest request,
        StreamObserver<HealthCheckResponse> responseObserver) {
      responseObserver.onError(new UnsupportedOperationException("Should never be called"));
      checkCalled = true;
    }

    @Override
    public void watch(HealthCheckRequest request,
        StreamObserver<HealthCheckResponse> responseObserver) {
      calls.add(new ServerSideCall(request, responseObserver));
    }
  }

  private static class ServerSideCall {
    final HealthCheckRequest request;
    final StreamObserver<HealthCheckResponse> responseObserver;

    ServerSideCall(
        HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
      this.request = request;
      this.responseObserver = responseObserver;
    }
  }
}
