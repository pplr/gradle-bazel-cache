# Contributing

Thanks for taking an interest.

## Building

```bash
./gradlew build
```

Needs a JDK 17 or newer. No container and no network beyond dependency
resolution: the tests that need a real server are tagged out of `build`.

## Test layers

| Command | What it proves |
|---|---|
| `./gradlew build` | Unit and functional tests, including an in-process fake cache server. |
| `./gradlew :cache-client:integrationTest` | The protocol, against a **real** bazel-remote. Needs `BAZEL_REMOTE_HTTP_URL`. |
| `./gradlew :gradle-plugin:crossVersionTest` | The plugin loads and caches on every supported Gradle version. |

Running the integration suite:

```bash
cd examples/quickstart && podman compose up -d && cd -
BAZEL_REMOTE_HTTP_URL=http://127.0.0.1:9090/ ./gradlew :cache-client:integrationTest
```

**A fake that is kinder than the real server certifies bugs.** Our fake once
accepted chunked uploads that bazel-remote rejects, and hid a bug in which every
store against a real server failed — through 121 passing tests. If you find the
fake is more permissive than reality, tighten the fake in the same change.

## Things that will fail review

**Changing the cache keyspace.** `CacheKeyMapper.actionKeyFor` determines the
Action Cache address of every entry this plugin has ever written. `CacheKeyMapperTest`
freezes it with golden vectors. If one fails, the change makes every existing
entry on every server unreachable and splits users across two keyspaces. Revert,
or bump `keyVersion` and add a new vector beside the old one — never update an
expected value to make a test pass.

**Throwing from `load` or `store`.** Gradle performs no retries and disables the
remote cache for the whole build on the first failure it sees. A cache fault must
never fail or degrade a build beyond the entry it concerns.

**Adding a dependency.** The plugin shares one classloader with every other
settings plugin in a consumer's build. protobuf is shaded for exactly this
reason. New dependencies need a strong justification and relocation.

**Relocating `build.bazel.remote.*`.** Generated protobuf classes embed their
descriptor as a string literal in which package names are length-prefixed
varints. Renaming the package corrupts the descriptor pool at class-init time.
`ShadedJarContentTest` guards this.

## Before opening a PR

```bash
./gradlew build
./tools/verify-proto-field-numbers.sh    # if you touched the vendored protos
```

CI runs the full matrix, including a real server. Conventional commits are not
required; a message explaining *why* is.

Releases go to the Gradle Plugin Portal; see [docs/RELEASING.md](docs/RELEASING.md).
