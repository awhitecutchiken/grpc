/*
 * Copyright 2021 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingHandler.FilterChainSelector;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import io.grpc.xds.XdsServerBuilder.XdsServingStatusListener;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

final class XdsServerWrapper extends Server {
  private static final Logger logger = Logger.getLogger(XdsServerWrapper.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
          // TODO(chengyuanzhang): implement cleanup.
        }
      });

  public static final Attributes.Key<ServerRoutingConfig> ATTR_SERVER_ROUTING_CONFIG =
          Attributes.Key.create("io.grpc.xds.ServerWrapper.serverRoutingConfig");

  @VisibleForTesting
  static final long RETRY_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);
  private final String listenerAddress;
  private final ServerBuilder<?> delegateBuilder;
  private boolean sharedTimeService;
  private final ScheduledExecutorService timeService;
  private final FilterRegistry filterRegistry;
  private final ThreadSafeRandom random;
  private final XdsClientPoolFactory xdsClientPoolFactory;
  private final XdsServingStatusListener listener;
  private final AtomicReference<FilterChainSelector> filterChainSelectorRef;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private boolean isServing;
  private final CountDownLatch internalTerminationLatch = new CountDownLatch(1);
  private final SettableFuture<Exception> initialStartFuture = SettableFuture.create();
  private boolean initialStarted;
  private ScheduledHandle restartTimer;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private DiscoveryState discoveryState;
  private volatile Server delegate;

  XdsServerWrapper(
      String listenerAddress,
      ServerBuilder<?> delegateBuilder,
      XdsServingStatusListener listener,
      AtomicReference<FilterChainSelector> filterChainSelectorRef,
      XdsClientPoolFactory xdsClientPoolFactory,
      FilterRegistry filterRegistry) {
    this(listenerAddress, delegateBuilder, listener, filterChainSelectorRef, xdsClientPoolFactory,
            filterRegistry, SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE));
    sharedTimeService = true;
  }

  @VisibleForTesting
  XdsServerWrapper(
          String listenerAddress,
          ServerBuilder<?> delegateBuilder,
          XdsServingStatusListener listener,
          AtomicReference<FilterChainSelector> filterChainSelectorRef,
          XdsClientPoolFactory xdsClientPoolFactory,
          FilterRegistry filterRegistry,
          ScheduledExecutorService timeService) {
    this.listenerAddress = checkNotNull(listenerAddress, "listenerAddress");
    this.delegateBuilder = checkNotNull(delegateBuilder, "delegateBuilder");
    this.delegateBuilder.intercept(new ConfigApplyingInterceptor());
    this.listener = checkNotNull(listener, "listener");
    this.filterChainSelectorRef = checkNotNull(filterChainSelectorRef, "filterChainSelectorRef");
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    this.timeService = checkNotNull(timeService, "timeService");
    this.filterRegistry = checkNotNull(filterRegistry,"filterRegistry");
    this.random = ThreadSafeRandomImpl.instance;
    this.delegate = delegateBuilder.build();
  }

  @Override
  public Server start() throws IOException {
    checkState(started.compareAndSet(false, true), "Already started");
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        internalStart();
      }
    });
    Exception exception;
    try {
      exception = initialStartFuture.get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
    if (exception != null) {
      throw (exception instanceof IOException) ? (IOException) exception :
              new IOException(exception);
    }
    return this;
  }

  private void internalStart() {
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      StatusException statusException = Status.UNAVAILABLE.withDescription(
              "Failed to initialize xDS").withCause(e).asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      return;
    }
    xdsClient = xdsClientPool.getObject();
    // TODO(chengyuanzhang): add an API on XdsClient indicating if it is using v3, don't get
    //  from bootstrap.
    boolean useProtocolV3 = xdsClient.getBootstrapInfo().getServers().get(0).isUseProtocolV3();
    String listenerTemplate = xdsClient.getBootstrapInfo().getServerListenerResourceNameTemplate();
    if (!useProtocolV3 || listenerTemplate == null) {
      StatusException statusException =
          Status.UNAVAILABLE.withDescription(
              "Can only support xDS v3 with listener resource name template").asException();
      listener.onNotServing(statusException);
      initialStartFuture.set(statusException);
      xdsClient = xdsClientPool.returnObject(xdsClient);
      return;
    }
    discoveryState = new DiscoveryState(listenerTemplate.replaceAll("%s", listenerAddress));
  }

  @Override
  public Server shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdown();
        }
        internalShutdown();
      }
    });
    return this;
  }

  @Override
  public Server shutdownNow() {
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!delegate.isShutdown()) {
          delegate.shutdownNow();
        }
        internalShutdown();
      }
    });
    return this;
  }

  // Must run in SynchronizationContext
  private void internalShutdown() {
    logger.log(Level.FINER, "Shutting down XdsServerWrapper");
    if (discoveryState != null) {
      discoveryState.shutdown();
    }
    if (xdsClient != null) {
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
    if (restartTimer != null) {
      restartTimer.cancel();
    }
    if (sharedTimeService) {
      SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timeService);
    }
    isServing = false;
    internalTerminationLatch.countDown();
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean isTerminated() {
    return internalTerminationLatch.getCount() == 0 && delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long startTime = System.nanoTime();
    if (!internalTerminationLatch.await(timeout, unit)) {
      return false;
    }
    long remainingTime = unit.toNanos(timeout) - (System.nanoTime() - startTime);
    return delegate.awaitTermination(remainingTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void awaitTermination() throws InterruptedException {
    internalTerminationLatch.await();
    delegate.awaitTermination();
  }

  @Override
  public int getPort() {
    return delegate.getPort();
  }

  @Override
  public List<? extends SocketAddress> getListenSockets() {
    return delegate.getListenSockets();
  }

  @Override
  public List<ServerServiceDefinition> getServices() {
    return delegate.getServices();
  }

  @Override
  public List<ServerServiceDefinition> getImmutableServices() {
    return delegate.getImmutableServices();
  }

  @Override
  public List<ServerServiceDefinition> getMutableServices() {
    return delegate.getMutableServices();
  }

  // Must run in SynchronizationContext
  private void startDelegateServer() {
    if (restartTimer != null && restartTimer.isPending()) {
      return;
    }
    if (isServing) {
      return;
    }
    if (delegate.isShutdown()) {
      delegate = delegateBuilder.build();
    }
    try {
      delegate.start();
      listener.onServing();
      isServing = true;
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(null);
      }
    } catch (IOException e) {
      logger.log(Level.FINE, "Fail to start delegate server: {0}", e);
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(e);
      }
      restartTimer = syncContext.schedule(
        new RestartTask(), RETRY_DELAY_NANOS, TimeUnit.NANOSECONDS, timeService);
    }
  }

  private final class RestartTask implements Runnable {
    @Override
    public void run() {
      startDelegateServer();
    }
  }

  private final class DiscoveryState implements LdsResourceWatcher {
    private final String resourceName;
    private final Map<String, RouteDiscoveryState> routeDiscoveryStates = new HashMap<>();
    private final Map<String, ServerRoutingConfig> routingConfigs = new HashMap<>();
    // Most recently discovered filter chains.
    private Map<String, FilterChain> filterChains = new HashMap<>();
    // Most recently discovered default filter chain.
    @Nullable
    private FilterChain defaultFilterChain;
    // Config for the most recently discovered default filter chain.
    @Nullable
    private ServerRoutingConfig defaultRoutingConfig;
    private boolean stopped;
    // This flag helps to manage sslContextProviderSuppliers release. It happens when:
    // 1. Upon LdsUpdate when its rds configs not fully arrived, release old suppliers cached in
    //    filterChains.
    // 2. Upon successful selector update for the newest LDS for the first time, release suppliers
    //    from the previous selectorRef content.
    // 3. Upon permanent errors, replace old selector with a noop selector, release suppliers
    //    from the previous selectorRef content.
    // 4. This State shutdown.
    private boolean inflight = false;

    private DiscoveryState(String resourceName) {
      this.resourceName = checkNotNull(resourceName, "resourceName");
      xdsClient.watchLdsResource(resourceName, this);
    }

    @Override
    public void onChanged(final LdsUpdate update) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          checkNotNull(update.listener(), "update");
          if (inflight) {
            releaseSuppliersInFlight();
          } else {
            inflight = true;
          }
          filterChains.clear();
          for (FilterChain filterChain: update.listener().getFilterChains()) {
            filterChains.put(filterChain.getName(), filterChain);
          }
          defaultFilterChain = update.listener().getDefaultFilterChain();
          List<FilterChain> allFilterChains = update.listener().getFilterChains();
          if (defaultFilterChain != null) {
            allFilterChains = new ArrayList<>(update.listener().getFilterChains());
            allFilterChains.add(defaultFilterChain);
          }
          routingConfigs.clear();
          List<String> currentRdsResources = new ArrayList<>();
          for (FilterChain filterChain : allFilterChains) {
            HttpConnectionManager hcm = filterChain.getHttpConnectionManager();
            if (hcm.virtualHosts() != null) {
                routingConfigs.put(filterChain.getName(),
                        ServerRoutingConfig.create(hcm.httpFilterConfigs(), hcm.virtualHosts()));
            } else {
              currentRdsResources.add(hcm.rdsName());
              RouteDiscoveryState rdsState = routeDiscoveryStates.get(hcm.rdsName());
              if (rdsState == null) {
                rdsState = new RouteDiscoveryState(hcm.rdsName(), filterChain,
                        hcm.httpFilterConfigs());
                routeDiscoveryStates.put(hcm.rdsName(), rdsState);
              }
              xdsClient.watchRdsResource(hcm.rdsName(), rdsState);
            }
          }
          for (String rdsName : routeDiscoveryStates.keySet()) {
            if (!currentRdsResources.contains(rdsName)) {
              xdsClient.cancelRdsResourceWatch(rdsName, routeDiscoveryStates.get(rdsName));
            }
          }
          routeDiscoveryStates.keySet().retainAll(currentRdsResources);
          if (defaultFilterChain != null) {
            defaultRoutingConfig = routingConfigs.get(defaultFilterChain.getName());
          } else {
            defaultRoutingConfig = null;
          }
          routingConfigs.keySet().retainAll(filterChains.keySet());
          maybeUpdateSelector();
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          StatusException statusException = Status.UNAVAILABLE.withDescription(
                  "Listener " + resourceName + " unavailable").asException();
          handleConfigNotFound(statusException);
        }
      });
    }

    @Override
    public void onError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (stopped) {
            return;
          }
          boolean isPermanentError = isPermanentError(error);
          logger.log(Level.FINE, "{0} error from XdsClient: {1}",
                  new Object[]{isPermanentError ? "Permanent" : "Transient", error});
          if (isPermanentError) {
            handleConfigNotFound(error.asException());
          } else if (!isServing) {
            listener.onNotServing(error.asException());
          }
        }
      });
    }

    private void shutdown() {
      stopped = true;
      cleanUpRouteDiscoveryStates();
      logger.log(Level.FINE, "Stop watching LDS resource {0}", resourceName);
      xdsClient.cancelLdsResourceWatch(resourceName, this);
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      releaseSuppliersInFlight();
    }

    private void handleConfigDiscovered(
        FilterChain filterChain, ServerRoutingConfig routingConfig) {
      if (Objects.equals(filterChain, defaultFilterChain)) {
        defaultRoutingConfig = routingConfig;
      } else {
        routingConfigs.put(filterChain.getName(), routingConfig);
      }
      maybeUpdateSelector();
    }

    // Update the selector to use the most recently updated configs only after all
    // RouteConfigurations (including default) have been discovered.
    private void maybeUpdateSelector() {
      if (routingConfigs.size() == filterChains.size()
              && (defaultFilterChain == null) == (defaultRoutingConfig == null)) {
        Map<FilterChain, ServerRoutingConfig> filterChainRouting = new HashMap<>();
        for (String name : filterChains.keySet()) {
          filterChainRouting.put(filterChains.get(name), routingConfigs.get(name));
        }
        FilterChainSelector selector = new FilterChainSelector(
            ImmutableMap.copyOf(filterChainRouting),
            defaultFilterChain == null ? null : defaultFilterChain.getSslContextProviderSupplier(),
            defaultRoutingConfig);
        List<SslContextProviderSupplier> toRelease = new ArrayList<>();
        if (inflight) {
          toRelease = getSuppliersInUse();
          inflight = false;
        }
        filterChainSelectorRef.set(selector);
        for (SslContextProviderSupplier e: toRelease) {
          e.close();
        }
        startDelegateServer();
      }
    }

    private void handleConfigNotFound(StatusException exception) {
      cleanUpRouteDiscoveryStates();
      List<SslContextProviderSupplier> toRelease = getSuppliersInUse();
      filterChainSelectorRef.set(FilterChainSelector.NO_FILTER_CHAIN);
      for (SslContextProviderSupplier s: toRelease) {
        s.close();
      }
      if (!initialStarted) {
        initialStarted = true;
        initialStartFuture.set(exception);
      }
      if (restartTimer != null) {
        restartTimer.cancel();
      }
      if (!delegate.isShutdown()) {
        delegate.shutdown();  // let in-progress calls finish
      }
      isServing = false;
      listener.onNotServing(exception);
    }

    private void cleanUpRouteDiscoveryStates() {
      for (RouteDiscoveryState rdsState : routeDiscoveryStates.values()) {
        String rdsName = rdsState.resourceName;
        logger.log(Level.FINE, "Stop watching RDS resource {0}", rdsName);
        xdsClient.cancelRdsResourceWatch(rdsName, rdsState);
      }
      routeDiscoveryStates.clear();
    }

    private List<SslContextProviderSupplier> getSuppliersInUse() {
      List<SslContextProviderSupplier> toRelease = new ArrayList<>();
      FilterChainSelector selector = filterChainSelectorRef.get();
      if (selector != null) {
        for (FilterChain f: selector.getRoutingConfigs().keySet()) {
          if (f.getSslContextProviderSupplier() != null) {
            toRelease.add(f.getSslContextProviderSupplier());
          }
        }
        SslContextProviderSupplier defaultSupplier =
                selector.getDefaultSslContextProviderSupplier();
        if (defaultSupplier != null) {
          toRelease.add(defaultSupplier);
        }
      }
      return toRelease;
    }

    private void releaseSuppliersInFlight() {
      SslContextProviderSupplier supplier;
      for (FilterChain filterChain : filterChains.values()) {
        supplier = filterChain.getSslContextProviderSupplier();
        if (supplier != null) {
          supplier.close();
        }
      }
      if (defaultFilterChain != null
              && (supplier = defaultFilterChain.getSslContextProviderSupplier()) != null) {
        supplier.close();
      }
    }

    private final class RouteDiscoveryState implements RdsResourceWatcher {
      private final String resourceName;
      private final FilterChain filterChain;
      private final List<NamedFilterConfig> httpFilterConfigs;

      private RouteDiscoveryState(String resourceName, FilterChain filterChain,
          List<NamedFilterConfig> httpFilterConfigs) {
        this.resourceName = checkNotNull(resourceName, "resourceName");
        this.filterChain = checkNotNull(filterChain, "filterChain");
        this.httpFilterConfigs = checkNotNull(httpFilterConfigs, "httpFilterConfigs");
      }

      @Override
      public void onChanged(final RdsUpdate update) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            ServerRoutingConfig routingConfig =
                    ServerRoutingConfig.create(httpFilterConfigs, update.virtualHosts);
            handleConfigDiscovered(filterChain, routingConfig);
          }
        });
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            StatusException statusException = Status.UNAVAILABLE.withDescription(
                    "Rds " + resourceName + " unavailable").asException();
            handleConfigNotFound(statusException);
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (!routeDiscoveryStates.containsKey(resourceName)) {
              return;
            }
            boolean isPermanentError = isPermanentError(error);
            logger.log(Level.FINE, "{0} error from XdsClient: {1}",
                    new Object[]{isPermanentError ? "Permanent" : "Transient", error});
            if (isPermanentError) {
              handleConfigNotFound(error.asException());
            } else if (!isServing) {
              listener.onNotServing(error.asException());
            }
          }
        });
      }
    }

    private boolean isPermanentError(Status error) {
      return EnumSet.of(
              Status.Code.INTERNAL,
              Status.Code.INVALID_ARGUMENT,
              Status.Code.FAILED_PRECONDITION,
              Status.Code.PERMISSION_DENIED,
              Status.Code.UNAUTHENTICATED)
              .contains(error.getCode());
    }
  }

  @VisibleForTesting
  final class ConfigApplyingInterceptor implements ServerInterceptor {
    private final ServerInterceptor noopInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(call, headers);
      }
    };

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      ServerRoutingConfig routingConfig = call.getAttributes().get(ATTR_SERVER_ROUTING_CONFIG);
      if (routingConfig == null) {
        call.close(
                Status.UNAVAILABLE.withDescription("Missing xDS routing config"),
                new Metadata());
        return new Listener<ReqT>() {};
      }
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(
          routingConfig.virtualHosts(), call.getAuthority());
      if (virtualHost == null) {
        call.close(
                Status.UNAVAILABLE.withDescription("Could not find xDS virtual host matching RPC"),
                new Metadata());
        return new Listener<ReqT>() {};
      }
      Route selectedRoute = null;
      Map<String, FilterConfig> selectedOverrideConfigs =
              new HashMap<>(virtualHost.filterConfigOverrides());
      MethodDescriptor<ReqT, RespT> method = call.getMethodDescriptor();
      for (Route route : virtualHost.routes()) {
        if (RoutingUtils.matchRoute(
            route.routeMatch(), "/" + method.getFullMethodName(), headers, random)) {
          selectedRoute = route;
          selectedOverrideConfigs.putAll(route.filterConfigOverrides());
          break;
        }
      }
      if (selectedRoute == null) {
        call.close(
            Status.UNAVAILABLE.withDescription("Could not find xDS route matching RPC"),
            new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
      List<ServerInterceptor> filterInterceptors = new ArrayList<>();
      for (NamedFilterConfig namedFilterConfig : routingConfig.httpFilterConfigs()) {
        FilterConfig filterConfig = namedFilterConfig.filterConfig;
        Filter filter = filterRegistry.get(filterConfig.typeUrl());
        if (filter instanceof ServerInterceptorBuilder) {
          ServerInterceptor interceptor =
              ((ServerInterceptorBuilder) filter).buildServerInterceptor(
                  filterConfig, selectedOverrideConfigs.get(namedFilterConfig.name));
          if (interceptor != null) {
            filterInterceptors.add(interceptor);
          }
        }
      }
      ServerInterceptor interceptor = combineInterceptors(filterInterceptors);
      return interceptor.interceptCall(call, headers, next);
    }

    private ServerInterceptor combineInterceptors(final List<ServerInterceptor> interceptors) {
      if (interceptors.isEmpty()) {
        return noopInterceptor;
      }
      if (interceptors.size() == 1) {
        return interceptors.get(0);
      }
      return new ServerInterceptor() {
        @Override
        public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          // intercept forward
          for (int i = interceptors.size() - 1; i >= 0; i--) {
            next = InternalServerInterceptors.interceptCallHandlerCreate(
                interceptors.get(i), next);
          }
          return next.startCall(call, headers);
        }
      };
    }
  }

  /**
   * The HttpConnectionManager level configuration.
   */
  @AutoValue
  abstract static class ServerRoutingConfig {
    // Top level http filter configs.
    abstract ImmutableList<NamedFilterConfig> httpFilterConfigs();

    abstract ImmutableList<VirtualHost> virtualHosts();

    /**
     * Server routing configuration.
     * */
    public static ServerRoutingConfig create(List<NamedFilterConfig> httpFilterConfigs,
                                             List<VirtualHost> virtualHosts) {
      checkNotNull(httpFilterConfigs, "httpFilterConfigs");
      checkNotNull(virtualHosts, "virtualHosts");
      return new AutoValue_XdsServerWrapper_ServerRoutingConfig(
              ImmutableList.copyOf(httpFilterConfigs), ImmutableList.copyOf(virtualHosts));
    }
  }
}
