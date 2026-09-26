# Server compatibility

## Verified

| Server | Transport | Status |
|---|---|---|
| **bazel-remote v2.6.2** | HTTP | ✅ Verified by the integration suite, with **AC validation enabled** (its default). No server flags required. |

The integration tests in `RealBazelRemoteIntegrationTest` run against a real
server and assert that a synthetic `ActionResult` is accepted by the validator,
survives the server's AC→CAS dependency check, and still parses after the server
rewrites it.

Run them yourself:

```bash
cd examples/quickstart && podman compose up -d
BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ ./gradlew :cache-client:integrationTest
```

## Expected to work, not yet verified

| Server | Transport | Notes |
|---|---|---|
| nginx + WebDAV | HTTP | A dumb object store. No AC validation, and **no AC→CAS dependency check** — a dangling entry returns the AC entry followed by a 404 for the blob. The client treats that as a miss. |
| S3 / GCS / MinIO / Artifactory | HTTP | As above. |

These have not been exercised in CI. Reports welcome.

## Requires gRPC (plugin 1.1)

| Server | Notes |
|---|---|
| **Buildbarn** (`bb-storage`) | gRPC only — it exposes no `/ac/` or `/cas/` HTTP cache endpoints. Wraps the AC in a completeness-checking layer, so dangling entries read as misses. |
| **BuildBuddy** | gRPC only; the cache listens on :1985 while :8080 is the web UI, which has no cache routes. Rewrites `ActionResult` on write and computes AC writability per API key. |
| **NativeLink** | gRPC on :50051. Licensed FSL-1.1-ALv2, which is **not** an OSI-approved open source licence. |
| **EngFlow** | Commercial. |

1.0 speaks the HTTP protocol only, so none of these work yet. That is the single
biggest gap in 1.0 and the reason 1.1 exists.

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

**`--enable_ac_key_instance_mangling`** — hashes the instance name into AC keys.
Harmless, provided every client sends the same `instanceName`.

**Eviction** — hit rate is governed by *CAS* eviction, not AC eviction: an entry
is only usable while the blob it names survives. A cache sized for Bazel
artifacts may evict Gradle entries sooner than the AC entry count suggests.
