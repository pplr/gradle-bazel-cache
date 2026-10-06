# Quickstart

A Gradle build whose task outputs are cached in a real Bazel remote cache.
Two commands, and you should see `FROM-CACHE`.

## 1. Start a cache server

```bash
cd examples/quickstart
podman compose up -d      # or: docker compose up -d
curl http://127.0.0.1:9090/status
```

This runs `bazel-remote` v2.6.2 with **AC validation enabled** — its default.
The plugin is designed to work against an unmodified server, and this quickstart
is here to prove it: no special flags, no proprietary extensions.

## 2. Build twice

```bash
../../gradlew :app:compileJava --build-cache     # cold: stores
rm -rf app/build .gradle                         # forget everything local
../../gradlew :app:compileJava --build-cache     # warm: FROM-CACHE
```

```
> Task :app:compileJava FROM-CACHE
```

The second build never compiles anything. It fetched the output from the Bazel
cache — the same server a Bazel build would use.

The local cache is deliberately **disabled** in `settings.gradle.kts`. A local
hit would short-circuit the remote lookup and prove nothing.

## What just happened

```
store:  PUT /cas/<sha256 of outputs>     the task outputs
        PUT /cas/<sha256 of Action>      a synthetic Action describing them
        PUT /ac/<digest of that Action>  an ActionResult pointing at the outputs

load:   GET /ac/<digest of that Action>  derived from the Gradle key, no network
        GET /cas/<digest it names>       the outputs
```

Gradle's own `HttpBuildCache` cannot do this: it addresses entries by a 32-character
key, and a Bazel cache requires 64-character SHA-256 paths. See
[docs/PROTOCOL.md](../../docs/PROTOCOL.md) for why the Action Cache indirection
is necessary rather than merely convenient.

Inspect what was written:

```bash
curl -s http://127.0.0.1:9090/status | jq '{NumFiles, CurrSize}'
```

## Over gRPC

The same server speaks the Remote Execution API on port 9092 — the port Bazel
itself would use. Point the plugin at it and repeat:

```bash
rm -rf app/build .gradle
../../gradlew :app:compileJava --build-cache -PbazelCache.url=grpc://127.0.0.1:9092
```

```
> Task :app:compileJava FROM-CACHE
```

A hit on the first try: the entry stored over HTTP above is the same entry over
gRPC. Only the transport differs — `ActionCache.GetActionResult` and
`ByteStream.Read` instead of `GET /ac/` and `GET /cas/`.

## Clean up

```bash
podman compose down -v
```

## Pointing at your own cache

```bash
../../gradlew :app:compileJava --build-cache -PbazelCache.url=https://cache.example.com
```

`grpc://` and `grpcs://` URLs select gRPC, as in Bazel's `--remote_cache`. If it
needs a token, set `tokenEnvironmentVariable` in `settings.gradle.kts` and
export that variable. The plugin stores the variable *name*, never the value, so
no secret reaches Gradle's configuration cache on disk.
