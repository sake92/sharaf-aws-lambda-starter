#!/usr/bin/env bash
# Builds the application image (Sharaf app + Lambda Web Adapter). Usage: build-image.sh [image-name]
set -euo pipefail
cd "$(dirname "$0")/.."

# public.ecr.aws rate-limits anonymous pulls (429 "Data limit exceeded"), which is flaky on shared CI runners.
# Pre-pull the adapter image with retries; the build then uses the local copy.
adapter="$(sed -n 's/^COPY --from=\(public\.ecr\.aws[^ ]*\) .*/\1/p' Dockerfile | head -n1)"
if [ -n "$adapter" ] && ! docker image inspect "$adapter" >/dev/null 2>&1; then
  for attempt in 1 2 3 4 5 6; do
    docker pull "$adapter" && break
    [ "$attempt" -lt 6 ] && { echo "pull of $adapter failed (attempt $attempt), retrying..." >&2; sleep $((attempt * 15)); }
  done
fi

# linux/amd64 matches `architectures = ["x86_64"]` in terraform/main.tf.
# --provenance=false keeps a plain image manifest (Lambda rejects OCI image indexes).
docker build --platform linux/amd64 --provenance=false -t "${1:-sharaf-lambda:local}" .
