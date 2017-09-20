/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import io.grpc.AbstractForwardingTest;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ForwardingReadableBuffer}.
 */
@RunWith(JUnit4.class)
public class ForwardingReadableBufferTest extends AbstractForwardingTest<ReadableBuffer> {

  @Mock private ReadableBuffer delegate;
  private ForwardingReadableBuffer buffer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    buffer = new ForwardingReadableBuffer(delegate) {};
  }

  @Override
  public ReadableBuffer mockDelegate() {
    return delegate;
  }

  @Override
  public ReadableBuffer forwarder() {
    return buffer;
  }

  @Override
  public Class<ReadableBuffer> delegateClass() {
    return ReadableBuffer.class;
  }
}
