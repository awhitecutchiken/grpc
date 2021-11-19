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

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;

import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for end-to-end xds tests.
 * A local control plane is implemented in {@link XdsTestControlPlaneService}.
 * Test cases can inject xds configs to the control plane for testing.
 */
abstract class AbstractXdsE2eTest {
  private static final Logger logger = Logger.getLogger(AbstractXdsE2eTest.class.getName());

  protected int testServerPort;
  protected String serverHostName;
  protected int controlPlaneServicePort;
  private Server server;
  private Server controlPlane;
  private XdsTestControlPlaneService controlPlaneService;
  protected SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub;
  private XdsNameResolverProvider nameResolverProvider;
  private static final String scheme = "test-xds";
  private static final String SERVER_LISTENER_TEMPLATE =
      "grpc/server?udpa.resource.listening_address=%s";
  private static final String rdsName = "route-config.googleapis.com";
  private static final String clusterName = "cluster0";
  private static final String edsName = "eds-service-0";
  private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";

  /**
   * Provides default client bootstrap.
   * A subclass test case should override this method if it tests client bootstrap.
   */
  protected Map<String, ?> getClientBootstrapOverride() {
    Map<String, ?> defaultClientBootstrapOverride = ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
        "xds_servers", Collections.singletonList(
            ImmutableMap.of(
                "server_uri", "localhost:" + controlPlaneServicePort,
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        )
    );
    return defaultClientBootstrapOverride;
  }

  /**
   * Provides default server bootstrap.
   * A subclass test case should override this method if it tests server bootstrap.
   */
  protected Map<String, ?> getServerBootstrapOverride() {
    Map<String, ?> defaultServerBootstrapOverride = ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString()),
        "xds_servers", Collections.singletonList(
            ImmutableMap.of(
                "server_uri", "localhost:" + controlPlaneServicePort,
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "server_listener_resource_name_template", SERVER_LISTENER_TEMPLATE
    );
    return defaultServerBootstrapOverride;
  }


  /**
   * 1. Start control plane server and get control plane port
   * 2. Find an available port for xds server
   * 3. Set control plane config using the port in 2
   * 4. Start xds server using control plane port in 1 and available port in 2
   * */
  protected void setUp() throws Exception {
    startControlPlane();
    ServerSocket serverSocket = new ServerSocket(0);
    testServerPort = serverSocket.getLocalPort();
    serverSocket.close();
    XdsTestControlPlaneService.XdsTestControlPlaneConfig controlPlaneConfig =
        getControlPlaneConfig();
    controlPlaneService.setConfig(controlPlaneConfig);
    startServer();
    nameResolverProvider = XdsNameResolverProvider.createForTest(scheme,
        getClientBootstrapOverride());
    NameResolverRegistry.getDefaultRegistry().register(nameResolverProvider);
    serverHostName = "0.0.0.0:" + testServerPort;
    ManagedChannel channel = Grpc.newChannelBuilder(scheme + ":///" + serverHostName,
        InsecureChannelCredentials.create()).build();
    logger.log(Level.FINER, "Starting control plane with config: {0}", controlPlaneConfig);
    blockingStub = SimpleServiceGrpc.newBlockingStub(channel);
  }

  protected void tearDown() throws Exception {
    if (server != null) {
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    if (controlPlane != null) {
      controlPlane.shutdownNow();
      if (!controlPlane.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    NameResolverRegistry.getDefaultRegistry().deregister(nameResolverProvider);
  }

  protected void startServer() throws Exception {
    SimpleServiceGrpc.SimpleServiceImplBase simpleServiceImpl =
        new SimpleServiceGrpc.SimpleServiceImplBase() {
          @Override
          public void unaryRpc(
              SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            SimpleResponse response =
                SimpleResponse.newBuilder().setResponseMessage("Hi, xDS!").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
          }
        };


    XdsServerBuilder serverBuilder = XdsServerBuilder.forPort(
        testServerPort, InsecureServerCredentials.create())
        .addService(simpleServiceImpl)
        .overrideBootstrapForTest(getServerBootstrapOverride());
    server = serverBuilder.build().start();
  }

  /**
   * Provides default control plane xds configs.
   * A subclass test case should override this method to inject control plane xds configs to verify
   * end-to-end behavior.
   */
  protected XdsTestControlPlaneService.XdsTestControlPlaneConfig getControlPlaneConfig() {
    String serverHostName = "0.0.0.0:" + testServerPort;
    String tcpListenerName = SERVER_LISTENER_TEMPLATE.replaceAll("%s", serverHostName);
    return new XdsTestControlPlaneService.XdsTestControlPlaneConfig(
        Collections.singletonList(serverListener(tcpListenerName, serverHostName)),
        Collections.singletonList(clientListener(serverHostName)),
        Collections.singletonList(rds(serverHostName)),
        Collections.singletonList(cds()),
        Collections.singletonList(eds(testServerPort))
    );
  }

  private void startControlPlane() throws Exception {
    controlPlaneService = new XdsTestControlPlaneService();
    NettyServerBuilder controlPlaneServerBuilder =
        NettyServerBuilder.forPort(0)
        .addService(controlPlaneService);
    controlPlane = controlPlaneServerBuilder.build().start();
    controlPlaneServicePort = controlPlane.getPort();
  }

  /**
   * A subclass test case should override this method to verify end-to-end behaviour.
   */
  abstract void run();

  private static Listener clientListener(String name) {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener apiListener = ApiListener.newBuilder().setApiListener(Any.pack(
        io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
            .HttpConnectionManager.newBuilder()
            .setRds(
                Rds.newBuilder()
                    .setRouteConfigName(rdsName)
                    .setConfigSource(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())))
            .addAllHttpFilters(Collections.singletonList(httpFilter))
            .build(),
        HTTP_CONNECTION_MANAGER_TYPE_URL)
    ).build();
    Listener listener = Listener.newBuilder()
        .setName(name)
        .setApiListener(apiListener).build();
    return listener;
  }

  private static Listener serverListener(String name, String authority) {
    HttpFilter routerFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(
            Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    VirtualHost virtualHost = io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
        .setName("virtual-host-0")
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build()
                )
                .setNonForwardingAction(NonForwardingAction.newBuilder().build())
                .build()
        ).build();
    RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
        .addVirtualHosts(virtualHost)
        .build();
    Filter filter = Filter.newBuilder()
        .setName("network-filter-0")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(routeConfig)
                    .addAllHttpFilters(Collections.singletonList(routerFilter))
                    .build()
            )
        ).build();
    FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
        .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
        .build();
    FilterChain filterChain = FilterChain.newBuilder()
        .setName("filter-chain-0")
        .setFilterChainMatch(filterChainMatch)
        .addFilters(filter)
        .build();
    return Listener.newBuilder()
        .setName(name)
        .setTrafficDirection(TrafficDirection.INBOUND)
        .addFilterChains(filterChain)
        .build();
  }

  private static RouteConfiguration rds(String authority) {
    VirtualHost virtualHost = VirtualHost.newBuilder()
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build()
                )
                .setRoute(
                    RouteAction.newBuilder().setCluster(clusterName).build()
                )
                .build())
        .build();
    return RouteConfiguration.newBuilder().setName(rdsName).addVirtualHosts(virtualHost).build();
  }

  private static Cluster cds() {
    return Cluster.newBuilder()
        .setName(clusterName)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder()
                .setServiceName(edsName)
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.newBuilder().build())
                        .build())
                .build()
        )
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .build();
  }

  private static ClusterLoadAssignment eds(int port) {
    Address address = Address.newBuilder()
        .setSocketAddress(
            SocketAddress.newBuilder().setAddress("0.0.0.0").setPortValue(port).build()
        )
        .build();
    LocalityLbEndpoints endpoints = LocalityLbEndpoints.newBuilder()
        .setLoadBalancingWeight(UInt32Value.of(10))
        .setPriority(0)
        .addLbEndpoints(
            LbEndpoint.newBuilder()
                .setEndpoint(
                    Endpoint.newBuilder().setAddress(address).build())
                .setHealthStatus(HealthStatus.HEALTHY)
                .build()
        )
        .build();
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(edsName)
        .addEndpoints(endpoints)
        .build();
  }
}
