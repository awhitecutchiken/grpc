// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: reflection.proto

package io.grpc.reflection.v1alpha;

public interface ErrorResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:grpc.reflection.v1alpha.ErrorResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * This field uses the error codes defined in grpc::StatusCode.
   * </pre>
   *
   * <code>optional int32 error_code = 1;</code>
   */
  int getErrorCode();

  /**
   * <code>optional string error_message = 2;</code>
   */
  java.lang.String getErrorMessage();
  /**
   * <code>optional string error_message = 2;</code>
   */
  com.google.protobuf.ByteString
      getErrorMessageBytes();
}
