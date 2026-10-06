package io.github.pplr.bazelcache.client.grpc

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.FindMissingBlobsRequest
import build.bazel.remote.execution.v2.FindMissingBlobsResponse
import build.bazel.remote.execution.v2.GetActionResultRequest
import build.bazel.remote.execution.v2.GetCapabilitiesRequest
import build.bazel.remote.execution.v2.ServerCapabilities
import build.bazel.remote.execution.v2.UpdateActionResultRequest
import com.google.bytestream.ByteStreamProto.ReadRequest
import com.google.bytestream.ByteStreamProto.ReadResponse
import com.google.bytestream.ByteStreamProto.WriteRequest
import com.google.bytestream.ByteStreamProto.WriteResponse
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import java.io.InputStream

/**
 * The REAPI and ByteStream methods a cache client calls, declared by hand.
 *
 * The alternative is protoc's gRPC plugin, whose generated stubs need
 * `grpc-protobuf` -- and that pulls in `proto-google-common-protos` and the
 * protobuf well-known types. Our shading relies on our protos importing no
 * well-known types (see gradle-plugin/build.gradle.kts), and a cache client
 * needs only six methods, so they are spelled out here instead.
 *
 * Full method names are `<proto package>.<Service>/<Method>`, verbatim from
 * upstream: they are the HTTP/2 paths the server routes on.
 */
object ReapiMethods {

    private const val CAPABILITIES = "build.bazel.remote.execution.v2.Capabilities"
    private const val ACTION_CACHE = "build.bazel.remote.execution.v2.ActionCache"
    private const val CAS = "build.bazel.remote.execution.v2.ContentAddressableStorage"
    private const val BYTE_STREAM = "google.bytestream.ByteStream"

    val GET_CAPABILITIES: MethodDescriptor<GetCapabilitiesRequest, ServerCapabilities> = unary(
        "$CAPABILITIES/GetCapabilities",
        GetCapabilitiesRequest.parser(),
        ServerCapabilities.parser(),
    )

    val GET_ACTION_RESULT: MethodDescriptor<GetActionResultRequest, ActionResult> = unary(
        "$ACTION_CACHE/GetActionResult",
        GetActionResultRequest.parser(),
        ActionResult.parser(),
    )

    val UPDATE_ACTION_RESULT: MethodDescriptor<UpdateActionResultRequest, ActionResult> = unary(
        "$ACTION_CACHE/UpdateActionResult",
        UpdateActionResultRequest.parser(),
        ActionResult.parser(),
    )

    val FIND_MISSING_BLOBS: MethodDescriptor<FindMissingBlobsRequest, FindMissingBlobsResponse> = unary(
        "$CAS/FindMissingBlobs",
        FindMissingBlobsRequest.parser(),
        FindMissingBlobsResponse.parser(),
    )

    val READ: MethodDescriptor<ReadRequest, ReadResponse> = method(
        MethodType.SERVER_STREAMING,
        "$BYTE_STREAM/Read",
        ReadRequest.parser(),
        ReadResponse.parser(),
    )

    val WRITE: MethodDescriptor<WriteRequest, WriteResponse> = method(
        MethodType.CLIENT_STREAMING,
        "$BYTE_STREAM/Write",
        WriteRequest.parser(),
        WriteResponse.parser(),
    )

    private fun <Req : MessageLite, Res : MessageLite> unary(
        name: String,
        requestParser: Parser<Req>,
        responseParser: Parser<Res>,
    ) = method(MethodType.UNARY, name, requestParser, responseParser)

    private fun <Req : MessageLite, Res : MessageLite> method(
        type: MethodType,
        name: String,
        requestParser: Parser<Req>,
        responseParser: Parser<Res>,
    ): MethodDescriptor<Req, Res> = MethodDescriptor.newBuilder<Req, Res>()
        .setType(type)
        .setFullMethodName(name)
        .setRequestMarshaller(ProtoMarshaller(requestParser))
        .setResponseMarshaller(ProtoMarshaller(responseParser))
        .build()

    /**
     * What `grpc-protobuf`'s `ProtoUtils.marshaller` does, minus the zero-copy
     * and well-known-type support we have no use for. Both directions are
     * needed: the request side by the client, the response side by test servers.
     */
    class ProtoMarshaller<T : MessageLite>(private val parser: Parser<T>) : MethodDescriptor.Marshaller<T> {
        override fun stream(value: T): InputStream = value.toByteArray().inputStream()
        override fun parse(stream: InputStream): T = stream.use { parser.parseFrom(it) }
    }
}
