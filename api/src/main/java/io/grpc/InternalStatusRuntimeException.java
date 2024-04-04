package io.grpc;

import javax.annotation.Nullable;

class InternalStatusRuntimeException extends StatusRuntimeException {
  public InternalStatusRuntimeException(Status status, @Nullable Metadata trailers) {
    super(status, trailers);
  }

  @Override
  public Throwable fillInStackTrace() {
    return this;
  }
}
