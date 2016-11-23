/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Supplier;

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.IntegerMarshaller;
import io.grpc.LoadBalancer2.PickResult;
import io.grpc.LoadBalancer2.Subchannel;
import io.grpc.LoadBalancer2.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StringMarshaller;

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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link DelayedClientTransport}.
 */
@RunWith(JUnit4.class)
public class DelayedClientTransportTest {
  @Mock private ManagedClientTransport.Listener transportListener;
  @Mock private ClientTransport mockRealTransport;
  @Mock private ClientTransport mockRealTransport2;
  @Mock private ClientStream mockRealStream;
  @Mock private ClientStream mockRealStream2;
  @Mock private ClientStreamListener streamListener;
  @Mock private ClientTransport.PingCallback pingCallback;
  @Mock private Executor mockExecutor;
  @Captor private ArgumentCaptor<Status> statusCaptor;
  @Captor private ArgumentCaptor<ClientStreamListener> listenerCaptor;

  private static final Attributes.Key<Integer> SHARD_ID = Attributes.Key.of("shard-id");

  private final MethodDescriptor<String, Integer> method = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNKNOWN, "/service/method",
      new StringMarshaller(), new IntegerMarshaller());

  private final MethodDescriptor<String, Integer> method2 = MethodDescriptor.create(
      MethodDescriptor.MethodType.UNKNOWN, "/service/method2",
      new StringMarshaller(), new IntegerMarshaller());

  private final Metadata headers = new Metadata();
  private final Metadata headers2 = new Metadata();

  private final CallOptions callOptions = CallOptions.DEFAULT.withAuthority("dummy_value");
  private final CallOptions callOptions2 = CallOptions.DEFAULT.withAuthority("dummy_value2");
  private final StatsTraceContext statsTraceCtx = StatsTraceContext.newClientContext(
      method.getFullMethodName(), NoopCensusContextFactory.INSTANCE,
      GrpcUtil.STOPWATCH_SUPPLIER);
  private final StatsTraceContext statsTraceCtx2 = StatsTraceContext.newClientContext(
      method2.getFullMethodName(), NoopCensusContextFactory.INSTANCE,
      GrpcUtil.STOPWATCH_SUPPLIER);

  private final FakeClock fakeExecutor = new FakeClock();

  final HashMap<Subchannel, ClientTransport> transportMap =
      new HashMap<Subchannel, ClientTransport>();

  private final DelayedClientTransport delayedTransport = new DelayedClientTransport(
      fakeExecutor.getScheduledExecutorService()) {
      @Override
      ClientTransport obtainActiveTransport(Subchannel subchannel) {
        return transportMap.get(subchannel);
      }
    };

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(mockRealTransport.newStream(same(method), same(headers), same(callOptions),
            same(statsTraceCtx)))
        .thenReturn(mockRealStream);
    when(mockRealTransport2.newStream(same(method2), same(headers2), same(callOptions2),
            same(statsTraceCtx2)))
        .thenReturn(mockRealStream2);
    delayedTransport.start(transportListener);
  }

  @After public void noMorePendingTasks() {
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test public void transportsAreUsedInOrder() {
    delayedTransport.newStream(method, headers, callOptions, statsTraceCtx);
    delayedTransport.newStream(method2, headers2, callOptions2, statsTraceCtx2);
    assertEquals(0, fakeExecutor.numPendingTasks());
    delayedTransport.setTransportSupplier(new Supplier<ClientTransport>() {
        final Iterator<ClientTransport> it =
            Arrays.asList(mockRealTransport, mockRealTransport2).iterator();

        @Override public ClientTransport get() {
          return it.next();
        }
      });
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockRealTransport).newStream(same(method), same(headers), same(callOptions),
        same(statsTraceCtx));
    verify(mockRealTransport2).newStream(same(method2), same(headers2), same(callOptions2),
        same(statsTraceCtx2));
  }

  @Test public void streamStartThenSetTransport() {
    assertFalse(delayedTransport.hasPendingStreams());
    ClientStream stream = delayedTransport.newStream(method, headers, callOptions, statsTraceCtx);
    stream.start(streamListener);
    assertEquals(1, delayedTransport.getPendingStreamsCount());
    assertTrue(delayedTransport.hasPendingStreams());
    assertTrue(stream instanceof DelayedStream);
    assertEquals(0, fakeExecutor.numPendingTasks());
    delayedTransport.setTransport(mockRealTransport);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    assertFalse(delayedTransport.hasPendingStreams());
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockRealTransport).newStream(same(method), same(headers), same(callOptions),
        same(statsTraceCtx));
    verify(mockRealStream).start(listenerCaptor.capture());
    verifyNoMoreInteractions(streamListener);
    listenerCaptor.getValue().onReady();
    verify(streamListener).onReady();
    verifyNoMoreInteractions(streamListener);
  }

  @Test public void newStreamThenSetTransportThenShutdown() {
    ClientStream stream = delayedTransport.newStream(method, headers, callOptions, statsTraceCtx);
    assertEquals(1, delayedTransport.getPendingStreamsCount());
    assertTrue(stream instanceof DelayedStream);
    delayedTransport.setTransport(mockRealTransport);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockRealTransport).newStream(same(method), same(headers), same(callOptions),
        same(statsTraceCtx));
    stream.start(streamListener);
    verify(mockRealStream).start(same(streamListener));
  }

  @Test public void transportTerminatedThenSetTransport() {
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    delayedTransport.setTransport(mockRealTransport);
    verifyNoMoreInteractions(transportListener);
  }

  @Test public void setTransportThenShutdownThenNewStream() {
    delayedTransport.setTransport(mockRealTransport);
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    ClientStream stream = delayedTransport.newStream(method, headers, callOptions, statsTraceCtx);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    stream.start(streamListener);
    assertFalse(stream instanceof DelayedStream);
    verify(mockRealTransport).newStream(same(method), same(headers), same(callOptions),
        same(statsTraceCtx));
    verify(mockRealStream).start(same(streamListener));
  }

  @Test public void setTransportThenShutdownNowThenNewStream() {
    delayedTransport.setTransport(mockRealTransport);
    delayedTransport.shutdownNow(Status.UNAVAILABLE);
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    ClientStream stream = delayedTransport.newStream(method, headers, callOptions, statsTraceCtx);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    stream.start(streamListener);
    assertFalse(stream instanceof DelayedStream);
    verify(mockRealTransport).newStream(same(method), same(headers), same(callOptions),
        same(statsTraceCtx));
    verify(mockRealStream).start(same(streamListener));
  }

  @Test public void cancelStreamWithoutSetTransport() {
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    assertEquals(1, delayedTransport.getPendingStreamsCount());
    stream.cancel(Status.CANCELLED);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    verifyNoMoreInteractions(mockRealTransport);
    verifyNoMoreInteractions(mockRealStream);
  }

  @Test public void startThenCancelStreamWithoutSetTransport() {
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    stream.start(streamListener);
    assertEquals(1, delayedTransport.getPendingStreamsCount());
    stream.cancel(Status.CANCELLED);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    verify(streamListener).closed(same(Status.CANCELLED), any(Metadata.class));
    verifyNoMoreInteractions(mockRealTransport);
    verifyNoMoreInteractions(mockRealStream);
  }

  @Test public void newStreamThenShutdownTransportThenCancelStream() {
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener, times(0)).transportTerminated();
    assertEquals(1, delayedTransport.getPendingStreamsCount());
    stream.cancel(Status.CANCELLED);
    verify(transportListener).transportTerminated();
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    verifyNoMoreInteractions(mockRealTransport);
    verifyNoMoreInteractions(mockRealStream);
  }

  @Test public void setTransportThenShutdownThenPing() {
    delayedTransport.setTransport(mockRealTransport);
    delayedTransport.shutdown();
    delayedTransport.ping(pingCallback, mockExecutor);
    verify(mockRealTransport).ping(same(pingCallback), same(mockExecutor));
  }

  @Test public void pingThenSetTransport() {
    delayedTransport.ping(pingCallback, mockExecutor);
    delayedTransport.setTransport(mockRealTransport);
    verify(mockRealTransport).ping(same(pingCallback), same(mockExecutor));
  }

  @Test public void shutdownThenPing() {
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    delayedTransport.ping(pingCallback, mockExecutor);
    verifyNoMoreInteractions(pingCallback);
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockExecutor).execute(runnableCaptor.capture());
    runnableCaptor.getValue().run();
    verify(pingCallback).onFailure(any(Throwable.class));
  }

  @Test public void shutdownThenNewStream() {
    delayedTransport.shutdown();
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    stream.start(streamListener);
    verify(streamListener).closed(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
  }

  @Test public void startStreamThenShutdownNow() {
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    stream.start(streamListener);
    delayedTransport.shutdownNow(Status.UNAVAILABLE);
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    verify(streamListener).closed(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
  }

  @Test public void shutdownNowThenNewStream() {
    delayedTransport.shutdownNow(Status.UNAVAILABLE);
    verify(transportListener).transportShutdown(any(Status.class));
    verify(transportListener).transportTerminated();
    ClientStream stream = delayedTransport.newStream(method, new Metadata());
    stream.start(streamListener);
    verify(streamListener).closed(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
  }

  @Test public void startBackOff_ClearsFailFastPendingStreams() {
    final Status cause = Status.UNAVAILABLE.withDescription("some error when connecting");
    final CallOptions failFastCallOptions = CallOptions.DEFAULT;
    final CallOptions waitForReadyCallOptions = CallOptions.DEFAULT.withWaitForReady();
    final ClientStream ffStream = delayedTransport.newStream(method, headers, failFastCallOptions,
        statsTraceCtx);
    ffStream.start(streamListener);
    delayedTransport.newStream(method, headers, waitForReadyCallOptions, statsTraceCtx);
    delayedTransport.newStream(method, headers, failFastCallOptions, statsTraceCtx);
    assertEquals(3, delayedTransport.getPendingStreamsCount());

    delayedTransport.startBackoff(cause);
    assertTrue(delayedTransport.isInBackoffPeriod());
    assertEquals(1, delayedTransport.getPendingStreamsCount());

    // Fail fast stream not failed yet.
    verify(streamListener, never()).closed(any(Status.class), any(Metadata.class));

    fakeExecutor.runDueTasks();
    // Now fail fast stream failed.
    verify(streamListener).closed(same(cause), any(Metadata.class));
  }

  @Test public void startBackOff_FailsFutureFailFastStreams() {
    final Status cause = Status.UNAVAILABLE.withDescription("some error when connecting");
    final CallOptions failFastCallOptions = CallOptions.DEFAULT;
    final CallOptions waitForReadyCallOptions = CallOptions.DEFAULT.withWaitForReady();
    delayedTransport.startBackoff(cause);
    assertTrue(delayedTransport.isInBackoffPeriod());

    final ClientStream ffStream = delayedTransport.newStream(method, headers, failFastCallOptions,
        statsTraceCtx);
    ffStream.start(streamListener);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    verify(streamListener).closed(statusCaptor.capture(), any(Metadata.class));
    assertEquals(cause, Status.fromThrowable(statusCaptor.getValue().getCause()));

    delayedTransport.newStream(method, headers, waitForReadyCallOptions, statsTraceCtx);
    assertEquals(1, delayedTransport.getPendingStreamsCount());
  }

  @Test public void startBackoff_DoNothingIfAlreadyShutDown() {
    delayedTransport.shutdown();

    final Status cause = Status.UNAVAILABLE.withDescription("some error when connecting");
    delayedTransport.startBackoff(cause);

    assertFalse(delayedTransport.isInBackoffPeriod());
  }

  @Test public void reprocess() {
    Attributes affinity1 = Attributes.newBuilder().set(SHARD_ID, 1).build();
    Attributes affinity2 = Attributes.newBuilder().set(SHARD_ID, 2).build();
    CallOptions failFastCallOptions = CallOptions.DEFAULT.withAffinity(affinity1);
    CallOptions waitForReadyCallOptions =
        CallOptions.DEFAULT.withWaitForReady().withAffinity(affinity2);

    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    Subchannel subchannel3 = mock(Subchannel.class);
    when(mockRealTransport.newStream(any(MethodDescriptor.class), any(Metadata.class),
            any(CallOptions.class), same(statsTraceCtx))).thenReturn(mockRealStream);
    when(mockRealTransport2.newStream(any(MethodDescriptor.class), any(Metadata.class),
            any(CallOptions.class), same(statsTraceCtx))).thenReturn(mockRealStream2);
    transportMap.put(subchannel1, mockRealTransport);
    transportMap.put(subchannel2, mockRealTransport2);
    // subchannel3 maps to null

    // Fail-fast streams
    DelayedStream stream1 = (DelayedStream) delayedTransport.newStream(
        method, headers, failFastCallOptions, statsTraceCtx);
    DelayedStream stream2 = (DelayedStream) delayedTransport.newStream(
        method2, headers2, failFastCallOptions, statsTraceCtx);
    DelayedStream stream3 = (DelayedStream) delayedTransport.newStream(
        method, headers, failFastCallOptions, statsTraceCtx);

    // Wait-for-ready streams
    DelayedStream stream4 = (DelayedStream) delayedTransport.newStream(
        method, headers, waitForReadyCallOptions, statsTraceCtx);
    DelayedStream stream5 = (DelayedStream) delayedTransport.newStream(
        method2, headers2, waitForReadyCallOptions, statsTraceCtx);
    DelayedStream stream6 = (DelayedStream) delayedTransport.newStream(
        method, headers, waitForReadyCallOptions, statsTraceCtx);
    assertEquals(6, delayedTransport.getPendingStreamsCount());

    // First reprocess(). Some will proceed, some will fail and the rest will stay buffered.
    SubchannelPicker picker = mock(SubchannelPicker.class);
    when(picker.pickSubchannel(any(Attributes.class), any(Metadata.class))).thenReturn(
        // For the fail-fast streams
        PickResult.withSubchannel(subchannel1),    // stream1: proceed
        PickResult.withError(Status.UNAVAILABLE),  // stream2: fail
        PickResult.withSubchannel(subchannel3),    // stream3: stay
        // For the wait-for-ready streams
        PickResult.withSubchannel(subchannel2),           // stream4: proceed
        PickResult.withError(Status.RESOURCE_EXHAUSTED),  // stream5: stay
        PickResult.withSubchannel(subchannel3));          // stream6: stay
    InOrder inOrder = inOrder(picker);
    delayedTransport.reprocess(picker);

    assertEquals(3, delayedTransport.getPendingStreamsCount());
    inOrder.verify(picker).pickSubchannel(affinity1, headers);  // stream1
    inOrder.verify(picker).pickSubchannel(affinity1, headers2);  // stream2
    inOrder.verify(picker).pickSubchannel(affinity1, headers);  // stream3
    inOrder.verify(picker).pickSubchannel(affinity2, headers);  // stream4
    inOrder.verify(picker).pickSubchannel(affinity2, headers2);  // stream5
    inOrder.verify(picker).pickSubchannel(affinity2, headers);  // stream6
    inOrder.verifyNoMoreInteractions();
    // Make sure that real transport creates streams in the executor
    verify(mockRealTransport, never()).newStream(any(MethodDescriptor.class),
        any(Metadata.class), any(CallOptions.class), any(StatsTraceContext.class));
    verify(mockRealTransport2, never()).newStream(any(MethodDescriptor.class),
        any(Metadata.class), any(CallOptions.class), any(StatsTraceContext.class));
    fakeExecutor.runDueTasks();
    assertEquals(0, fakeExecutor.numPendingTasks());
    // stream1 and stream4 went through
    verify(mockRealTransport).newStream(method, headers, failFastCallOptions, statsTraceCtx);
    verify(mockRealTransport2).newStream(method, headers, waitForReadyCallOptions, statsTraceCtx);
    assertSame(mockRealStream, stream1.getRealStream());
    assertSame(mockRealStream2, stream4.getRealStream());
    // The fail-fast stream2 has failed due to picker returning an error
    assertSame(Status.UNAVAILABLE, ((FailingClientStream) stream2.getRealStream()).getError());
    // Other streams are still buffered
    assertNull(stream3.getRealStream());
    assertNull(stream5.getRealStream());
    assertNull(stream6.getRealStream());

    // Second reprocess(). All will proceed.
    picker = mock(SubchannelPicker.class);
    when(picker.pickSubchannel(any(Attributes.class), any(Metadata.class))).thenReturn(
        PickResult.withSubchannel(subchannel1),  // stream3
        PickResult.withSubchannel(subchannel2),  // stream5
        PickResult.withSubchannel(subchannel1));  // stream6
    inOrder = inOrder(picker);

    delayedTransport.reprocess(picker);
    assertEquals(0, delayedTransport.getPendingStreamsCount());
    inOrder.verify(picker).pickSubchannel(affinity1, headers);  // stream3
    inOrder.verify(picker).pickSubchannel(affinity2, headers2);  // stream5
    inOrder.verify(picker).pickSubchannel(affinity2, headers);  // stream6
    inOrder.verifyNoMoreInteractions();
    fakeExecutor.runDueTasks();
    assertEquals(0, fakeExecutor.numPendingTasks());
    assertSame(mockRealStream, stream3.getRealStream());
    assertSame(mockRealStream2, stream5.getRealStream());
    assertSame(mockRealStream, stream6.getRealStream());
  }
}
