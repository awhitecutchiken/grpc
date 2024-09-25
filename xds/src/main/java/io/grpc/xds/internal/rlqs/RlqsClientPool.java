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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.InsecureChannelCredentials;
import io.grpc.SynchronizationContext;
import io.grpc.xds.RlqsFilterConfig;
import io.grpc.xds.client.Bootstrapper.RemoteServerInfo;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RlqsClientPool {
  private static final Logger logger = Logger.getLogger(RlqsClientPool.class.getName());

  // TODO(sergiitk): [QUESTION] always in sync context?
  private volatile boolean shutdown = false;
  private final SynchronizationContext syncContext = new SynchronizationContext((thread, error) -> {
    String message = "Uncaught exception in RlqsClientPool SynchronizationContext. Panic!";
    logger.log(Level.FINE, message, error);
    throw new RlqsPoolSynchronizationException(message, error);
  });

  private final ConcurrentHashMap<String, RlqsEngine> enginePool = new ConcurrentHashMap<>();
  Set<String> enginesToShutdown = Sets.newConcurrentHashSet();
  private final ScheduledExecutorService scheduler;


  private RlqsClientPool(ScheduledExecutorService scheduler) {
    this.scheduler = checkNotNull(scheduler, "scheduler");
  }

  /** Creates an instance. */
  public static RlqsClientPool newInstance(ScheduledExecutorService scheduler) {
    // TODO(sergiitk): [IMPL] scheduler - consider using GrpcUtil.TIMER_SERVICE.
    // TODO(sergiitk): [IMPL] note that the scheduler has a finite lifetime.
    return new RlqsClientPool(scheduler);
  }

  public void shutdown() {
    if (shutdown) {
      return;
    }
    syncContext.execute(() -> {
      shutdown = true;
      logger.log(Level.FINER, "Shutting down RlqsClientPool");
      enginesToShutdown.clear();
      for (String configHash : enginePool.keySet()) {
        enginePool.get(configHash).shutdown();
      }
      enginePool.clear();
      shutdown = false;
    });
  }

  public RlqsEngine getOrCreateRlqsEngine(RlqsFilterConfig config) {
    final SettableFuture<RlqsEngine> future = SettableFuture.create();
    final String configHash = hashRlqsFilterConfig(config);

    syncContext.execute(() -> {
      if (enginePool.containsKey(configHash)) {
        future.set(enginePool.get(configHash));
        return;
      }
      // TODO(sergiitk): [IMPL] get from bootstrap.
      RemoteServerInfo rlqsServer = RemoteServerInfo.create(config.rlqsService().targetUri(),
          InsecureChannelCredentials.create());
      RlqsEngine rlqsEngine = new RlqsEngine(
          rlqsServer,
          config.domain(),
          config.bucketMatchers(),
          configHash,
          scheduler);

      enginePool.put(configHash, rlqsEngine);
      future.set(enginePool.get(configHash));
    });
    try {
      // TODO(sergiitk): [IMPL] clarify time
      return future.get(1, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      // TODO(sergiitk): [IMPL] handle properly
      throw new RuntimeException(e);
    }
  }

  private String hashRlqsFilterConfig(RlqsFilterConfig config) {
    // TODO(sergiitk): [QUESTION] better name? - ask Eric.
    // TODO(sergiitk): [DESIGN] the key should be hashed (domain + buckets) merged config?
    // TODO(sergiitk): [IMPL] Hash buckets
    return config.rlqsService().targetUri() + config.domain();
  }

  /**
   * Throws when fail to bootstrap or initialize the XdsClient.
   */
  public static final class RlqsPoolSynchronizationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RlqsPoolSynchronizationException(String message, Throwable cause) {
      super(message, cause);
    }
  }


}
