# How Gradle build cache entries are stored in a Bazel cache

This document is the reference for the on-the-wire format. It matters more than
most design notes, because the mapping below **defines the cache keyspace**:
change it and every entry ever written by this plugin becomes unreachable.

## The problem

Gradle's build cache is a key/value store. A Bazel remote cache is not.

Bazel splits its cache in two:

- the **CAS** (Content Addressable Storage), where a blob's address *is* the
  SHA-256 of its content;
- the **Action Cache**, mapping an action's digest to an `ActionResult` proto
  that *points at* CAS blobs.

Two consequences follow, and together they rule out the obvious approaches:

1. **A Gradle key cannot be a CAS address.** CAS addresses are content hashes,
   and servers verify them. At `load()` time we hold the cache key but not the
   content, so the address cannot be recomputed.
2. **Key widths differ.** Gradle emits a 128-bit key — 32 lowercase hex
   characters, e.g. `3748f79fa4230cfba17f559fee3220fb`. `bazel-remote` matches
   both `/ac/` and `/cas/` paths against a strict 64-character lowercase-hex
   pattern and answers **400** to anything else.

Point 2 is why Gradle's built-in `HttpBuildCache` cannot talk to a Bazel cache
at all, even though both are HTTP blob stores: it `PUT`s to `<url>/<32-hex-key>`,
which never matches.

## The mapping

### Store

```
1.  digest = SHA-256(entry bytes)
2.  PUT /cas/<digest>                 the entry itself
3.  PUT /cas/<commandDigest>          the synthetic Command   (once per build)
4.  PUT /cas/<actionDigest>           the synthetic Action    (once per entry)
5.  PUT /ac/<actionDigest>            an ActionResult naming the entry blob
```

**Order is not optional.** The CAS blob must exist before the AC entry that
references it. No server checks dependencies on *write*, so a reversed order
publishes a pointer to a blob that is not there, which reads as a permanent miss.

### Load

```
1.  actionDigest = derived locally from the Gradle key   (no network)
2.  GET /ac/<actionDigest>            404/204 -> clean miss
3.  GET /cas/<digest it names>        404     -> clean miss (blob was evicted)
```

Step 1 is a pure function, so a cache miss costs exactly one request.

## Deriving the Action Cache key

Rather than hashing the Gradle key into an opaque digest, we synthesise a
**real** `Action`. REAPI states that a client must upload the `Action` and its
`Command` into the CAS before writing an `ActionResult`, and a genuine Action
means an operator can fetch it and see what produced an entry.

```protobuf
Command {
  arguments   = ["gradle-build-cache", "<keyVersion>"]
  output_paths = ["entry"]
}

Action {
  command_digest    = SHA-256(serialized Command)
  input_root_digest = { hash: <empty SHA-256>, size_bytes: 0 }
  salt              = <the Gradle cache key, as raw UTF-8>
}

actionDigest = SHA-256(serialized Action)
```

`ActionResult` carries exactly one output file:

```protobuf
ActionResult {
  output_files = [ { path: "entry", digest: <entry blob digest> } ]
}
```

Three details that are easy to get wrong:

- **`Action.salt` is field 9**, not 7. Fields 3–5 and 8 are reserved upstream for
  fields that moved to `Command`.
- **`exit_code` is omitted.** It is 0, a proto3 default, so it never appears on
  the wire — which is also what a real successful action produces.
- **The Gradle key goes in as UTF-8 text, not hex-decoded bytes.**
  `BuildCacheKey.getHashCode()` is typed as a `String` and Gradle has changed its
  hash function before; treating it as opaque text keeps the mapping correct
  whatever shape the key takes.

`Command` is constant for a given `keyVersion`, so it is uploaded at most once
per build. Only the ~80-byte `Action` is per-entry.

### Why not simply pad the Gradle key to 64 characters?

Because a padded Gradle key would land in the same namespace as real Bazel
action digests. In a cache shared with Bazel builds, a Gradle entry could then be
served where an action result was expected. The namespace prefix in
`Command.arguments` makes collision impossible by construction.

## Compatibility notes

**Servers rewrite AC entries.** `bazel-remote` appends `execution_metadata`
(field 9) containing its worker address, so what comes back is never
byte-identical to what was sent. Parsing must tolerate unknown and added fields.

**A dangling entry is a miss, not an error.** The AC entry and the CAS blob have
independent lifetimes, so an LRU eviction of the blob leaves a pointer to
nothing. Servers that run a dependency check turn this into a clean 404; dumb
object stores do not, and return the AC entry followed by a 404 for the blob.
Both are treated as a miss.

**CAS uploads must send `Content-Length`.** A chunked upload is rejected with
400 by `bazel-remote`; Bazel's own HTTP cache client documents the same
limitation. The size is always known, since it is part of the digest.

**Cache hit rate is governed by CAS eviction, not AC eviction**, because an entry
is only usable while its blob survives.

## Stability

`actionDigest` is this plugin's ABI. It is frozen by golden vectors in
`CacheKeyMapperTest`, which assert the exact serialized bytes and resulting
digests for fixed inputs. A failure there means the change under test would:

- make every existing entry on every server unreachable, and
- split users across two incompatible keyspaces.

The correct response is to revert, or to bump `keyVersion` and add a new vector
beside the old one. `keyVersion` is the supported escape hatch, and bumping it is
a full cache flush.

Field numbers in the vendored proto subset are verified against upstream by
`tools/verify-proto-field-numbers.sh`.
