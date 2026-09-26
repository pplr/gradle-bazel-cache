package io.github.pplr.bazelcache.client

import java.net.URI

/**
 * Builds `/ac/` and `/cas/` URLs for the Bazel HTTP cache protocol.
 *
 * The protocol is defined by Bazel's own `HttpCacheClient`: action cache blobs
 * live at `<base>/ac/<hash>` and CAS blobs at `<base>/cas/<hash>`, where `<hash>`
 * is base16. There is no protocol-level instance name -- any prefix is simply
 * part of the base URL, which is how `bazel --remote_cache=<url>` works too.
 *
 * bazel-remote matches the result against a strict path regex requiring an
 * optional prefix, then `ac/` or `cas/`, then exactly 64 lowercase hex
 * characters. A stray double slash or an unencoded segment is therefore a 400,
 * not a 404 -- and that distinction matters: 400 means we built a bad URL,
 * 404 means a cache miss.
 */
class CacheEndpoint(baseUrl: String, instanceName: String = "") {

    private val base: URI

    init {
        require(baseUrl.isNotBlank()) { "endpoint must not be blank" }
        val parsed = try {
            URI(baseUrl.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("endpoint is not a valid URL: $baseUrl", e)
        }
        require(parsed.isAbsolute) { "endpoint must be absolute (include a scheme): $baseUrl" }
        require(parsed.scheme.lowercase() in setOf("http", "https")) {
            "endpoint scheme must be http or https, was '${parsed.scheme}': $baseUrl"
        }
        require(!parsed.host.isNullOrBlank()) { "endpoint must include a host: $baseUrl" }

        // Normalise base + instance into a single prefix with exactly one
        // trailing slash, so joining never produces "//" (which bazel-remote
        // runs through path.Clean and may reject).
        val basePath = parsed.path.orEmpty().trim('/')
        val instance = instanceName.trim('/')
        val segments = listOf(basePath, instance).filter { it.isNotEmpty() }
        val path = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/") + "/"

        base = URI(parsed.scheme, parsed.userInfo, parsed.host, parsed.port, path, null, null)
    }

    fun actionCache(hash: String): URI = resolve(AC_PREFIX, hash)

    fun contentAddressableStorage(hash: String): URI = resolve(CAS_PREFIX, hash)

    /**
     * A URL that is always safe to GET as a liveness probe: the empty blob,
     * which every REAPI server treats as unconditionally present.
     */
    fun probe(): URI = contentAddressableStorage(Digests.EMPTY_SHA256_HEX)

    private fun resolve(prefix: String, hash: String): URI {
        require(Digests.isValidHash(hash)) {
            "cache key must be 64 lowercase hex characters, was '$hash' (${hash.length} chars)"
        }
        return base.resolve("$prefix$hash")
    }

    override fun toString(): String = base.toString()

    companion object {
        const val AC_PREFIX = "ac/"
        const val CAS_PREFIX = "cas/"
    }
}
