package io.grpc.testing;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;

@javax.annotation.Generated("by gRPC proto compiler")
public class TestServiceGrpc {

  // Static method descriptors that strictly reflect the proto.
  public static final io.grpc.MethodDescriptor<io.grpc.testing.SimpleRequest,
      io.grpc.testing.SimpleResponse> METHOD_UNARY_CALL =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          "grpc.testing.TestService", "UnaryCall",
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.SimpleRequest.parser()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.SimpleResponse.parser()));
  public static final io.grpc.MethodDescriptor<io.grpc.testing.SimpleRequest,
      io.grpc.testing.SimpleResponse> METHOD_STREAMING_CALL =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING,
          "grpc.testing.TestService", "StreamingCall",
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.SimpleRequest.parser()),
          io.grpc.protobuf.ProtoUtils.marshaller(io.grpc.testing.SimpleResponse.parser()));

  public static TestServiceStub newStub(io.grpc.ClientCallFactory callFactory) {
    return new TestServiceStub(callFactory);
  }

  public static TestServiceBlockingStub newBlockingStub(
      io.grpc.ClientCallFactory callFactory) {
    return new TestServiceBlockingStub(callFactory);
  }

  public static TestServiceFutureStub newFutureStub(
      io.grpc.ClientCallFactory callFactory) {
    return new TestServiceFutureStub(callFactory);
  }

  public static interface TestService {

    public void unaryCall(io.grpc.testing.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver);

    public io.grpc.stub.StreamObserver<io.grpc.testing.SimpleRequest> streamingCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver);
  }

  public static interface TestServiceBlockingClient {

    public io.grpc.testing.SimpleResponse unaryCall(io.grpc.testing.SimpleRequest request);
  }

  public static interface TestServiceFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.SimpleResponse> unaryCall(
        io.grpc.testing.SimpleRequest request);
  }

  public static class TestServiceStub extends io.grpc.stub.AbstractStub<TestServiceStub>
      implements TestService {
    private TestServiceStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private TestServiceStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected TestServiceStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new TestServiceStub(callFactory, callOptions);
    }

    @java.lang.Override
    public void unaryCall(io.grpc.testing.SimpleRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver) {
      asyncUnaryCall(
          callFactory.newCall(METHOD_UNARY_CALL, callOptions), request, responseObserver);
    }

    @java.lang.Override
    public io.grpc.stub.StreamObserver<io.grpc.testing.SimpleRequest> streamingCall(
        io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver) {
      return asyncBidiStreamingCall(
          callFactory.newCall(METHOD_STREAMING_CALL, callOptions), responseObserver);
    }
  }

  public static class TestServiceBlockingStub extends io.grpc.stub.AbstractStub<TestServiceBlockingStub>
      implements TestServiceBlockingClient {
    private TestServiceBlockingStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private TestServiceBlockingStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected TestServiceBlockingStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new TestServiceBlockingStub(callFactory, callOptions);
    }

    @java.lang.Override
    public io.grpc.testing.SimpleResponse unaryCall(io.grpc.testing.SimpleRequest request) {
      return blockingUnaryCall(
          callFactory.newCall(METHOD_UNARY_CALL, callOptions), request);
    }
  }

  public static class TestServiceFutureStub extends io.grpc.stub.AbstractStub<TestServiceFutureStub>
      implements TestServiceFutureClient {
    private TestServiceFutureStub(io.grpc.ClientCallFactory callFactory) {
      super(callFactory);
    }

    private TestServiceFutureStub(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      super(callFactory, callOptions);
    }

    @java.lang.Override
    protected TestServiceFutureStub build(io.grpc.ClientCallFactory callFactory,
        io.grpc.CallOptions callOptions) {
      return new TestServiceFutureStub(callFactory, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<io.grpc.testing.SimpleResponse> unaryCall(
        io.grpc.testing.SimpleRequest request) {
      return futureUnaryCall(
          callFactory.newCall(METHOD_UNARY_CALL, callOptions), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final TestService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder("grpc.testing.TestService")
      .addMethod(io.grpc.ServerMethodDefinition.create(
          METHOD_UNARY_CALL,
          asyncUnaryCall(
            new io.grpc.stub.ServerCalls.UnaryMethod<
                io.grpc.testing.SimpleRequest,
                io.grpc.testing.SimpleResponse>() {
              @java.lang.Override
              public void invoke(
                  io.grpc.testing.SimpleRequest request,
                  io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver) {
                serviceImpl.unaryCall(request, responseObserver);
              }
            })))
      .addMethod(io.grpc.ServerMethodDefinition.create(
          METHOD_STREAMING_CALL,
          asyncBidiStreamingCall(
            new io.grpc.stub.ServerCalls.BidiStreamingMethod<
                io.grpc.testing.SimpleRequest,
                io.grpc.testing.SimpleResponse>() {
              @java.lang.Override
              public io.grpc.stub.StreamObserver<io.grpc.testing.SimpleRequest> invoke(
                  io.grpc.stub.StreamObserver<io.grpc.testing.SimpleResponse> responseObserver) {
                return serviceImpl.streamingCall(responseObserver);
              }
            }))).build();
  }
}
