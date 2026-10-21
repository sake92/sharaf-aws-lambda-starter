#!/usr/bin/env bash
# Integration test: HTTP client -> Floci Function URL -> Lambda Web Adapter -> Sharaf.
# Runs integration-test/ (scala-cli + Testcontainers): it runs scripts/build-image.sh, starts a pinned Floci,
# creates the container-image Lambda + Function URL there and sends real HTTP requests.
# Testcontainers removes the Floci/Lambda containers and network, also on failure.
set -euo pipefail
cd "$(dirname "$0")/.."
exec scala-cli test integration-test "$@"
