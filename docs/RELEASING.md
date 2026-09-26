# Releasing

## Where the plugin is published

**The Gradle Plugin Portal is the only publication a release requires.** It is
independent of Sonatype, and it is what `plugins { id(...) version "..." }`
resolves from by default:

```kotlin
plugins { id("io.github.pplr.bazel-cache") version "1.0.0" }
```

Maven Central is an **optional mirror**, for organisations that proxy Central but
not the Plugin Portal. The release workflow skips it entirely unless its secrets
are configured, so neither a Sonatype account nor a signing key in GitHub
secrets is needed to ship.

### Why not GitHub Packages

GitHub Packages' Maven registry **requires authentication for reads, even from a
public repository** — an anonymous request returns `401`, not `404`. Every
consumer would need a personal access token with `read:packages` in their
`pluginManagement` block just to apply the plugin. That is not a plausible ask,
so it is not used.

## Secrets

Minimum, for a Portal-only release:

| Secret | Where from |
|---|---|
| `GRADLE_PUBLISH_KEY` | plugins.gradle.org → your profile → API Keys |
| `GRADLE_PUBLISH_SECRET` | the same page |

Only if you also want the Central mirror:

| Secret | Notes |
|---|---|
| `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` | Sonatype Central Portal token |
| `SIGNING_KEY` | base64-armored PGP **private** key |
| `SIGNING_PASSWORD` | its passphrase |

Adding `SIGNING_KEY` means putting a private key into GitHub secrets. That is a
real cost, and the reason the Central mirror is opt-in rather than assumed.

Secrets live on the `release` GitHub environment, so a release needs an explicit
approval rather than firing on any tag push.

## Rehearse first

A published Plugin Portal version **cannot be withdrawn**, so the release path
is rehearsable. Run it from the Actions tab:

> Actions → Release → Run workflow → version `1.0.0`, **Dry run: checked**

A rehearsal runs every check and validates the publication against the Portal
(`publishPlugins --validate-only`), but publishes nothing, uploads nothing and
creates no GitHub release. Dry run defaults to **on**, so an accidental dispatch
cannot publish.

A tag push always publishes.

## Cutting a release

1. Make sure `main` is green. The release workflow re-runs everything, but a
   release should not be the first time the suite runs.
2. Tag and push:
   ```bash
   git tag -s v1.0.0 -m "1.0.0"
   git push origin v1.0.0
   ```
3. Approve the `release` environment when GitHub asks. The environment requires
   a reviewer, so nothing publishes until a human approves the run.

The workflow derives the version from the tag, so nothing in the repository
needs editing. It refuses to publish a `SNAPSHOT`.

`workflow_dispatch` takes an explicit version, for a re-run after a failed
publish.

## Approval gate

The `release` environment has required reviewers. A tag push starts the workflow
but it pauses before any publishing step until approved, which is also the last
chance to cancel a release triggered by an accidental tag.

## What the workflow checks before publishing

- the full unit and functional suite
- the protocol suite against a real `bazel-remote`
- the cross-version matrix
- that every artifact is signed (only when building the Central bundle)

## First publication is reviewed by hand

The Plugin Portal reviews the **first** version of a new plugin ID manually, and
it can take a few days. Subsequent versions publish immediately. Validate
metadata ahead of time without publishing:

```bash
./gradlew publishPlugins --validate-only
```

## Known gap

The Central upload step was written from Sonatype's documentation and has never
been exercised. Everything up to it — staging layout, POM validity, signature
coverage — is verified locally, but expect to iterate on the upload itself the
first time the mirror is enabled. The Portal publish path does not depend on it.
