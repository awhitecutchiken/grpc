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

package io.grpc.rls.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.ChildPolicyReportingHelper.ChildLbStatusListener;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.internal.LbPolicyConfiguration.InvalidChildPolicyConfigException;
import io.grpc.rls.internal.LbPolicyConfiguration.RefCountedChildPolicyWrapperFactory;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LbPolicyConfigurationTest {

  private final RefCountedChildPolicyWrapperFactory factory =
      new RefCountedChildPolicyWrapperFactory(
          new ChildLoadBalancerHelperProvider(
              mock(Helper.class),
              mock(SubchannelStateManager.class),
              mock(SubchannelPicker.class)),
          mock(ChildLbStatusListener.class));

  @Test
  public void childPolicyWrapper_refCounted() {
    String target = "target";
    ChildPolicyWrapper childPolicy = factory.createOrGet(target);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);

    ChildPolicyWrapper childPolicy2 = factory.createOrGet(target);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);
    assertThat(childPolicy2).isEqualTo(childPolicy);

    factory.release(childPolicy2);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);

    factory.release(childPolicy);
    assertThat(factory.childPolicyMap).isEmpty();

    try {
      factory.release(childPolicy);
      fail("should not be able to access already released policy");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("already released");
    }
  }

  @Test
  public void childLoadBalancingPolicy_effectiveChildPolicy() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    ChildLoadBalancingPolicy childLbPolicy =
        new ChildLoadBalancingPolicy(
            "targetFieldName",
            ImmutableMap.<String, Object>of("foo", "bar"),
            mockProvider);

    assertThat(childLbPolicy.getEffectiveChildPolicy("target"))
        .containsExactly("foo", "bar", "targetFieldName", "target");
    assertThat(childLbPolicy.getEffectiveLbProvider()).isEqualTo(mockProvider);
  }

  @Test
  public void childLoadBalancingPolicy_noPolicyProvided() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    when(mockProvider.getPolicyName()).thenReturn("rls");
    when(mockProvider.isAvailable()).thenReturn(true);

    LoadBalancerRegistry.getDefaultRegistry().register(mockProvider);
    try {
      ChildLoadBalancingPolicy.create(
          "targetFieldName",
          ImmutableList.<Map<String, ?>>of(
              ImmutableMap.<String, Object>of(
                  "rls", ImmutableMap.of(), "rls2", ImmutableMap.of())));
      fail("parsing exception expected");
    } catch (InvalidChildPolicyConfigException e) {
      assertThat(e).hasMessageThat()
          .contains("childPolicy should have exactly one loadbalancing policy");
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockProvider);
    }
  }

  @Test
  public void childLoadBalancingPolicy_tooManyChildPolicies() {
    try {
      ChildLoadBalancingPolicy
          .create("targetFieldName", ImmutableList.<Map<String, ?>>of());
      fail("parsing exception expected");
    } catch (InvalidChildPolicyConfigException e) {
      assertThat(e).hasMessageThat().contains("no valid childPolicy found");
    }
  }
}
