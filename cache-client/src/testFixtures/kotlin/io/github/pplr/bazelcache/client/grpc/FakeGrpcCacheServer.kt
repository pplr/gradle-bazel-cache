package io.github.pplr.bazelcache.client.grpc

import build.bazel.remote.execution.v2.ActionCacheUpdateCapabilities
import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.CacheCapabilities
import build.bazel.remote.execution.v2.Digest
import build.bazel.remote.execution.v2.DigestFunction
import build.bazel.remote.execution.v2.FindMissingBlobsResponse
import build.bazel.remote.execution.v2.ServerCapabilities
import com.google.bytestream.ByteStreamProto.ReadResponse
import com.google.bytestream.ByteStreamProto.WriteRequest
import com.google.bytestream.ByteStreamProto.WriteResponse
import com.google.protobuf.ByteString
import io.github.pplr.bazelcache.client.Digests
import io.grpc.InsecureServerCredentials
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.okhttp.OkHttpServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process stand-in for a REAPI cache over gRPC.
 *
 * A real socket server, for the same reason as `FakeBazelCacheServer`: the
 * behaviours worth testing are wire-level. It is at least as strict as
 * bazel-remote about what it accepts -- malformed resource names, non-contiguous
 * write offsets, and blobs whose content does not match their digest are all
 * INVALID_ARGUMENT -- because a fake kinder than the real server certifies bugs.
 */
class FakeGrpcCacheServer : Closeable {

    /** Keyed by hash. Like a default bazel-remote, instance names do not partition. */
    val ac = ConcurrentHashMap<String, ActionResult>()
    val cas = ConcurrentHashMap<String, ByteArray>()

    /** Call count per method name, e.g. "Write" or "FindMissingBlobs". */
    val calls = ConcurrentHashMap<String, AtomicInteger>()

    /** Every instance name and ByteStream resource name seen, in order. */
    val instanceNames: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val resourceNames: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Request metadata of every call, keys lower-cased as gRPC sends them. */
    val headers: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

    /** When set, every call fails with this status instead of being served. */
    @Volatile var forceStatus: Status? = null

    /** Fail the first N calls with [forceStatus] (or UNAVAILABLE), then behave. */
    @Volatile var failFirst: Int = 0

    /** Serve CAS reads truncated to this many bytes, then end the stream OK. */
    @Volatile var truncateReadsTo: Int? = null

    /** Advertised in GetCapabilities. */
    @Volatile var digestFunctions: List<DigestFunction.Value> = listOf(DigestFunction.Value.SHA256)
    @Volatile var updateEnabled: Boolean = true

    private val server: Server = OkHttpServerBuilder.forPort(0, InsecureServerCredentials.create())
        .addService(ServerInterceptors.intercept(capabilities(), Recorder()))
        .addService(ServerInterceptors.intercept(actionCache(), Recorder()))
        .addService(ServerInterceptors.intercept(cas(), Recorder()))
        .addService(ServerInterceptors.intercept(byteStream(), Recorder()))
        .build()
        .start()

    val target: String get() = "grpc://127.0.0.1:${server.port}"

    fun count(method: String): Int = calls[method]?.get() ?: 0

    /** Records each call, then applies [forceStatus] / [failFirst] before the handler runs. */
    private inner class Recorder : ServerInterceptor {
        override fun <Req, Res> interceptCall(
            call: ServerCall<Req, Res>,
            metadata: Metadata,
            next: ServerCallHandler<Req, Res>,
        ): ServerCall.Listener<Req> {
            calls.computeIfAbsent(call.methodDescriptor.bareMethodName!!) { AtomicInteger() }.incrementAndGet()
            headers += metadata.keys().associateWith {
                metadata.get(Metadata.Key.of(it, Metadata.ASCII_STRING_MARSHALLER)).orEmpty()
            }
            val forced = forceStatus
            val failing = synchronized(this@FakeGrpcCacheServer) {
                if (failFirst > 0) { failFirst--; true } else false
            }
            if (failing || forced != null) {
                call.close(forced ?: Status.UNAVAILABLE, Metadata())
                return object : ServerCall.Listener<Req>() {}
            }
            return next.startCall(call, metadata)
        }
    }

    private fun capabilities() = ServerServiceDefinition.builder("build.bazel.remote.execution.v2.Capabilities")
        .addMethod(ReapiMethods.GET_CAPABILITIES, ServerCalls.asyncUnaryCall { request, out ->
            instanceNames += request.instanceName
            out.reply(
                ServerCapabilities.newBuilder().setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .addAllDigestFunctions(digestFunctions)
                        .setActionCacheUpdateCapabilities(
                            ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(updateEnabled),
                        ),
                ).build(),
            )
        })
        .build()

    private fun actionCache() = ServerServiceDefinition.builder("build.bazel.remote.execution.v2.ActionCache")
        .addMethod(ReapiMethods.GET_ACTION_RESULT, ServerCalls.asyncUnaryCall { request, out ->
            instanceNames += request.instanceName
            if (!valid(request.actionDigest)) return@asyncUnaryCall out.fail(Status.INVALID_ARGUMENT)
            val result = ac[request.actionDigest.hash]
                ?: return@asyncUnaryCall out.fail(Status.NOT_FOUND)
            out.reply(result)
        })
        .addMethod(ReapiMethods.UPDATE_ACTION_RESULT, ServerCalls.asyncUnaryCall { request, out ->
            instanceNames += request.instanceName
            if (!valid(request.actionDigest)) return@asyncUnaryCall out.fail(Status.INVALID_ARGUMENT)
            ac[request.actionDigest.hash] = request.actionResult
            out.reply(request.actionResult)
        })
        .build()

    private fun cas() = ServerServiceDefinition.builder("build.bazel.remote.execution.v2.ContentAddressableStorage")
        .addMethod(ReapiMethods.FIND_MISSING_BLOBS, ServerCalls.asyncUnaryCall { request, out ->
            instanceNames += request.instanceName
            if (!request.blobDigestsList.all(::valid)) return@asyncUnaryCall out.fail(Status.INVALID_ARGUMENT)
            val missing = request.blobDigestsList.filter { it.sizeBytes > 0 && !cas.containsKey(it.hash) }
            out.reply(FindMissingBlobsResponse.newBuilder().addAllMissingBlobDigests(missing).build())
        })
        .build()

    private fun byteStream() = ServerServiceDefinition.builder("google.bytestream.ByteStream")
        .addMethod(ReapiMethods.READ, ServerCalls.asyncServerStreamingCall { request, out ->
            resourceNames += request.resourceName
            val digest = parseResource(request.resourceName, upload = false)
                ?: return@asyncServerStreamingCall out.fail(Status.INVALID_ARGUMENT)
            val blob = (if (digest.sizeBytes == 0L) ByteArray(0) else cas[digest.hash])
                ?: return@asyncServerStreamingCall out.fail(Status.NOT_FOUND)
            val served = truncateReadsTo?.let { blob.copyOf(minOf(it, blob.size)) } ?: blob
            var offset = request.readOffset.toInt()
            while (offset < served.size) {
                val n = minOf(READ_CHUNK, served.size - offset)
                out.onNext(ReadResponse.newBuilder().setData(ByteString.copyFrom(served, offset, n)).build())
                offset += n
            }
            out.onCompleted()
        })
        .addMethod(ReapiMethods.WRITE, ServerCalls.asyncClientStreamingCall { out -> WriteHandler(out) })
        .build()

    private inner class WriteHandler(private val out: StreamObserver<WriteResponse>) : StreamObserver<WriteRequest> {
        private var digest: Digest? = null
        private var resource: String? = null
        private val buffer = ByteArrayOutputStream()
        private var done = false

        override fun onNext(request: WriteRequest) {
            if (done) return
            if (digest == null) {
                resource = request.resourceName
                resourceNames += request.resourceName
                digest = parseResource(request.resourceName, upload = true) ?: return reject()
            } else if (request.resourceName.isNotEmpty() && request.resourceName != resource) {
                return reject()
            }
            if (request.writeOffset != buffer.size().toLong()) return reject()
            request.data.writeTo(buffer)
            if (buffer.size() > digest!!.sizeBytes) return reject()
            if (request.finishWrite) {
                val bytes = buffer.toByteArray()
                if (bytes.size.toLong() != digest!!.sizeBytes || Digests.sha256Hex(bytes) != digest!!.hash) {
                    return reject()
                }
                cas[digest!!.hash] = bytes
                done = true
                out.reply(WriteResponse.newBuilder().setCommittedSize(bytes.size.toLong()).build())
            }
        }

        override fun onError(t: Throwable) {}

        override fun onCompleted() {
            // A client that half-closes without finish_write has not uploaded anything.
            if (!done) reject()
        }

        private fun reject() {
            if (done) return
            done = true
            out.fail(Status.INVALID_ARGUMENT)
        }
    }

    /**
     * Parses `[instance/]blobs/{hash}/{size}`, or `[instance/]uploads/{uuid}/blobs/{hash}/{size}`
     * when [upload]. The instance prefix is recorded, then ignored.
     */
    private fun parseResource(name: String, upload: Boolean): Digest? {
        val parts = name.split('/')
        val tail = if (upload) 5 else 3
        if (parts.size < tail) return null
        val rest = parts.takeLast(tail)
        if (upload && rest[0] != "uploads") return null
        if (rest[tail - 3] != "blobs") return null
        instanceNames += parts.dropLast(tail).joinToString("/")
        val size = rest[tail - 1].toLongOrNull() ?: return null
        val digest = Digest.newBuilder().setHash(rest[tail - 2]).setSizeBytes(size).build()
        return digest.takeIf(::valid)
    }

    private fun valid(digest: Digest): Boolean = Digests.isValidHash(digest.hash) && digest.sizeBytes >= 0

    private fun <T> StreamObserver<T>.reply(value: T) {
        onNext(value)
        onCompleted()
    }

    private fun StreamObserver<*>.fail(status: Status) = onError(status.asRuntimeException())

    fun reset() {
        ac.clear(); cas.clear(); calls.clear()
        instanceNames.clear(); resourceNames.clear(); headers.clear()
        forceStatus = null; failFirst = 0; truncateReadsTo = null
        digestFunctions = listOf(DigestFunction.Value.SHA256); updateEnabled = true
    }

    override fun close() {
        server.shutdownNow()
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
