package semidx.runtime.grpc.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
 * method names remain unchanged, but adding the service under this versioned
 * proto package intentionally changes the full service name (and therefore the
 * full wire method paths) from semidx.RuntimeService to
 * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.63.0)",
    comments = "Source: semidx/runtime/grpc/v1/runtime.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RuntimeServiceGrpc {

  private RuntimeServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "semidx.runtime.grpc.v1.RuntimeService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.HealthRequest,
      semidx.runtime.grpc.v1.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = semidx.runtime.grpc.v1.HealthRequest.class,
      responseType = semidx.runtime.grpc.v1.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.HealthRequest,
      semidx.runtime.grpc.v1.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.HealthRequest, semidx.runtime.grpc.v1.HealthResponse> getHealthMethod;
    if ((getHealthMethod = RuntimeServiceGrpc.getHealthMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getHealthMethod = RuntimeServiceGrpc.getHealthMethod) == null) {
          RuntimeServiceGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.HealthRequest, semidx.runtime.grpc.v1.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.CreateIndexRequest,
      semidx.runtime.grpc.v1.CreateIndexResponse> getCreateIndexMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CreateIndex",
      requestType = semidx.runtime.grpc.v1.CreateIndexRequest.class,
      responseType = semidx.runtime.grpc.v1.CreateIndexResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.CreateIndexRequest,
      semidx.runtime.grpc.v1.CreateIndexResponse> getCreateIndexMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.CreateIndexRequest, semidx.runtime.grpc.v1.CreateIndexResponse> getCreateIndexMethod;
    if ((getCreateIndexMethod = RuntimeServiceGrpc.getCreateIndexMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getCreateIndexMethod = RuntimeServiceGrpc.getCreateIndexMethod) == null) {
          RuntimeServiceGrpc.getCreateIndexMethod = getCreateIndexMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.CreateIndexRequest, semidx.runtime.grpc.v1.CreateIndexResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CreateIndex"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.CreateIndexRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.CreateIndexResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("CreateIndex"))
              .build();
        }
      }
    }
    return getCreateIndexMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ResolveContextRequest,
      semidx.runtime.grpc.v1.ResolveContextResponse> getResolveContextMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ResolveContext",
      requestType = semidx.runtime.grpc.v1.ResolveContextRequest.class,
      responseType = semidx.runtime.grpc.v1.ResolveContextResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ResolveContextRequest,
      semidx.runtime.grpc.v1.ResolveContextResponse> getResolveContextMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ResolveContextRequest, semidx.runtime.grpc.v1.ResolveContextResponse> getResolveContextMethod;
    if ((getResolveContextMethod = RuntimeServiceGrpc.getResolveContextMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getResolveContextMethod = RuntimeServiceGrpc.getResolveContextMethod) == null) {
          RuntimeServiceGrpc.getResolveContextMethod = getResolveContextMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.ResolveContextRequest, semidx.runtime.grpc.v1.ResolveContextResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ResolveContext"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.ResolveContextRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.ResolveContextResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("ResolveContext"))
              .build();
        }
      }
    }
    return getResolveContextMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ExpandContextRequest,
      semidx.runtime.grpc.v1.ExpandContextResponse> getExpandContextMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ExpandContext",
      requestType = semidx.runtime.grpc.v1.ExpandContextRequest.class,
      responseType = semidx.runtime.grpc.v1.ExpandContextResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ExpandContextRequest,
      semidx.runtime.grpc.v1.ExpandContextResponse> getExpandContextMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.ExpandContextRequest, semidx.runtime.grpc.v1.ExpandContextResponse> getExpandContextMethod;
    if ((getExpandContextMethod = RuntimeServiceGrpc.getExpandContextMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getExpandContextMethod = RuntimeServiceGrpc.getExpandContextMethod) == null) {
          RuntimeServiceGrpc.getExpandContextMethod = getExpandContextMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.ExpandContextRequest, semidx.runtime.grpc.v1.ExpandContextResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ExpandContext"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.ExpandContextRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.ExpandContextResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("ExpandContext"))
              .build();
        }
      }
    }
    return getExpandContextMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.FetchContextDetailRequest,
      semidx.runtime.grpc.v1.FetchContextDetailResponse> getFetchContextDetailMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchContextDetail",
      requestType = semidx.runtime.grpc.v1.FetchContextDetailRequest.class,
      responseType = semidx.runtime.grpc.v1.FetchContextDetailResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.FetchContextDetailRequest,
      semidx.runtime.grpc.v1.FetchContextDetailResponse> getFetchContextDetailMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.FetchContextDetailRequest, semidx.runtime.grpc.v1.FetchContextDetailResponse> getFetchContextDetailMethod;
    if ((getFetchContextDetailMethod = RuntimeServiceGrpc.getFetchContextDetailMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getFetchContextDetailMethod = RuntimeServiceGrpc.getFetchContextDetailMethod) == null) {
          RuntimeServiceGrpc.getFetchContextDetailMethod = getFetchContextDetailMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.FetchContextDetailRequest, semidx.runtime.grpc.v1.FetchContextDetailResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchContextDetail"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.FetchContextDetailRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.FetchContextDetailResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("FetchContextDetail"))
              .build();
        }
      }
    }
    return getFetchContextDetailMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.LiteralFileSliceRequest,
      semidx.runtime.grpc.v1.LiteralFileSliceResponse> getLiteralFileSliceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "LiteralFileSlice",
      requestType = semidx.runtime.grpc.v1.LiteralFileSliceRequest.class,
      responseType = semidx.runtime.grpc.v1.LiteralFileSliceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.LiteralFileSliceRequest,
      semidx.runtime.grpc.v1.LiteralFileSliceResponse> getLiteralFileSliceMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.LiteralFileSliceRequest, semidx.runtime.grpc.v1.LiteralFileSliceResponse> getLiteralFileSliceMethod;
    if ((getLiteralFileSliceMethod = RuntimeServiceGrpc.getLiteralFileSliceMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getLiteralFileSliceMethod = RuntimeServiceGrpc.getLiteralFileSliceMethod) == null) {
          RuntimeServiceGrpc.getLiteralFileSliceMethod = getLiteralFileSliceMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.LiteralFileSliceRequest, semidx.runtime.grpc.v1.LiteralFileSliceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "LiteralFileSlice"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.LiteralFileSliceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.LiteralFileSliceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("LiteralFileSlice"))
              .build();
        }
      }
    }
    return getLiteralFileSliceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.SnapshotDiffRequest,
      semidx.runtime.grpc.v1.SnapshotDiffResponse> getSnapshotDiffMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SnapshotDiff",
      requestType = semidx.runtime.grpc.v1.SnapshotDiffRequest.class,
      responseType = semidx.runtime.grpc.v1.SnapshotDiffResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.SnapshotDiffRequest,
      semidx.runtime.grpc.v1.SnapshotDiffResponse> getSnapshotDiffMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.SnapshotDiffRequest, semidx.runtime.grpc.v1.SnapshotDiffResponse> getSnapshotDiffMethod;
    if ((getSnapshotDiffMethod = RuntimeServiceGrpc.getSnapshotDiffMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getSnapshotDiffMethod = RuntimeServiceGrpc.getSnapshotDiffMethod) == null) {
          RuntimeServiceGrpc.getSnapshotDiffMethod = getSnapshotDiffMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.SnapshotDiffRequest, semidx.runtime.grpc.v1.SnapshotDiffResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SnapshotDiff"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.SnapshotDiffRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.SnapshotDiffResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("SnapshotDiff"))
              .build();
        }
      }
    }
    return getSnapshotDiffMethod;
  }

  private static volatile io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.TraverseRelationsRequest,
      semidx.runtime.grpc.v1.TraverseRelationsResponse> getTraverseRelationsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TraverseRelations",
      requestType = semidx.runtime.grpc.v1.TraverseRelationsRequest.class,
      responseType = semidx.runtime.grpc.v1.TraverseRelationsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.TraverseRelationsRequest,
      semidx.runtime.grpc.v1.TraverseRelationsResponse> getTraverseRelationsMethod() {
    io.grpc.MethodDescriptor<semidx.runtime.grpc.v1.TraverseRelationsRequest, semidx.runtime.grpc.v1.TraverseRelationsResponse> getTraverseRelationsMethod;
    if ((getTraverseRelationsMethod = RuntimeServiceGrpc.getTraverseRelationsMethod) == null) {
      synchronized (RuntimeServiceGrpc.class) {
        if ((getTraverseRelationsMethod = RuntimeServiceGrpc.getTraverseRelationsMethod) == null) {
          RuntimeServiceGrpc.getTraverseRelationsMethod = getTraverseRelationsMethod =
              io.grpc.MethodDescriptor.<semidx.runtime.grpc.v1.TraverseRelationsRequest, semidx.runtime.grpc.v1.TraverseRelationsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TraverseRelations"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.TraverseRelationsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  semidx.runtime.grpc.v1.TraverseRelationsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RuntimeServiceMethodDescriptorSupplier("TraverseRelations"))
              .build();
        }
      }
    }
    return getTraverseRelationsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RuntimeServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceStub>() {
        @java.lang.Override
        public RuntimeServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RuntimeServiceStub(channel, callOptions);
        }
      };
    return RuntimeServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RuntimeServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceBlockingStub>() {
        @java.lang.Override
        public RuntimeServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RuntimeServiceBlockingStub(channel, callOptions);
        }
      };
    return RuntimeServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RuntimeServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RuntimeServiceFutureStub>() {
        @java.lang.Override
        public RuntimeServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RuntimeServiceFutureStub(channel, callOptions);
        }
      };
    return RuntimeServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
   * method names remain unchanged, but adding the service under this versioned
   * proto package intentionally changes the full service name (and therefore the
   * full wire method paths) from semidx.RuntimeService to
   * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
   * </pre>
   */
  public interface AsyncService {

    /**
     */
    default void health(semidx.runtime.grpc.v1.HealthRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     */
    default void createIndex(semidx.runtime.grpc.v1.CreateIndexRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.CreateIndexResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCreateIndexMethod(), responseObserver);
    }

    /**
     */
    default void resolveContext(semidx.runtime.grpc.v1.ResolveContextRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ResolveContextResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getResolveContextMethod(), responseObserver);
    }

    /**
     */
    default void expandContext(semidx.runtime.grpc.v1.ExpandContextRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ExpandContextResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getExpandContextMethod(), responseObserver);
    }

    /**
     */
    default void fetchContextDetail(semidx.runtime.grpc.v1.FetchContextDetailRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.FetchContextDetailResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFetchContextDetailMethod(), responseObserver);
    }

    /**
     */
    default void literalFileSlice(semidx.runtime.grpc.v1.LiteralFileSliceRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.LiteralFileSliceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getLiteralFileSliceMethod(), responseObserver);
    }

    /**
     */
    default void snapshotDiff(semidx.runtime.grpc.v1.SnapshotDiffRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.SnapshotDiffResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSnapshotDiffMethod(), responseObserver);
    }

    /**
     */
    default void traverseRelations(semidx.runtime.grpc.v1.TraverseRelationsRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.TraverseRelationsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTraverseRelationsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RuntimeService.
   * <pre>
   * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
   * method names remain unchanged, but adding the service under this versioned
   * proto package intentionally changes the full service name (and therefore the
   * full wire method paths) from semidx.RuntimeService to
   * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
   * </pre>
   */
  public static abstract class RuntimeServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RuntimeServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RuntimeService.
   * <pre>
   * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
   * method names remain unchanged, but adding the service under this versioned
   * proto package intentionally changes the full service name (and therefore the
   * full wire method paths) from semidx.RuntimeService to
   * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
   * </pre>
   */
  public static final class RuntimeServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RuntimeServiceStub> {
    private RuntimeServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RuntimeServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RuntimeServiceStub(channel, callOptions);
    }

    /**
     */
    public void health(semidx.runtime.grpc.v1.HealthRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void createIndex(semidx.runtime.grpc.v1.CreateIndexRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.CreateIndexResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCreateIndexMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void resolveContext(semidx.runtime.grpc.v1.ResolveContextRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ResolveContextResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getResolveContextMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void expandContext(semidx.runtime.grpc.v1.ExpandContextRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ExpandContextResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getExpandContextMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void fetchContextDetail(semidx.runtime.grpc.v1.FetchContextDetailRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.FetchContextDetailResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFetchContextDetailMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void literalFileSlice(semidx.runtime.grpc.v1.LiteralFileSliceRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.LiteralFileSliceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getLiteralFileSliceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void snapshotDiff(semidx.runtime.grpc.v1.SnapshotDiffRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.SnapshotDiffResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSnapshotDiffMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void traverseRelations(semidx.runtime.grpc.v1.TraverseRelationsRequest request,
        io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.TraverseRelationsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTraverseRelationsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RuntimeService.
   * <pre>
   * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
   * method names remain unchanged, but adding the service under this versioned
   * proto package intentionally changes the full service name (and therefore the
   * full wire method paths) from semidx.RuntimeService to
   * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
   * </pre>
   */
  public static final class RuntimeServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RuntimeServiceBlockingStub> {
    private RuntimeServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RuntimeServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RuntimeServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public semidx.runtime.grpc.v1.HealthResponse health(semidx.runtime.grpc.v1.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.CreateIndexResponse createIndex(semidx.runtime.grpc.v1.CreateIndexRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCreateIndexMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.ResolveContextResponse resolveContext(semidx.runtime.grpc.v1.ResolveContextRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getResolveContextMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.ExpandContextResponse expandContext(semidx.runtime.grpc.v1.ExpandContextRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getExpandContextMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.FetchContextDetailResponse fetchContextDetail(semidx.runtime.grpc.v1.FetchContextDetailRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFetchContextDetailMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.LiteralFileSliceResponse literalFileSlice(semidx.runtime.grpc.v1.LiteralFileSliceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getLiteralFileSliceMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.SnapshotDiffResponse snapshotDiff(semidx.runtime.grpc.v1.SnapshotDiffRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSnapshotDiffMethod(), getCallOptions(), request);
    }

    /**
     */
    public semidx.runtime.grpc.v1.TraverseRelationsResponse traverseRelations(semidx.runtime.grpc.v1.TraverseRelationsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTraverseRelationsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RuntimeService.
   * <pre>
   * RuntimeService is the unary gRPC surface of the semidx runtime edge. RPC
   * method names remain unchanged, but adding the service under this versioned
   * proto package intentionally changes the full service name (and therefore the
   * full wire method paths) from semidx.RuntimeService to
   * semidx.runtime.grpc.v1.RuntimeService; see ADR-042.
   * </pre>
   */
  public static final class RuntimeServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RuntimeServiceFutureStub> {
    private RuntimeServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RuntimeServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RuntimeServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.HealthResponse> health(
        semidx.runtime.grpc.v1.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.CreateIndexResponse> createIndex(
        semidx.runtime.grpc.v1.CreateIndexRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCreateIndexMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.ResolveContextResponse> resolveContext(
        semidx.runtime.grpc.v1.ResolveContextRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getResolveContextMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.ExpandContextResponse> expandContext(
        semidx.runtime.grpc.v1.ExpandContextRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getExpandContextMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.FetchContextDetailResponse> fetchContextDetail(
        semidx.runtime.grpc.v1.FetchContextDetailRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFetchContextDetailMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.LiteralFileSliceResponse> literalFileSlice(
        semidx.runtime.grpc.v1.LiteralFileSliceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getLiteralFileSliceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.SnapshotDiffResponse> snapshotDiff(
        semidx.runtime.grpc.v1.SnapshotDiffRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSnapshotDiffMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<semidx.runtime.grpc.v1.TraverseRelationsResponse> traverseRelations(
        semidx.runtime.grpc.v1.TraverseRelationsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTraverseRelationsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HEALTH = 0;
  private static final int METHODID_CREATE_INDEX = 1;
  private static final int METHODID_RESOLVE_CONTEXT = 2;
  private static final int METHODID_EXPAND_CONTEXT = 3;
  private static final int METHODID_FETCH_CONTEXT_DETAIL = 4;
  private static final int METHODID_LITERAL_FILE_SLICE = 5;
  private static final int METHODID_SNAPSHOT_DIFF = 6;
  private static final int METHODID_TRAVERSE_RELATIONS = 7;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_HEALTH:
          serviceImpl.health((semidx.runtime.grpc.v1.HealthRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.HealthResponse>) responseObserver);
          break;
        case METHODID_CREATE_INDEX:
          serviceImpl.createIndex((semidx.runtime.grpc.v1.CreateIndexRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.CreateIndexResponse>) responseObserver);
          break;
        case METHODID_RESOLVE_CONTEXT:
          serviceImpl.resolveContext((semidx.runtime.grpc.v1.ResolveContextRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ResolveContextResponse>) responseObserver);
          break;
        case METHODID_EXPAND_CONTEXT:
          serviceImpl.expandContext((semidx.runtime.grpc.v1.ExpandContextRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.ExpandContextResponse>) responseObserver);
          break;
        case METHODID_FETCH_CONTEXT_DETAIL:
          serviceImpl.fetchContextDetail((semidx.runtime.grpc.v1.FetchContextDetailRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.FetchContextDetailResponse>) responseObserver);
          break;
        case METHODID_LITERAL_FILE_SLICE:
          serviceImpl.literalFileSlice((semidx.runtime.grpc.v1.LiteralFileSliceRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.LiteralFileSliceResponse>) responseObserver);
          break;
        case METHODID_SNAPSHOT_DIFF:
          serviceImpl.snapshotDiff((semidx.runtime.grpc.v1.SnapshotDiffRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.SnapshotDiffResponse>) responseObserver);
          break;
        case METHODID_TRAVERSE_RELATIONS:
          serviceImpl.traverseRelations((semidx.runtime.grpc.v1.TraverseRelationsRequest) request,
              (io.grpc.stub.StreamObserver<semidx.runtime.grpc.v1.TraverseRelationsResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.HealthRequest,
              semidx.runtime.grpc.v1.HealthResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getCreateIndexMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.CreateIndexRequest,
              semidx.runtime.grpc.v1.CreateIndexResponse>(
                service, METHODID_CREATE_INDEX)))
        .addMethod(
          getResolveContextMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.ResolveContextRequest,
              semidx.runtime.grpc.v1.ResolveContextResponse>(
                service, METHODID_RESOLVE_CONTEXT)))
        .addMethod(
          getExpandContextMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.ExpandContextRequest,
              semidx.runtime.grpc.v1.ExpandContextResponse>(
                service, METHODID_EXPAND_CONTEXT)))
        .addMethod(
          getFetchContextDetailMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.FetchContextDetailRequest,
              semidx.runtime.grpc.v1.FetchContextDetailResponse>(
                service, METHODID_FETCH_CONTEXT_DETAIL)))
        .addMethod(
          getLiteralFileSliceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.LiteralFileSliceRequest,
              semidx.runtime.grpc.v1.LiteralFileSliceResponse>(
                service, METHODID_LITERAL_FILE_SLICE)))
        .addMethod(
          getSnapshotDiffMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.SnapshotDiffRequest,
              semidx.runtime.grpc.v1.SnapshotDiffResponse>(
                service, METHODID_SNAPSHOT_DIFF)))
        .addMethod(
          getTraverseRelationsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              semidx.runtime.grpc.v1.TraverseRelationsRequest,
              semidx.runtime.grpc.v1.TraverseRelationsResponse>(
                service, METHODID_TRAVERSE_RELATIONS)))
        .build();
  }

  private static abstract class RuntimeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RuntimeServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return semidx.runtime.grpc.v1.RuntimeProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RuntimeService");
    }
  }

  private static final class RuntimeServiceFileDescriptorSupplier
      extends RuntimeServiceBaseDescriptorSupplier {
    RuntimeServiceFileDescriptorSupplier() {}
  }

  private static final class RuntimeServiceMethodDescriptorSupplier
      extends RuntimeServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RuntimeServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RuntimeServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RuntimeServiceFileDescriptorSupplier())
              .addMethod(getHealthMethod())
              .addMethod(getCreateIndexMethod())
              .addMethod(getResolveContextMethod())
              .addMethod(getExpandContextMethod())
              .addMethod(getFetchContextDetailMethod())
              .addMethod(getLiteralFileSliceMethod())
              .addMethod(getSnapshotDiffMethod())
              .addMethod(getTraverseRelationsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
