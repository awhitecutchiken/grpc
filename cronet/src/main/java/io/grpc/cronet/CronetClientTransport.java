/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc.cronet;

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.cronet.CronetChannelBuilder.StreamBuilderFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogId;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.WithLogId;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * A cronet-based {@link ConnectionClientTransport} implementation.
 */
class CronetClientTransport implements ConnectionClientTransport, WithLogId {
  /**
   * Note: this flag is only for experimental use. We may remove this in the future.
   * Turning on this flag will cause all grpc requests going out through cronet to use PUT instead
   * of POST.
   * If needed, this must be set before creating any grpc channels and should not be changed
   * afterwards.
   */
  public static boolean enableIdempotencyForAllCronetStreams = false;

  /**
   * Used for attaching annotation objects to cronet streams. When the stream finishes, the user can
   * get cronet metrics from {@link org.chromium.net.RequestFinishedInfo.Listener} with the same
   * annotation object.
   *
   * The Object must not be null.
   */
  public static final CallOptions.Key<Object> CRONET_ANNOTATION_KEY =
      CallOptions.Key.of("cronet-annotation", null);

  private final LogId logId = LogId.allocate(getClass().getName());
  private final InetSocketAddress address;
  private final String authority;
  private final String userAgent;
  private Listener listener;
  private final Object lock = new Object();
  @GuardedBy("lock")
  private final Set<CronetClientStream> streams =
      new HashSet<CronetClientStream>();
  private final Executor executor;
  private final int maxMessageSize;
  // Indicates the transport is in go-away state: no new streams will be processed,
  // but existing streams may continue.
  @GuardedBy("lock")
  private boolean goAway;
  // Used to indicate the special phase while we are going to enter go-away state but before
  // goAway is turned to true, see the comment at where this is set about why it is needed.
  @GuardedBy("lock")
  private boolean startedGoAway;
  @GuardedBy("lock")
  private Status goAwayStatus;
  @GuardedBy("lock")
  private boolean stopped;
  @GuardedBy("lock")
  // Whether this transport has started.
  private boolean started;
  private StreamBuilderFactory streamFactory;

  CronetClientTransport(StreamBuilderFactory streamFactory, InetSocketAddress address,
      String authority, @Nullable String userAgent, Executor executor, int maxMessageSize) {
    this.address = Preconditions.checkNotNull(address, "address");
    this.authority = authority;
    this.userAgent = GrpcUtil.getGrpcUserAgent("cronet", userAgent);
    this.maxMessageSize = maxMessageSize;
    this.executor = Preconditions.checkNotNull(executor, "executor");
    this.streamFactory = Preconditions.checkNotNull(streamFactory, "streamFactory");
  }

  @Override
  public CronetClientStream newStream(final MethodDescriptor<?, ?> method, final Metadata headers,
      final CallOptions callOptions) {
    Preconditions.checkNotNull(method, "method");
    Preconditions.checkNotNull(headers, "headers");

    final String defaultPath = "/" + method.getFullMethodName();
    final String url = "https://" + authority + defaultPath;

    final StatsTraceContext statsTraceCtx = StatsTraceContext.newClientContext(callOptions, headers);
    class StartCallback implements Runnable {
      final CronetClientStream clientStream = new CronetClientStream(
          url, userAgent, executor, headers, CronetClientTransport.this, this, lock, maxMessageSize,
          method, statsTraceCtx, callOptions);

      @Override
      public void run() {
        synchronized (lock) {
          if (goAway) {
            clientStream.transportState().transportReportStatus(goAwayStatus, true, new Metadata());
          } else if (started) {
            startStream(clientStream);
          } else {
            throw new AssertionError("Transport is not started");
          }
        }
      }
    }

    return new StartCallback().clientStream;
  }

  @GuardedBy("lock")
  private void startStream(CronetClientStream stream) {
    streams.add(stream);
    stream.transportState().start(streamFactory);
  }

  @Override
  public Runnable start(Listener listener) {
    this.listener = Preconditions.checkNotNull(listener, "listener");
    synchronized (lock) {
      started = true;
    }
    return new Runnable() {
      @Override
      public void run() {
        // Listener callbacks should not be called simultaneously
        CronetClientTransport.this.listener.transportReady();
      }
    };
  }

  @Override
  public String toString() {
    return super.toString() + "(" + address + ")";
  }

  public void shutdown() {
    shutdown(Status.UNAVAILABLE.withDescription("Transport stopped"));
  }

  @Override
  public void shutdown(Status status) {
    synchronized (lock) {
      if (goAway) {
        return;
      }
    }

    startGoAway(status);
  }

  @Override
  public void shutdownNow(Status status) {
    shutdown(status);
    ArrayList<CronetClientStream> streamsCopy;
    synchronized (lock) {
      // A copy is always necessary since cancel() can call finishStream() which calls
      // streams.remove()
      streamsCopy = new ArrayList<CronetClientStream>(streams);
    }
    for (int i = 0; i < streamsCopy.size(); i++) {
      // Avoid deadlock by calling into stream without lock held
      streamsCopy.get(i).cancel(status);
    }
    stopIfNecessary();
  }

  @Override
  public Attributes getAttributes() {
    // TODO(zhangkun83): fill channel security attributes
    return Attributes.EMPTY;
  }

  private void startGoAway(Status status) {
    synchronized (lock) {
      if (startedGoAway) {
        // Another go-away is in progress, ignore this one.
        return;
      }
      // We use startedGoAway here instead of goAway, because once the goAway becomes true, other
      // thread in stopIfNecessary() may stop the transport and cause the
      // listener.transportTerminated() be called before listener.transportShutdown().
      startedGoAway = true;
    }

    listener.transportShutdown(status);

    synchronized (lock) {
      goAway = true;
      goAwayStatus = status;
    }

    stopIfNecessary();
  }

  @Override
  public void ping(final PingCallback callback, Executor executor) {
    // TODO(ericgribkoff): depend on cronet implemenetation
    throw new UnsupportedOperationException();
  }

  @Override
  public LogId getLogId() {
    return logId;
  }

  /**
   * When the transport is in goAway state, we should stop it once all active streams finish.
   */
  void stopIfNecessary() {
    synchronized (lock) {
      if (goAway && !stopped && streams.size() == 0) {
        stopped = true;
      } else {
        return;
      }
    }
    listener.transportTerminated();
  }

  void finishStream(CronetClientStream stream, Status status) {
    synchronized (lock) {
      if (streams.remove(stream)) {
        boolean isCancelled = (status.getCode() == Code.CANCELLED
            || status.getCode() == Code.DEADLINE_EXCEEDED);
        stream.transportState().transportReportStatus(status, isCancelled, new Metadata());
      } else {
        return;
      }
    }
    stopIfNecessary();
  }
}
