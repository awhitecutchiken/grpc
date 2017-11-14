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

package io.grpc.cronet;

import io.grpc.Internal;

/**
 * Internal {@link CronetChannelBuilder} accessor.  This is intended for usage internal to the gRPC
 * team.  If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public class InternalCronetChannelBuilder {
  public static void setStatsEnabled(CronetChannelBuilder builder, boolean value) {
    builder.setStatsEnabled(value);
  }

  public static void setTracingEnabled(CronetChannelBuilder builder, boolean value) {
    builder.setTracingEnabled(value);
  }

  private InternalCronetChannelBuilder() {}
}
