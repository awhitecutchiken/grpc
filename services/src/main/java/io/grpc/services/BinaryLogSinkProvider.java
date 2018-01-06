/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.services;

import com.google.protobuf.MessageLite;
import java.io.Closeable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Subclasses must be thread safe, and are responsible for writing the binary log message to
 * the appropriate destination.
 */
@ThreadSafe
public abstract class BinaryLogSinkProvider implements Closeable {

  /**
   * Returns the {@code BinaryLogSinkProvider} that should be used.
   */
  public static BinaryLogSinkProvider provider() {
    // TODO(zpencer): either implement the service provider here, or use a generic helper
    // See: https://github.com/grpc/grpc-java/pull/3886
    return null;
  }

  /**
   * Writes the {@code message} to the destination.
   */
  public abstract void write(MessageLite message);

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int priority();
}
