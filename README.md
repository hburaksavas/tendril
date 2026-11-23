# Tendril

Spring Boot tooling for IntelliJ IDEA that works without an Ultimate subscription, in Java and Kotlin.

| | |
|---|---|
| **Configuration** | Completion in `application*.yml` and `.properties`; unknown and missing-key inspections; navigation both ways between a key and the code that reads it (profiles and modules aware) |
| **Beans** | Gutter icons between injection points and bean definitions; missing, ambiguous and circular bean inspections |
| **Endpoints** | Incoming endpoints and outgoing Feign clients in one window, with the address each client resolves to per profile; focus mode; copy as cURL |
| **HTTP client** | `.http` files with a run arrow per request, or send straight from an endpoint or a Feign call; `{{variables}}`, `http-client.env.json` |
| **Running application** | Actuator window (health, beans, mappings, env, loggers, conditions); Recordings of incoming requests, Feign calls and Rabbit/Kafka input, with replay |

See [CHANGELOG.md](CHANGELOG.md) for what each release contains and [ROADMAP.md](ROADMAP.md) for what is planned.

## Requirements

IntelliJ IDEA 2025.3 or later (build 253+). The Maven integration of the recorder needs the bundled Maven plugin; everything else works without it.

## Installing

Until Tendril is on the JetBrains Marketplace, install it from the zip:

1. Download `tendril-<version>.zip` from the [latest release](https://github.com/hburaksavas/tendril/releases/latest) (do not unpack it).
2. In IntelliJ IDEA open **Settings | Plugins**, click the gear icon, choose **Install Plugin from Disk...** and select the zip.
3. Restart the IDE when asked.

To update, install the newer zip the same way; it replaces the old version.

## Building

```
./gradlew buildPlugin     # build/distributions/tendril-<version>.zip
./gradlew test            # unit, light-fixture and recorder tests
./gradlew runIde          # a sandbox IDE with the plugin
```

`recorder/` is a separate module: the agent jar that records a running application. It is built and bundled into the plugin
by `buildPlugin`; its tests run with `./gradlew :recorder:test`.

To try everything, open the sample project from the `tendril-playground` repository (a Java and a Kotlin Spring Boot app
that contain one example of each feature, including deliberately wrong code for the inspections).

## Releasing

The version lives in `gradle.properties` (`pluginVersion`). To release:

1. Move the finished entries of `CHANGELOG.md` from *Unreleased* to a new `## [x.y.z]` section.
2. Set `pluginVersion` to `x.y.z`, commit.
3. Tag and push: `git tag vx.y.z && git push --tags`.

The `Release` workflow builds and tests the tag, attaches the plugin zip to a GitHub release, and, when the
repository has the secrets below, signs the plugin and publishes it to the JetBrains Marketplace. A version with a suffix
(`0.2.0-beta.1`) goes to the matching Marketplace channel and is marked as a pre-release on GitHub.

| Secret | Purpose |
|---|---|
| `PUBLISH_TOKEN` | JetBrains Marketplace token |
| `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | plugin signing ([how to create them](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)) |

Without them the workflow still produces the zip and the GitHub release.

## Continuous integration

`Build` runs the tests and builds the plugin on every push and pull request. `Verify` (manual, and weekly) runs the
IntelliJ Plugin Verifier against the recommended IDE versions, which catches use of APIs that changed.
