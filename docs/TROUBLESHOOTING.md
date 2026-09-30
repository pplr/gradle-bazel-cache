# Troubleshooting

## Start here

```bash
./gradlew build --build-cache --info | grep bazel-cache
```

Every build prints one summary line:

```
bazel-cache: 143 hits, 61 misses, 58 stores (412.3 MiB down, 88.1 MiB up)
```

It is logged at `WARN` when there were errors and `INFO` otherwise, so
`--info` shows it on a healthy build. If the line is absent, the plugin was
never asked to do anything — see *No cache activity* below.

## No cache activity at all

The build cache is **off by default**. Enable it with `--build-cache`, or
`org.gradle.caching=true` in `gradle.properties`.

Other causes, in order of likelihood:

- **`--offline`.** Gradle disables the remote cache before the plugin is ever
  constructed.
- **`push` defaults to `false` for remote caches.** Reads work, writes silently
  do not happen. Set `isPush = true` where you want stores, typically CI.
- **The local cache is answering first.** A local hit short-circuits the remote
  lookup entirely. That is correct and desirable, but it means a hit proves
  nothing about the remote. To test the remote specifically, set
  `local { isEnabled = false }`.
- **Nothing is cacheable.** Only `@CacheableTask` tasks and artifact transforms
  use the build cache.

## `... is not reachable; builds will run without the remote cache`

The plugin probes the server once when the build starts, and falls back to doing
nothing if the probe fails. This is deliberate: a typo in the endpoint should
cost one request, not one failed request per task.

```bash
curl -v http://your-cache:8080/cas/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
```

That is the empty blob, which every REAPI server reports as present. Expect
`200`. Then check, in order:

- The endpoint includes a **scheme**: `https://cache.example.com`, not `cache.example.com`.
- The port is the **cache** port. For BuildBuddy, `:8080` is the web UI and the
  cache is gRPC on `:1985` — which 1.0 cannot use.
- The server is HTTP-capable at all. **Buildbarn and BuildBuddy are gRPC-only**
  (see [SERVER-MATRIX.md](SERVER-MATRIX.md)).

`401`/`403` count as reachable — the server answered. A credential problem is
reported separately and with a clearer message.

## `instanceName has no effect over HTTP (as in Bazel)`

Expected, and matches Bazel: its HTTP cache client ignores `--remote_instance_name`
too. The value is kept for the gRPC transport. If you meant a path prefix, move it
into the endpoint:

```kotlin
endpoint = "https://cache.example.com/team-a/"
```

Upgrading from 1.0.0-beta: that release inserted `instanceName` into HTTP URLs. Moving
the prefix into `endpoint` reproduces the old URLs exactly.

## Everything is a miss

**Different Gradle versions produce different cache keys.** The key includes the
task implementation's classloader hash, so upgrading Gradle invalidates
everything. This is Gradle's behaviour, not the plugin's.

**Check `keyVersion` matches** across the builds meant to share a cache. It
partitions the keyspace by design.

**The blob may be evicted while the AC entry survives.** Cache hits depend on
*CAS* eviction; a cache sized for Bazel may drop Gradle entries early. Look at
`NumFiles`/`CurrSize` in `bazel-remote`'s `/status`.

**Confirm both sides agree on the key:**

```bash
./gradlew :app:compileJava --build-cache -Dorg.gradle.caching.debug=true
```

That prints `Build cache key for task ':app:compileJava' is <32 hex>`. The same
inputs must produce the same key on both machines; if they do not, the cause is
in the task's inputs, not in the cache backend.

## HTTP 400 on every store

The server is rejecting the request rather than the content. Two known causes:

- **Key width.** `bazel-remote` accepts only 64-character lowercase hex. The
  plugin always sends that; a 400 here suggests a proxy is rewriting paths.
- **Chunked uploads.** `bazel-remote` requires `Content-Length` and rejects
  chunked transfer encoding. The plugin always sends a length, but a proxy in
  between may re-chunk the request.

## Stores are skipped

```
bazel-cache: ... 12 skipped
```

Either the entry exceeded `maxEntrySizeBytes` (default 256 MiB, skipped
client-side to save a doomed upload), or the server refused it — operators often
set `--max_blob_size` around 10 MiB, which Gradle outputs routinely exceed.

Skips are not errors and never disable caching for other tasks.

## `caching disabled: ...` in the summary

The circuit breaker gave up. The reason is on the same line:

- **`authentication rejected`** — trips immediately and never retries. Retrying a
  rejected credential across thousands of tasks is the worst possible behaviour.
  Check the variable named by `tokenEnvironmentVariable` is exported *and
  visible to the Gradle daemon*; a variable set in a shell that started before
  the daemon will not be.
- **`N consecutive failures`** / **`failure rate above 50%`** — the server is
  unhealthy. The build still succeeds without caching.

The breaker retries after a quiet period and gives up permanently after repeated
trips, so a server that restarts mid-build recovers on its own.

## Builds feel slower with the cache on

A cold, distant cache costs a round trip per task and returns nothing. Check the
hit rate in the summary line. Mitigations:

- Set `isPush = true` on CI and leave it off locally, so developers read a cache
  that CI fills.
- Keep the **local** cache enabled in day-to-day use; it answers first and costs
  nothing.

## Getting a diagnosable report

```bash
./gradlew build --build-cache --info 2>&1 | grep -i bazel-cache
```

Per-operation detail is at `--debug`. Useful facts for a bug report: the summary
line, your server and version, whether AC validation is enabled, and the plugin
and Gradle versions.

Nothing here should ever fail a build. If a cache problem breaks your build,
that is a bug worth reporting — the only exception is `strict = true`, which
turns cache faults into build failures on purpose.
