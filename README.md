# gradle-bazel-cache

[![CI](https://github.com/pplr/gradle-bazel-cache/actions/workflows/ci.yml/badge.svg)](https://github.com/pplr/gradle-bazel-cache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Use a **standard Bazel remote cache server** as the backend for Gradle's build cache,
so one cache serves both your Bazel and your Gradle builds.

> **Status: early development.** Not yet published. See [Roadmap](#roadmap).

## Why this exists

Gradle's built-in `HttpBuildCache` **cannot talk to a Bazel remote cache**, even though
both are content-addressed HTTP blob stores. Gradle `PUT`s to `<url>/<key>` where the key
is a **32-character** hash; `bazel-remote` routes on
`^/?(.*/)?(ac/|cas/)([a-f0-9]{64})$` — **64 lowercase hex, SHA-256 only**. The request is
rejected before it reaches storage.

There is a deeper mismatch behind the key width. Bazel's cache is not a key-value store:
the **Action Cache** holds `ActionResult` protos that *point at* blobs in the
**Content Addressable Store**, and a CAS key must be the SHA-256 of the blob's own
content. At load time Gradle hands you a cache key but not the content, so the CAS
address cannot be recomputed. Bridging the two requires an AC indirection.

This plugin implements that bridge against the **unmodified** REAPI protocol — no server
flags, no proprietary extensions.

## Prior art, and the gap

The approach was presented in *"Making a faster Gradle build cache with Bazel's Remote
APIs"* (Zach Gray, BazelCon). In that talk Bitrise describes building exactly this, then
says they *"considered open sourcing this project but … held off"*, and instead built a
proprietary gRPC protocol for their own servers. Their plugin is a closed-source jar that
cannot talk to `bazel-remote`. EngFlow's equivalent is likewise closed and vendor-locked.

No open-source Gradle build-cache backend speaks REAPI. This project is that missing piece.

For a working reference in another ecosystem, see Mill's `BazelRemoteCache.scala`.

## Try it in two minutes

```bash
cd examples/quickstart
podman compose up -d                              # or docker compose up -d
../../gradlew :app:compileJava --build-cache      # cold: stores
rm -rf app/build .gradle
../../gradlew :app:compileJava --build-cache      # warm: FROM-CACHE
```

See [examples/quickstart](examples/quickstart/README.md).

## Usage

```kotlin
// settings.gradle.kts — pluginManagement must be the first block
pluginManagement { repositories { gradlePluginPortal() } }

plugins { id("io.github.pplr.bazel-cache") version "<unreleased>" }

buildCache {
    remote(BazelRemoteBuildCache::class) {
        endpoint = "https://cache.internal:8080"
        isPush = System.getenv("CI") != null   // Gradle defaults remote push to false
    }
}
```

Credentials are referenced **by environment variable name**, never by value, so no secret
is written into Gradle's configuration cache entry on disk:

```kotlin
tokenEnvironmentVariable = "BAZEL_CACHE_TOKEN"   // default
```

## Server support

| Server | 1.0 (HTTP) | 1.1 (gRPC) |
|---|---|---|
| `bazel-remote` | ✅ verified against v2.6.2 | planned |
| nginx + WebDAV, S3 / GCS / MinIO, Artifactory | expected, untested | — |
| Buildbarn, BuildBuddy, NativeLink, EngFlow | ❌ gRPC-only | planned |

Verified means the integration suite runs against a real server with **AC
validation enabled** — its default. No server flags are required.
Details in [docs/SERVER-MATRIX.md](docs/SERVER-MATRIX.md).

## Roadmap

- **1.0** — HTTP `/ac/` `/cas/`, resilience layer, quickstart, Plugin Portal + Maven Central
- **1.1** — gRPC REAPI (`grpc-okhttp`), `FindMissingBlobs`, Buildbarn / BuildBuddy interop

## Building

```bash
./gradlew build
```

Requires a JDK 17 or newer. No container needed: the tests that need a real
cache server are tagged out of `build`. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the other test layers.

## Compatibility

| | |
|---|---|
| Gradle | 8.0 and newer — each version in the matrix is exercised in CI |
| Java | 17 and newer |
| Configuration cache | supported |

## Documentation

- [How entries are stored](docs/PROTOCOL.md) — the Action Cache indirection, the
  key derivation, and why it is frozen
- [Server compatibility](docs/SERVER-MATRIX.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

## Licence

MIT — see [LICENSE](LICENSE). Vendored `.proto` files are Apache-2.0; see [NOTICE](NOTICE).
