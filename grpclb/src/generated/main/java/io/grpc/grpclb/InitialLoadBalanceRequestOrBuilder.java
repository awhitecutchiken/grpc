// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: load_balancer.proto

package io.grpc.grpclb;

public interface InitialLoadBalanceRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:grpc.lb.v1.InitialLoadBalanceRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of load balanced service (IE, service.blade.gslb.googleprod.com). Its
   * length should be less than 256 bytes.
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of load balanced service (IE, service.blade.gslb.googleprod.com). Its
   * length should be less than 256 bytes.
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  com.google.protobuf.ByteString
      getNameBytes();
}
