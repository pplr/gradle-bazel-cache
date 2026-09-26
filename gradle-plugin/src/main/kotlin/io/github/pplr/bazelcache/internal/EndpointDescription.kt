package io.github.pplr.bazelcache.internal

import java.net.URI

/**
 * Renders an endpoint for logs, `BuildCacheServiceFactory.Describer` output and
 * build scans.
 *
 * Describer values are logged and published into build scans, so anything that
 * could carry a credential must be stripped: URL userinfo (`https://user:pw@host`)
 * and the query string (a common place for signed-URL tokens).
 */
internal fun describeEndpoint(endpoint: String?): String {
    if (endpoint.isNullOrBlank()) return "(not set)"
    return try {
        val uri = URI(endpoint)
        buildString {
            uri.scheme?.let { append(it).append("://") }
            if (uri.userInfo != null) append("<redacted>@")
            append(uri.host ?: "")
            if (uri.port != -1) append(':').append(uri.port)
            uri.path?.takeIf { it.isNotEmpty() && it != "/" }?.let { append(it) }
            if (uri.query != null) append("?<redacted>")
        }
    } catch (_: Exception) {
        // Never let a malformed endpoint break describing; the factory validates it.
        "(unparseable)"
    }
}
