/*
 * Copyright 2023 The gRPC Authors
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

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import java.util.Map;

/**
 * Providers a {@link WeightedRoundRobinLoadBalancer}.
 * */
final class WeightedRoundRobinLoadBalancerProvider extends LoadBalancerProvider {

  private static final long MIN_WEIGHT_UPDATE_PERIOD_NANOS = 10_000_000L; // 100ms

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new WeightedRoundRobinLoadBalancer(helper, TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "weighted_round_robin_experimental";
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    Long blackoutPeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "blackoutPeriod");
    Long weightExpirationPeriodNanos =
            JsonUtil.getStringAsDuration(rawConfig, "weightExpirationPeriod");
    Long oobReportingPeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "oobReportingPeriod");
    Boolean enableOobLoadReport = JsonUtil.getBoolean(rawConfig, "enableOobLoadReport");
    Long weightUpdatePeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "weightUpdatePeriod");

    WeightedRoundRobinLoadBalancerConfig.Builder configBuilder =
            new WeightedRoundRobinLoadBalancerConfig.Builder();
    if (blackoutPeriodNanos != null) {
      configBuilder.setBlackoutPeriodNanos(blackoutPeriodNanos);
    }
    if (weightExpirationPeriodNanos != null) {
      configBuilder.setWeightExpirationPeriodNanos(weightExpirationPeriodNanos);
    }
    if (oobReportingPeriodNanos != null) {
      configBuilder.setEnableOobLoadReport(enableOobLoadReport);
    }
    if (weightUpdatePeriodNanos != null) {
      configBuilder.setWeightUpdatePeriodNanos(weightUpdatePeriodNanos);
      if (weightUpdatePeriodNanos < MIN_WEIGHT_UPDATE_PERIOD_NANOS) {
        configBuilder.setWeightUpdatePeriodNanos(MIN_WEIGHT_UPDATE_PERIOD_NANOS);
      }
    }
    return ConfigOrError.fromConfig(configBuilder.build());
  }
}
