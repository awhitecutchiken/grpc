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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.XdsLoadBalancer.XdsConfig;
import java.util.Map;

/** Fallback load balancer. Handles fallback policy changes. */
final class FallbackLb extends ForwardingLoadBalancer {

  private final LoadBalancerRegistry lbRegistry;
  private final GracefulSwitchLoadBalancer fallbackPolicyLb;

  FallbackLb(Helper fallbackLbHelper) {
    this(fallbackLbHelper, LoadBalancerRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  FallbackLb(Helper fallbackLbHelper, LoadBalancerRegistry lbRegistry) {
    this.lbRegistry = lbRegistry;
    fallbackPolicyLb = new GracefulSwitchLoadBalancer(fallbackLbHelper);
  }

  @Override
  protected LoadBalancer delegate() {
    return fallbackPolicyLb;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // In the future, xdsConfig can be gotten directly by
    // resolvedAddresses.getLoadBalancingPolicyConfig()
    Attributes attributes = resolvedAddresses.getAttributes();
    Map<String, ?> newRawLbConfig = checkNotNull(
        attributes.get(ATTR_LOAD_BALANCING_CONFIG), "ATTR_LOAD_BALANCING_CONFIG not available");
    ConfigOrError cfg =
        XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(newRawLbConfig, lbRegistry);
    if (cfg.getError() != null) {
      throw cfg.getError().asRuntimeException();
    }
    XdsConfig xdsConfig = (XdsConfig) cfg.getConfig();

    LbConfig fallbackPolicy = xdsConfig.fallbackPolicy;
    String newFallbackPolicyName = fallbackPolicy.getPolicyName();
    fallbackPolicyLb.switchTo(lbRegistry.getProvider(newFallbackPolicyName));
    ResolvedAddresses fallbackResolvedAddresses = resolvedAddresses.toBuilder()
        .setAttributes(attributes.toBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, fallbackPolicy.getRawConfigValue()).build())
        .build();
    fallbackPolicyLb.handleResolvedAddresses(fallbackResolvedAddresses);
  }
}
