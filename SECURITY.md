# Security

## Reporting

Please report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/pplr/gradle-bazel-cache/security/advisories/new)
rather than a public issue.

## Design notes relevant to security

**Credentials are referenced by environment variable name, never by value.**
The plugin's configuration object stores the *name* of the variable holding your
token; the value is resolved later, in the build cache service factory. This is
deliberate: Gradle serializes the build cache configuration into the
configuration cache entry under `.gradle/configuration-cache`, a directory CI
systems routinely cache and upload. Storing the value would publish the token.
A functional test asserts the token does not appear in the entry.

If you set a token literally via the `token` property, it **will** be written to
that entry. The environment variable form is strongly preferred.

**Describer output is sanitised.** Values passed to Gradle's
`BuildCacheServiceFactory.Describer` are logged and published into build scans.
URL userinfo and query strings are redacted before the endpoint is described,
since both are common carriers for credentials.

**Downloaded blobs are verified.** Content is checked against its SHA-256 before
being handed to Gradle. A cache serving wrong or truncated content yields a
cache miss rather than corrupt build outputs.

**Entries are namespaced.** Cache keys are derived through a synthetic `Command`
carrying a fixed namespace argument, so a Gradle entry cannot collide with a real
Bazel action digest in a shared cache.

## Trust model

A remote build cache is a trusted input to your build: anything able to write to
it can influence build outputs. This plugin verifies that content matches the
digest it was requested under, which protects against corruption and substitution
of a *different* blob — it does not protect against an attacker who can write
arbitrary entries to a cache your builds read. Restrict write access
accordingly, typically by giving developers read-only credentials and reserving
push for CI.
