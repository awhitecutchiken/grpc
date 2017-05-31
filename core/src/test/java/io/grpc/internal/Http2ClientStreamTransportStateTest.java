/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.Charsets.US_ASCII;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link Http2ClientStreamTransportState}. */
@RunWith(JUnit4.class)
public class Http2ClientStreamTransportStateTest {
  @Mock private ClientStreamListener mockListener;
  @Captor private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void transportHeadersReceived_notifiesListener() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);

    verify(mockListener, never()).closed(any(Status.class), any(Metadata.class));
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_doesntRequire200() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "500");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);

    verify(mockListener, never()).closed(any(Status.class), any(Metadata.class));
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_noHttpStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);
    state.transportDataReceived(ReadableBuffers.empty(), true);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(headers));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void transportHeadersReceived_wrongContentType_200() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "text/html");
    state.transportHeadersReceived(headers);
    state.transportDataReceived(ReadableBuffers.empty(), true);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(headers));
    assertEquals(Code.UNKNOWN, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("200"));
  }

  @Test
  public void transportHeadersReceived_wrongContentType_401() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "401");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "text/html");
    state.transportHeadersReceived(headers);
    state.transportDataReceived(ReadableBuffers.empty(), true);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(headers));
    assertEquals(Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("401"));
    assertTrue(statusCaptor.getValue().getDescription().contains("text/html"));
  }

  @Test
  public void transportHeadersReceived_handles_1xx() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);

    Metadata infoHeaders = new Metadata();
    infoHeaders.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "100");
    state.transportHeadersReceived(infoHeaders);
    Metadata infoHeaders2 = new Metadata();
    infoHeaders2.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "101");
    state.transportHeadersReceived(infoHeaders2);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);

    verify(mockListener, never()).closed(any(Status.class), any(Metadata.class));
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_twice() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);
    Metadata headersAgain = new Metadata();
    state.transportHeadersReceived(headersAgain);
    state.transportDataReceived(ReadableBuffers.empty(), true);

    verify(mockListener).headersRead(headers);
    verify(mockListener).closed(statusCaptor.capture(), same(headersAgain));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("twice"));
  }

  @Test
  public void transportHeadersReceived_unknownAndTwiceLogsSecondHeaders() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "text/html");
    state.transportHeadersReceived(headers);
    Metadata headersAgain = new Metadata();
    String testString = "This is a test";
    headersAgain.put(Metadata.Key.of("key", Metadata.ASCII_STRING_MARSHALLER), testString);
    state.transportHeadersReceived(headersAgain);
    state.transportDataReceived(ReadableBuffers.empty(), true);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(headers));
    assertEquals(Code.UNKNOWN, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains(testString));
  }

  @Test
  public void transportDataReceived_noHeaderReceived() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    String testString = "This is a test";
    state.transportDataReceived(ReadableBuffers.wrap(testString.getBytes(US_ASCII)), true);

    verify(mockListener).closed(statusCaptor.capture(), any(Metadata.class));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void transportDataReceived_debugData() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER), "text/html");
    state.transportHeadersReceived(headers);
    String testString = "This is a test";
    state.transportDataReceived(ReadableBuffers.wrap(testString.getBytes(US_ASCII)), true);

    verify(mockListener).closed(statusCaptor.capture(), same(headers));
    assertTrue(statusCaptor.getValue().getDescription().contains(testString));
  }

  @Test
  public void transportTrailersReceived_notifiesListener() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    trailers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    trailers.put(Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER), "0");
    state.transportTrailersReceived(trailers);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(Status.OK, trailers);
  }

  @Test
  public void transportTrailersReceived_afterHeaders() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER), "0");
    state.transportTrailersReceived(trailers);

    verify(mockListener).headersRead(headers);
    verify(mockListener).closed(Status.OK, trailers);
  }

  @Test
  public void transportTrailersReceived_observesStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    trailers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    trailers.put(Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER), "1");
    state.transportTrailersReceived(trailers);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(Status.CANCELLED, trailers);
  }

  @Test
  public void transportTrailersReceived_missingStatusUsesHttpStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "401");
    trailers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportTrailersReceived(trailers);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(trailers));
    assertEquals(Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("401"));
  }

  @Test
  public void transportTrailersReceived_missingHttpStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    trailers.put(Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER), "0");
    state.transportTrailersReceived(trailers);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(trailers));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void transportTrailersReceived_missingStatusAndMissingHttpStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportTrailersReceived(trailers);

    verify(mockListener, never()).headersRead(any(Metadata.class));
    verify(mockListener).closed(statusCaptor.capture(), same(trailers));
    assertEquals(Code.INTERNAL, statusCaptor.getValue().getCode());
  }

  @Test
  public void transportTrailersReceived_missingStatusAfterHeadersIgnoresHttpStatus() {
    BaseTransportState state = new BaseTransportState();
    state.setListener(mockListener);
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);
    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of(":status", Metadata.ASCII_STRING_MARSHALLER), "401");
    state.transportTrailersReceived(trailers);

    verify(mockListener).headersRead(headers);
    verify(mockListener).closed(statusCaptor.capture(), same(trailers));
    assertEquals(Code.UNKNOWN, statusCaptor.getValue().getCode());
  }

  private static class BaseTransportState extends Http2ClientStreamTransportState {
    public BaseTransportState() {
      super(DEFAULT_MAX_MESSAGE_SIZE, StatsTraceContext.NOOP);
    }

    @Override
    protected void http2ProcessingFailed(Status status, Metadata trailers) {
      transportReportStatus(status, false, trailers);
    }

    @Override
    protected void deframeFailed(Throwable cause) {}

    @Override
    public void bytesRead(int processedBytes) {}
  }
}
