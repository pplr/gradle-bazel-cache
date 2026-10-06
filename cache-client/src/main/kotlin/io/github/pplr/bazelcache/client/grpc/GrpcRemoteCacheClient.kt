package io.github.pplr.bazelcache.client.grpc

import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Digest
import build.bazel.remote.execution.v2.DigestFunction
import build.bazel.remote.execution.v2.FindMissingBlobsRequest
import build.bazel.remote.execution.v2.GetActionResultRequest
import build.bazel.remote.execution.v2.GetCapabilitiesRequest
import build.bazel.remote.execution.v2.ServerCapabilities
import build.bazel.remote.execution.v2.UpdateActionResultRequest
import com.google.bytestream.ByteStreamProto.ReadRequest
import com.google.bytestream.ByteStreamProto.WriteRequest
import com.google.bytestream.ByteStreamProto.WriteResponse
import com.google.protobuf.ByteString
import io.github.pplr.bazelcache.client.CacheIoException
import io.github.pplr.bazelcache.client.Digests
import io.github.pplr.bazelcache.client.RemoteCacheClient
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Remote Execution API over gRPC, following Bazel's `GrpcCacheClient`:
 *
 * - Action Cache through `ActionCache.GetActionResult` / `UpdateActionResult`;
 * - CAS blobs through ByteStream, at `{instance}/blobs/{hash}/{size}` for reads
 *   and `{instance}/uploads/{uuid}/blobs/{hash}/{size}` for writes;
 * - `FindMissingBlobs` before uploading;
 * - `GetCapabilities` as the startup check.
 *
 * Every request carries [instanceName] and `digest_function = SHA256`, as
 * Bazel's do. A `NOT_FOUND` is a miss, never an error.
 *
 * Thread-safe: a gRPC channel is, and this class holds no per-call state.
 */
class GrpcRemoteCacheClient private constructor(
    private val channel: ManagedChannel,
    private val instanceName: String,
    private val requestTimeout: Duration,
) : RemoteCacheClient {

    constructor(
        endpoint: GrpcEndpoint,
        instanceName: String = "",
        headers: Map<String, String> = emptyMap(),
        requestTimeout: Duration = Duration.ofSeconds(30),
    ) : this(newChannel(endpoint, headers), instanceName, requestTimeout)

    override fun getActionResult(actionKey: Digest): ActionResult? {
        val request = GetActionResultRequest.newBuilder()
            .setInstanceName(instanceName)
            .setActionDigest(actionKey)
            .setDigestFunction(DigestFunction.Value.SHA256)
            .build()
        return try {
            unary(ReapiMethods.GET_ACTION_RESULT, request, requestTimeout)
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) return null
            throw failure("GetActionResult", e)
        }
    }

    override fun updateActionResult(actionKey: Digest, result: ActionResult) {
        val request = UpdateActionResultRequest.newBuilder()
            .setInstanceName(instanceName)
            .setActionDigest(actionKey)
            .setActionResult(result)
            .setDigestFunction(DigestFunction.Value.SHA256)
            .build()
        try {
            unary(ReapiMethods.UPDATE_ACTION_RESULT, request, requestTimeout)
        } catch (e: StatusRuntimeException) {
            throw failure("UpdateActionResult", e)
        }
    }

    override val queriesMissingBlobs: Boolean get() = true

    override fun findMissingBlobs(digests: Collection<Digest>): Set<Digest> {
        if (digests.isEmpty()) return emptySet()
        val request = FindMissingBlobsRequest.newBuilder()
            .setInstanceName(instanceName)
            .addAllBlobDigests(digests)
            .setDigestFunction(DigestFunction.Value.SHA256)
            .build()
        val response = try {
            unary(ReapiMethods.FIND_MISSING_BLOBS, request, requestTimeout)
        } catch (e: StatusRuntimeException) {
            throw failure("FindMissingBlobs", e)
        }
        return response.missingBlobDigestsList.toSet()
    }

    override fun readBlob(digest: Digest, sink: OutputStream): Boolean {
        val call = channel.newCall(ReapiMethods.READ, callOptions(requestTimeout))
        val request = ReadRequest.newBuilder().setResourceName(readResourceName(digest)).build()

        // Verify while streaming, as the HTTP client does: a corrupt or truncated
        // blob must read as a miss, never reach Gradle's unpacker.
        val sha = Digests.newSha256()
        var total = 0L
        var finished = false
        try {
            val responses = ClientCalls.blockingServerStreamingCall(call, request)
            while (responses.hasNext()) {
                val data = responses.next().data
                if (total + data.size() > digest.sizeBytes) {
                    // More than the digest allows: a misbehaving server. Bazel
                    // refuses the extra bytes too.
                    return false
                }
                val bytes = data.toByteArray()
                sha.update(bytes)
                sink.write(bytes)
                total += data.size()
            }
            finished = true
        } catch (e: StatusRuntimeException) {
            // Like Bazel: an error after the last byte arrived does not matter.
            if (total != digest.sizeBytes) {
                if (e.status.code == Status.Code.NOT_FOUND) return false
                // A retry would start again from offset 0 and append to a sink
                // that already holds a prefix, so only a read that wrote nothing
                // may be retried.
                throw failure("Read ${request.resourceName}", e, retryable = total == 0L)
            }
            finished = true
        } finally {
            if (!finished) call.cancel("read abandoned", null)
        }

        if (total != digest.sizeBytes) return false
        return Digests.toHex(sha.digest()) == digest.hash
    }

    override fun writeBlob(digest: Digest, source: () -> InputStream) {
        // Servers treat the empty blob as always present; Bazel never uploads it.
        if (digest.sizeBytes == 0L) return

        val resourceName = writeResourceName(digest)
        val call = channel.newCall(ReapiMethods.WRITE, callOptions(requestTimeout))
        val listener = WriteListener()
        call.start(listener, Metadata())
        call.request(1)

        var offset = 0L
        var completed = false
        try {
            source().use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (offset < digest.sizeBytes) {
                    // The server may finish early, having found the blob already
                    // present; then there is nothing left to send.
                    if (!listener.awaitReady(call)) break

                    val want = minOf(CHUNK_SIZE.toLong(), digest.sizeBytes - offset).toInt()
                    readFully(input, buffer, want)
                    val request = WriteRequest.newBuilder()
                        .setWriteOffset(offset)
                        .setData(ByteString.copyFrom(buffer, 0, want))
                    if (offset == 0L) request.resourceName = resourceName
                    offset += want
                    if (offset == digest.sizeBytes) request.finishWrite = true
                    call.sendMessage(request.build())
                }
            }
            call.halfClose()

            val committed = try {
                listener.await()
            } catch (e: StatusRuntimeException) {
                // ALREADY_EXISTS is success: the server holds the blob.
                if (e.status.code == Status.Code.ALREADY_EXISTS) {
                    completed = true
                    return
                }
                throw failure("Write $resourceName", e, isWrite = true)
            }
            completed = true
            if (committed != digest.sizeBytes) {
                throw CacheIoException(
                    "Write $resourceName incomplete: committed_size $committed for ${digest.sizeBytes} total",
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CacheIoException("interrupted", e, retryable = false)
        } finally {
            if (!completed) call.cancel("write abandoned", null)
        }
    }

    /**
     * The server's capabilities, or null if it answered without them -- an
     * authentication failure or an unimplemented service. Either way a server
     * is there, which is all [probe] needs to know; a wrong credential is
     * reported later with a far clearer message than "unreachable" would give.
     *
     * Throws [CacheIoException] if the server cannot be reached at all.
     */
    fun getCapabilities(timeout: Duration = requestTimeout): ServerCapabilities? {
        val request = GetCapabilitiesRequest.newBuilder().setInstanceName(instanceName).build()
        return try {
            unary(ReapiMethods.GET_CAPABILITIES, request, timeout)
        } catch (e: StatusRuntimeException) {
            when (e.status.code) {
                Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED, Status.Code.UNIMPLEMENTED -> null
                else -> throw failure("GetCapabilities", e)
            }
        }
    }

    override fun probe(): Boolean = try {
        getCapabilities(PROBE_TIMEOUT)
        true
    } catch (_: Exception) {
        false
    }

    override fun close() {
        channel.shutdown()
        try {
            if (!channel.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) channel.shutdownNow()
        } catch (_: InterruptedException) {
            channel.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // --- plumbing -----------------------------------------------------------

    private fun readResourceName(digest: Digest): String =
        "${instancePrefix()}blobs/${digest.hash}/${digest.sizeBytes}"

    private fun writeResourceName(digest: Digest): String =
        "${instancePrefix()}uploads/${UUID.randomUUID()}/blobs/${digest.hash}/${digest.sizeBytes}"

    /** Bazel omits the separator entirely when there is no instance name. */
    private fun instancePrefix(): String = if (instanceName.isEmpty()) "" else "$instanceName/"

    private fun callOptions(timeout: Duration): CallOptions =
        CallOptions.DEFAULT.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)

    private fun <Req, Res> unary(method: MethodDescriptor<Req, Res>, request: Req, timeout: Duration): Res =
        ClientCalls.blockingUnaryCall(channel, method, callOptions(timeout), request)

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("blob source ended after fewer bytes than its digest declares")
            read += n
        }
    }

    /**
     * Turns a gRPC status into a [CacheIoException].
     *
     * Retryable codes are Bazel's (`RemoteRetrier`): UNKNOWN, DEADLINE_EXCEEDED,
     * ABORTED, INTERNAL, UNAVAILABLE and RESOURCE_EXHAUSTED, plus CANCELLED when
     * the thread was not interrupted. On a write, RESOURCE_EXHAUSTED is what
     * bazel-remote answers when it is out of space -- the gRPC face of the
     * HTTP 507 that [CacheIoException.payloadTooLarge] already covers.
     */
    private fun failure(
        operation: String,
        e: StatusRuntimeException,
        retryable: Boolean = true,
        isWrite: Boolean = false,
    ): CacheIoException {
        val code = e.status.code
        val interrupted = Thread.currentThread().isInterrupted
        return CacheIoException(
            "$operation returned $code${e.status.description?.let { ": $it" } ?: ""}",
            e,
            retryable = retryable && !interrupted &&
                (code in RETRYABLE_CODES || code == Status.Code.CANCELLED),
            authFailure = code == Status.Code.UNAUTHENTICATED || code == Status.Code.PERMISSION_DENIED,
            payloadTooLarge = isWrite && code == Status.Code.RESOURCE_EXHAUSTED,
        )
    }

    /**
     * Bridges the asynchronous ByteStream.Write call onto the calling thread,
     * honouring flow control: [awaitReady] blocks until the transport can take
     * another chunk, so a large blob is never buffered whole in memory.
     */
    private class WriteListener : ClientCall.Listener<WriteResponse>() {
        private val lock = Object()
        private var committedSize = -1L
        private var closed: Status? = null
        private var trailers: Metadata? = null

        override fun onMessage(message: WriteResponse) {
            synchronized(lock) { committedSize = message.committedSize }
        }

        override fun onReady() {
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onClose(status: Status, trailers: Metadata) {
            synchronized(lock) {
                closed = status
                this.trailers = trailers
                lock.notifyAll()
            }
        }

        /** True when the call can take a message; false once the server has closed it. */
        fun awaitReady(call: ClientCall<*, *>): Boolean = synchronized(lock) {
            while (closed == null && !call.isReady) lock.wait()
            closed == null
        }

        /** The committed size once the call closes, or the failure it closed with. */
        fun await(): Long = synchronized(lock) {
            while (closed == null) lock.wait()
            val status = closed!!
            if (!status.isOk) throw status.asRuntimeException(trailers)
            committedSize
        }
    }

    companion object {
        /** Deadline for the startup `GetCapabilities`, which doubles as the probe. */
        val PROBE_TIMEOUT: Duration = Duration.ofMillis(1500)

        /** Bazel's ByteStream chunk size. */
        private const val CHUNK_SIZE = 16 * 1024
        private const val CLOSE_TIMEOUT_SECONDS = 5L

        private val RETRYABLE_CODES = setOf(
            Status.Code.UNKNOWN,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.ABORTED,
            Status.Code.INTERNAL,
            Status.Code.UNAVAILABLE,
            Status.Code.RESOURCE_EXHAUSTED,
        )

        private fun newChannel(endpoint: GrpcEndpoint, headers: Map<String, String>): ManagedChannel {
            val builder = OkHttpChannelBuilder.forAddress(endpoint.host, endpoint.port)
                // As Bazel: the server is trusted, and the 4 MiB default only
                // breaks legitimately large messages.
                .maxInboundMessageSize(Int.MAX_VALUE)
            if (endpoint.tls) builder.useTransportSecurity() else builder.usePlaintext()
            if (headers.isNotEmpty()) {
                val metadata = Metadata()
                headers.forEach { (name, value) ->
                    metadata.put(Metadata.Key.of(name.lowercase(), Metadata.ASCII_STRING_MARSHALLER), value)
                }
                builder.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            }
            return builder.build()
        }
    }
}
