# Server compatibility

## Verified

| Server | Transport | Status |
|---|---|---|
| **bazel-remote v2.6.2** | HTTP | ✅ Verified by the integration suite, with **AC validation enabled** (its default). No server flags required. |
| **bazel-remote v2.6.2** | gRPC | ✅ Verified by the same suite over the REAPI port (`:9092`), including an entry stored over one transport and loaded over the other. |

The integration tests in `RealBazelRemoteIntegrationTest` and
`RealBazelRemoteGrpcIntegrationTest` run against a real server and assert that a
synthetic `ActionResult` is accepted by the validator, survives the server's
AC→CAS dependency check, and still parses after the server rewrites it.

Run them yourself:

```bash
cd examples/quickstart && podman compose up -d
BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ BAZEL_REMOTE_GRPC_URL=grpc://127.0.0.1:9092 \
  ./gradlew :cache-client:integrationTest
```

## Expected to work, not yet verified

| Server | Transport | Notes |
|---|---|---|
| nginx + WebDAV | HTTP | A dumb object store. No AC validation, and **no AC→CAS dependency check** — a dangling entry returns the AC entry followed by a 404 for the blob. The client treats that as a miss. |
| S3 / GCS / MinIO / Artifactory | HTTP | As above. |
| **Buildbarn** (`bb-storage`) | gRPC | gRPC only — it exposes no `/ac/` or `/cas/` HTTP cache endpoints. Wraps the AC in a completeness-checking layer, so dangling entries read as misses. |
| **BuildBuddy** | gRPC | gRPC only; the cache listens on :1985 while :8080 is the web UI, which has no cache routes. Rewrites `ActionResult` on write and computes AC writability per API key — a read-only key earns the `update_enabled` warning. |
| **NativeLink** | gRPC | gRPC on :50051. Licensed FSL-1.1-ALv2, which is **not** an OSI-approved open source licence. |
| **EngFlow** | gRPC | Commercial. |

These have not been exercised in CI. Reports welcome.

## The gRPC transport

Selected by the endpoint, exactly as Bazel selects one for `--remote_cache`:
`grpc://host:port` is plaintext, `grpcs://host:port` is TLS against the JVM's
default trust store, and an endpoint with no scheme is gRPC over TLS. The calls
follow Bazel's `GrpcCacheClient`; see [PROTOCOL.md](PROTOCOL.md#over-grpc).

Not yet supported: custom CA certificates and client certificates (Bazel's
`--tls_certificate`, `--tls_client_certificate`), credential helpers, `unix://`
targets, and zstd-compressed blobs.

## Not applicable

| Server | Why |
|---|---|
| Gradle Develocity / Build Cache Node | Implements *Gradle's own* HTTP build cache protocol, not REAPI. Gradle already talks to it natively; this plugin is for the opposite case. |

## Server-side settings worth knowing

**`--max_blob_size`** — operators often set this to around 10 MiB. Gradle task
outputs routinely exceed it. The plugin treats a size rejection as a skipped
store, never a failure, so one oversized entry does not disable caching for the
rest of the build. Set `maxEntrySizeBytes` to skip them client-side and save the
round trip.

**`--disable_http_ac_validation`** — not required. The plugin is designed for an
unmodified server, and the integration suite runs against the validating default.

**Instance names and path prefixes** — the plugin treats `instanceName` exactly as
Bazel treats `--remote_instance_name`: it is ignored over HTTP, and over gRPC it is
sent as the REAPI `instance_name` field and as the prefix of every ByteStream resource
name. Over HTTP the only prefix is the endpoint's own path.

A path prefix does **not** isolate entries on a default bazel-remote: it parses the
prefix and discards it, so `/team-a/ac/X` and `/team-b/ac/X` are the same entry. Only
`--enable_ac_key_instance_mangling` changes that, and then for the Action Cache only.
Use separate servers, or separate `keyVersion` values, if you need hard isolation.

**Eviction** — hit rate is governed by *CAS* eviction, not AC eviction: an entry
is only usable while the blob it names survives. A cache sized for Bazel
artifacts may evict Gradle entries sooner than the AC entry count suggests.
