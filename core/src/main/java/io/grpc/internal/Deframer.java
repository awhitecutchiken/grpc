/*
 * Copyright 2017 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;

import io.grpc.Decompressor;
import io.grpc.Status;

/** Interface for deframing gRPC messages. */
public interface Deframer {

  void setMaxInboundMessageSize(int messageSize);

  /**
   * Sets the decompressor available to use. The message encoding for the stream comes later in
   * time, and thus will not be available at the time of construction. This should only be set once,
   * since the compression codec cannot change after the headers have been sent.
   *
   * @param decompressor the decompressing wrapper.
   */
  void setDecompressor(Decompressor decompressor);

  /**
   * Sets the decompressor used for full-stream decompression. Full-stream decompression disables
   * any per-message decompressor set by {@link #setDecompressor}.
   *
   * @param fullStreamDecompressor the decompressing wrapper
   */
  void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor);

  /**
   * Requests up to the given number of messages from the call. No additional messages will be
   * delivered.  Returns a NO_STATUS {@link StatusHolder} if there were no problems, or an error
   * status otherwise.
   *
   * <p>If {@link #close()} has been called, this method will have no effect.
   *
   * @param numMessages the requested number of messages to be delivered to the listener.
   */
  StatusHolder request(int numMessages);

  /**
   * Adds the given data to this deframer and attempts delivery to the listener.  Returns an Ok
   * {@link Status} if there were no problems, or an error status otherwise.
   *
   * @param data the raw data read from the remote endpoint. Must be non-null.
   */
  StatusHolder deframe(ReadableBuffer data);

  /** Close when any messages currently queued have been requested and delivered. */
  void closeWhenComplete();

  /**
   * Closes this deframer and frees any resources. After this method is called, additional calls
   * will have no effect.
   */
  void close();


  final class StatusHolder {

    /**
     * Sentinel value to indicate there was not a problem.
     */
    static final StatusHolder NO_STATUS = new StatusHolder();

    final Status status;

    StatusHolder(Status status) {
      checkArgument(!status.isOk(), "can't use non-ok status %s", status);
      this.status = status;
    }

    private StatusHolder() {
      this.status = null;
    }
  }
}
