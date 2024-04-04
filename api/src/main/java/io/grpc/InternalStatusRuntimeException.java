package io.grpc;

import javax.annotation.Nullable;

class InternalStatusRuntimeException extends StatusRuntimeException {
  private static final long serialVersionUID = 5225396310618305864L;

  public InternalStatusRuntimeException(Status status, @Nullable Metadata trailers) {
    super(status, trailers);
  }

  @Override
  public Throwable fillInStackTrace() {
    return this;
  }
}
