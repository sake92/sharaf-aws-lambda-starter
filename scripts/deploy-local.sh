#!/usr/bin/env bash
# Builds and deploys the app to a running Floci instance with initialized local OpenTofu state.
set -euo pipefail
cd "$(dirname "$0")/.."
export TF_WORKSPACE=floci

repo=$(tofu -chdir=terraform output -raw ecr_repository_url)
if [[ "$repo" != localhost:4566/* ]]; then
  printf 'Expected a Floci ECR repository at localhost:4566, got %s\n' "$repo" >&2
  exit 1
fi

scripts/build-image.sh
digest=$(AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_SESSION_TOKEN= \
  AWS_DEFAULT_REGION=us-east-1 AWS_REGION=us-east-1 \
  AWS_ENDPOINT_URL_ECR=http://127.0.0.1:4566 scripts/push-image.sh "$repo")
tofu -chdir=terraform apply -var "image_digest=$digest"

url=$(tofu -chdir=terraform output -raw function_url)
printf 'Function URL: %s\n' "$url"
curl -fsS --max-time 60 "${url}health"
printf '\n'
