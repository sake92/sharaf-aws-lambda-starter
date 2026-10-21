#!/usr/bin/env bash
# Pushes the locally built image to ECR and prints its digest (sha256:...) as the last line of stdout.
# Usage: push-image.sh <ecr-repository-url> [local-image] [tag]
# AWS credentials / region / endpoint come from the standard AWS_* environment variables.
set -euo pipefail

repo_url="${1:?usage: push-image.sh <ecr-repository-url> [local-image] [tag]}"
local_image="${2:-sharaf-lambda:local}"
tag="${3:-build-$(date +%Y%m%d%H%M%S)}" # the ECR repository is IMMUTABLE: every push needs a new tag

aws ecr get-login-password | docker login --username AWS --password-stdin "${repo_url%%/*}" >&2
docker tag "$local_image" "$repo_url:$tag"
docker push "$repo_url:$tag" >&2
digest="$(docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$repo_url:$tag" | sed -n "s|^$repo_url@||p" | head -n1)"
echo "${digest:?could not determine image digest}"
