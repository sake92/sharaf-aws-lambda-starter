#!/usr/bin/env bash
# Builds the application image (Sharaf app + Lambda Web Adapter). Usage: build-image.sh [image-name]
set -euo pipefail
cd "$(dirname "$0")/.."
# linux/amd64 matches `architectures = ["x86_64"]` in terraform/main.tf.
# --provenance=false keeps a plain image manifest (Lambda rejects OCI image indexes).
docker build --platform linux/amd64 --provenance=false -t "${1:-sharaf-lambda:local}" .
