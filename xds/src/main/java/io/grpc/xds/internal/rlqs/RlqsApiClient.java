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

package io.grpc.xds.internal.rlqs;

import com.google.protobuf.Duration;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaResponse.BucketAction.QuotaAssignmentAction;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaServiceGrpc.RateLimitQuotaServiceStub;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports;
import io.envoyproxy.envoy.service.rate_limit_quota.v3.RateLimitQuotaUsageReports.BucketQuotaUsage;
import io.envoyproxy.envoy.type.v3.RateLimitStrategy;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RlqsApiClient {
  private static final Logger logger = Logger.getLogger(RlqsApiClient.class.getName());

  private final RemoteServerInfo serverInfo;
  private final String domain;
  private final RlqsApiClientInternal rlqsApiClient;
  private final RlqsBucketCache bucketCache;

  RlqsApiClient(RemoteServerInfo serverInfo, String domain, RlqsBucketCache bucketCache) {
    this.serverInfo = serverInfo;
    this.domain = domain;
    this.rlqsApiClient = new RlqsApiClientInternal(serverInfo);
    this.bucketCache = bucketCache;
  }

  void sendInitialUsageReport(RlqsBucket bucket) {
    rlqsApiClient.reportUsage(RateLimitQuotaUsageReports.newBuilder()
        .setDomain(domain)
        .addBucketQuotaUsages(toUsageReport(bucket))
        .build());
  }


  void sendUsageReports() {
    RateLimitQuotaUsageReports.Builder reports = RateLimitQuotaUsageReports.newBuilder();
    for (RlqsBucket bucket : bucketCache.getBucketsToReport()) {
      reports.addBucketQuotaUsages(toUsageReport(bucket));
    }
    rlqsApiClient.reportUsage(reports.build());
  }

  void abandonBucket(RlqsBucketId bucketId) {
    bucketCache.deleteBucket(bucketId);
  }

  void updateBucketAssignment(
      RlqsBucketId bucketId, RateLimitStrategy rateLimitStrategy, Duration duration) {
    // Deadline.after(Durations.toMillis(ttl), TimeUnit.MILLISECONDS);
  }

  BucketQuotaUsage toUsageReport(RlqsBucket bucket) {
    return null;
  }

  public void shutdown() {
    logger.log(Level.FINER, "Shutting down RlqsApiClient to {0}", serverInfo.target());
    // TODO(sergiitk): [IMPL] RlqsApiClient shutdown
  }

  private class RlqsApiClientInternal {
    private final ManagedChannel channel;
    private final RateLimitQuotaServiceStub stub;
    private final ClientCallStreamObserver<RateLimitQuotaUsageReports> clientCallStream;

    RlqsApiClientInternal(RemoteServerInfo serverInfo) {
      channel = Grpc.newChannelBuilder(serverInfo.target(), serverInfo.channelCredentials())
          .keepAliveTime(10, TimeUnit.SECONDS)
          .keepAliveWithoutCalls(true)
          .build();
      // keepalive?
      // TODO(sergiitk): [IMPL] Manage State changes?
      stub = RateLimitQuotaServiceGrpc.newStub(channel);
      clientCallStream = (ClientCallStreamObserver<RateLimitQuotaUsageReports>)
          stub.streamRateLimitQuotas(new RlqsStreamObserver());
      // TODO(sergiitk): [IMPL] set on ready handler?
    }

    void reportUsage(RateLimitQuotaUsageReports usageReports) {
      clientCallStream.onNext(usageReports);
    }

    /**
     * RLQS Stream observer.
     *
     * <p>See {@link io.grpc.alts.internal.AltsHandshakerStub.Reader} for examples.
     * See {@link io.grpc.stub.ClientResponseObserver} for flow control examples.
     */
    private class RlqsStreamObserver implements StreamObserver<RateLimitQuotaResponse> {
      @Override
      public void onNext(RateLimitQuotaResponse response) {
        for (BucketAction bucketAction : response.getBucketActionList()) {
          switch (bucketAction.getBucketActionCase()) {
            case ABANDON_ACTION:
              abandonBucket(RlqsBucketId.fromEnvoyProto(bucketAction.getBucketId()));
              break;
            case QUOTA_ASSIGNMENT_ACTION:
              QuotaAssignmentAction quotaAssignmentAction = bucketAction.getQuotaAssignmentAction();
              updateBucketAssignment(RlqsBucketId.fromEnvoyProto(bucketAction.getBucketId()),
                  quotaAssignmentAction.getRateLimitStrategy(),
                  quotaAssignmentAction.getAssignmentTimeToLive());
              break;
            default:
              // TODO(sergiitk): error
          }
        }
      }

      @Override
      public void onError(Throwable t) {

      }

      @Override
      public void onCompleted() {

      }
    }
  }
}
