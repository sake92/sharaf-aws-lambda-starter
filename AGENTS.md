# Agent Instructions

## Stack

- Use Scala 3.9.0 with sbt 2.0.12 and Scala Native 0.5.12.
- The production app and native route tests are in the `app/` http4s/Ember Scala Native module.
- Keep Floci image tests in `integration-test/`; they remain JVM-only because they use AWS SDK and Testcontainers.

## Commands

| Task | Command |
| --- | --- |
| Native route tests | `sbt app/test` |
| Native release link | `sbt app/nativeLink` |
| Floci integration | `sbt integrationTest/test` |
| Formatting check | `sbt 'app/scalafmtCheckAll; scalafmtCheckAll; scalafmtSbtCheck; integrationTest/scalafmtCheckAll'` |
| OpenTofu checks | `tofu -chdir=terraform fmt -check` and `tofu -chdir=terraform validate` |
| Build local image | `scripts/build-image.sh` |

Local native linking needs Clang plus the `libgc`, `libunwind`, zlib, and OpenSSL development packages. Docker installs these in the build stage.

## Conventions

- Preserve the Lambda Web Adapter, OpenTofu-managed Function URL, route paths, response statuses, and JSON content-type headers.
- Keep native startup and shutdown in `Main`; do not add a custom Lambda handler or SDK dependency.
- Integration tests build the same Docker image used for deployment and require Docker; they use dummy Floci credentials only.
- Do not claim AWS deployment or cold-start behavior from local checks; see `README.md` for verification boundaries.

## References

- Build and deployment workflow: `README.md`
- Sharaf native/http4s example: https://github.com/sake92/sharaf/blob/main/examples/scala-cli/hello-http4s-native.scala
