package io.github.pplr.bazelcache.client

import build.bazel.remote.execution.v2.Action
import build.bazel.remote.execution.v2.ActionResult
import build.bazel.remote.execution.v2.Command
import build.bazel.remote.execution.v2.Digest
import build.bazel.remote.execution.v2.OutputFile

/**
 * Maps a Gradle build cache key onto a Bazel Action Cache key.
 *
 * ## Why an indirection is needed
 *
 * A CAS address must be the SHA-256 of the blob's own content. At `load()` time
 * Gradle gives us a cache key but not the content, so the CAS address cannot be
 * recomputed. We therefore store the entry in the CAS under its content digest,
 * and publish an `ActionResult` in the Action Cache -- keyed by something we
 * *can* derive from the Gradle key alone -- that points at it.
 *
 * ## Why a real Action rather than an opaque hash
 *
 * REAPI states that before writing an `ActionResult` the client "MUST first
 * upload the Action that produced the result, along with its Command, into the
 * CAS". Synthesising a genuine `Action` keeps us compatible with servers strict
 * enough to check, and means an operator can fetch the Action from the CAS and
 * see what produced an entry. Hashing the Gradle key into an arbitrary digest
 * would satisfy neither.
 *
 * The `Command` is constant for a given [keyVersion], so it is uploaded at most
 * once per build. Only the ~80-byte `Action` is per-entry.
 *
 * ## Stability
 *
 * [actionKeyFor] defines this plugin's entire keyspace. Any change to the proto
 * subset, to which fields are populated, or to the strings below silently
 * invalidates every entry on every server running this plugin. Such a change
 * requires a [keyVersion] bump. Golden vectors in `CacheKeyMapperTest` freeze
 * the current behaviour.
 */
class CacheKeyMapper(val keyVersion: String = DEFAULT_KEY_VERSION) {

    /**
     * Constant for a given [keyVersion]. `output_paths` matches the single
     * [ENTRY_PATH] we declare in every `ActionResult`.
     */
    val command: Command = Command.newBuilder()
        .addArguments(TOOL_ARGUMENT)
        .addArguments(keyVersion)
        .addOutputPaths(ENTRY_PATH)
        .build()

    val commandBytes: ByteArray = command.toByteArray()
    val commandDigest: Digest = Digests.digestOf(commandBytes)

    /**
     * The synthetic `Action` for [gradleKey].
     *
     * The key goes in `salt` as raw UTF-8 of the string Gradle gave us -- not
     * hex-decoded. `BuildCacheKey.getHashCode()` is typed as a String and Gradle
     * has changed its hash function before, so treating it as opaque text keeps
     * the mapping correct whatever shape it takes.
     */
    fun actionFor(gradleKey: String): Action = Action.newBuilder()
        .setCommandDigest(commandDigest)
        // Empty Directory: serializes to zero bytes, so this is the empty-blob
        // digest. Never uploaded -- servers treat the empty blob as always present.
        .setInputRootDigest(Digests.EMPTY)
        .setSalt(com.google.protobuf.ByteString.copyFromUtf8(gradleKey))
        .build()

    /** Serialized [actionFor] bytes; upload these to the CAS under [actionKeyFor]. */
    fun actionBytesFor(gradleKey: String): ByteArray = actionFor(gradleKey).toByteArray()

    /**
     * The Action Cache key for [gradleKey]: the true digest of the serialized
     * `Action`. Pure and cheap -- `load()` computes it with no network at all.
     */
    fun actionKeyFor(gradleKey: String): Digest = Digests.digestOf(actionBytesFor(gradleKey))

    /** The `ActionResult` published in the AC, pointing at the entry blob. */
    fun actionResultFor(entryBlob: Digest): ActionResult = ActionResult.newBuilder()
        .addOutputFiles(
            OutputFile.newBuilder()
                .setPath(ENTRY_PATH)
                .setDigest(entryBlob)
                .build(),
        )
        // exit_code is left at its proto3 default of 0 and is therefore absent
        // from the wire, which is what a real successful action would produce.
        .build()

    /**
     * The entry blob an [ActionResult] points at, or null if it does not name
     * one we can use.
     *
     * A malformed or unexpected result is a cache miss, never an error: the
     * server may be shared with real Bazel builds, and a key collision or an
     * entry written by a future version of this plugin must degrade quietly.
     */
    fun entryBlobOf(result: ActionResult): Digest? {
        val file = result.outputFilesList.firstOrNull { it.path == ENTRY_PATH }
            ?: result.outputFilesList.firstOrNull()
            ?: return null
        if (!file.hasDigest()) return null
        val digest = file.digest
        if (!Digests.isValidHash(digest.hash) || digest.sizeBytes < 0) return null
        return digest
    }

    companion object {
        const val DEFAULT_KEY_VERSION = "v1"

        /**
         * Namespaces our Action Cache entries away from real Bazel actions.
         * Without it a Gradle entry could be mistaken for an action result in a
         * cache shared with Bazel builds.
         */
        const val TOOL_ARGUMENT = "gradle-build-cache"

        /** Relative path of the single output file. Must not start with '/'. */
        const val ENTRY_PATH = "entry"
    }
}
