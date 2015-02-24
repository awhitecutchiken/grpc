/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc;

import com.google.common.base.Preconditions;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.Immutable;

/**
 * Description of a remote method used by {@link Channel} to initiate a call.
 *
 * <p>Provides the name of the operation to execute as well as {@link io.grpc.Marshaller} instances
 * used to parse and serialize request and response messages.
 *
 * <p>Can be constructed manually but will often be generated by stub code generators.
 */
@Immutable
public class MethodDescriptor<RequestT, ResponseT> {

  private final MethodType type;
  private final String name;
  private final Marshaller<RequestT> requestMarshaller;
  private final Marshaller<ResponseT> responseMarshaller;
  private final long timeoutMicros;

  public static <RequestT, ResponseT> MethodDescriptor<RequestT, ResponseT> create(
      MethodType type, String name, long timeout, TimeUnit timeoutUnit,
      Marshaller<RequestT> requestMarshaller,
      Marshaller<ResponseT> responseMarshaller) {
    return new MethodDescriptor<RequestT, ResponseT>(
        type, name, timeoutUnit.toMicros(timeout), requestMarshaller, responseMarshaller);
  }

  private MethodDescriptor(MethodType type, String name, long timeoutMicros,
                           Marshaller<RequestT> requestMarshaller,
                           Marshaller<ResponseT> responseMarshaller) {
    this.type = Preconditions.checkNotNull(type);
    this.name = name;
    Preconditions.checkArgument(timeoutMicros > 0);
    this.timeoutMicros = timeoutMicros;
    this.requestMarshaller = requestMarshaller;
    this.responseMarshaller = responseMarshaller;
  }

  /**
   * The call type of the method.
   */
  public MethodType getType() {
    return type;
  }

  /**
   * The fully qualified name of the method.
   */
  public String getName() {
    return name;
  }

  /**
   * Timeout for the operation in microseconds.
   */
  public long getTimeout() {
    return timeoutMicros;
  }

  /**
   * Parse a response payload from the given {@link InputStream}.
   *
   * @param input stream containing response message to parse.
   * @return parsed response message object.
   */
  public ResponseT parseResponse(InputStream input) {
    return responseMarshaller.parse(input);
  }

  /**
   * Convert a request message to an {@link InputStream}.
   *
   * @param requestMessage to serialize using the request {@link io.grpc.Marshaller}.
   * @return serialized request message.
   */
  public InputStream streamRequest(RequestT requestMessage) {
    return requestMarshaller.stream(requestMessage);
  }

  /**
   * Create a new descriptor with a different timeout.
   *
   * @param timeout to set on cloned descriptor.
   * @param unit of time for {@code timeout}
   * @return clone of this with the specified timeout.
   */
  public MethodDescriptor<RequestT, ResponseT> withTimeout(long timeout, TimeUnit unit) {
    return new MethodDescriptor<RequestT, ResponseT>(type, name, unit.toMicros(timeout),
        requestMarshaller, responseMarshaller);
  }
}
