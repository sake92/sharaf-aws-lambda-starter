#!/usr/bin/env bash
# Integration test: HTTP client -> Floci Function URL -> Lambda Web Adapter -> Sharaf.
# Uses the same image, push script and Terraform configuration as the real AWS deployment,
# pointed at a pinned Floci container. Never talks to real AWS (fixed local endpoint + dummy credentials).
set -euo pipefail
cd "$(dirname "$0")/.."

FLOCI_VERSION="${FLOCI_VERSION:-2.3.0}"
REGISTRY_IMAGE="registry:2.8.3"
PORT="${FLOCI_PORT:-4566}"
REGION="us-east-1"
PREFIX="sharaf-it"
NET="sharaf-floci-it"
FLOCI="sharaf-floci-it-floci"
REGISTRY="floci-aws-sharaf-it-registry" # Floci adopts a registry container of this exact name
IMAGE="sharaf-lambda:floci-it"
ENDPOINT="http://localhost:$PORT"
for tool in docker terraform aws jq curl; do
  command -v "$tool" >/dev/null || { echo "missing prerequisite: $tool" >&2; exit 1; }
done
TMP="$(mktemp -d)"

# --- explicit, local-only AWS configuration -------------------------------------------------
unset AWS_PROFILE AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_WEB_IDENTITY_TOKEN_FILE AWS_ROLE_ARN
export AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION" AWS_ENDPOINT_URL="$ENDPOINT"
export AWS_PAGER=""
export TF_DATA_DIR="$TMP/tfdata" TF_IN_AUTOMATION=1
case "$ENDPOINT" in http://localhost:*) ;; *) echo "refusing non-local endpoint $ENDPOINT" >&2; exit 1 ;; esac

tf() { # keeps all state in the temp dir
  local cmd="$1"; shift
  case "$cmd" in
    init) terraform -chdir=terraform init -input=false "$@" ;;
    output) terraform -chdir=terraform output -state="$TMP/terraform.tfstate" "$@" ;;
    *) terraform -chdir=terraform "$cmd" -input=false -state="$TMP/terraform.tfstate" "$@" ;;
  esac
}
tf_vars=(-var "emulator_endpoint=$ENDPOINT" -var "region=$REGION" -var "name_prefix=$PREFIX")

cleanup() {
  status=$?
  set +e
  if [ "$status" -ne 0 ]; then
    echo "=== TEST FAILED (exit $status): diagnostics ===" >&2
    echo "--- Floci logs (last 100 lines) ---" >&2
    docker logs --tail 100 "$FLOCI" >&2
    for c in $(docker ps -aq --filter "network=$NET"); do
      name="$(docker inspect --format '{{.Name}}' "$c")"
      case "$name" in "/$FLOCI" | "/$REGISTRY") continue ;; esac
      echo "--- Lambda container $name logs ---" >&2
      docker logs --tail 100 "$c" >&2
    done
  fi
  echo "=== cleaning up ===" >&2
  [ -s "$TMP/terraform.tfstate" ] && timeout 120 terraform -chdir=terraform destroy -input=false -auto-approve \
    -state="$TMP/terraform.tfstate" "${tf_vars[@]}" >/dev/null 2>&1
  docker ps -aq --filter "network=$NET" | xargs -r docker rm -f -v >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  docker images --format '{{.Repository}}:{{.Tag}}' | grep -F ".localhost:$PORT/$PREFIX:" | xargs -r docker rmi >/dev/null 2>&1
  docker rmi "$IMAGE" >/dev/null 2>&1
  rm -rf "$TMP"
  exit "$status"
}
trap cleanup EXIT

# --- 1. start Floci (needs the Docker socket to run Lambda containers) ----------------------
docker network create "$NET" >/dev/null
# Floci would start its backing ECR registry itself, but then neither its container name resolves on the
# Docker network nor are its upload URLs relative, which breaks `docker push` from the host.
# Starting the registry up front with the same settings fixes both; Floci adopts it by name.
docker run -d --name "$REGISTRY" --network "$NET" -e REGISTRY_HTTP_RELATIVEURLS=true "$REGISTRY_IMAGE" >/dev/null
docker run -d --name "$FLOCI" --network "$NET" \
  -p "127.0.0.1:$PORT:4566" \
  -v "${DOCKER_SOCK:-/var/run/docker.sock}:/var/run/docker.sock" \
  -e FLOCI_SERVICES_LAMBDA_DOCKER_NETWORK="$NET" \
  -e FLOCI_SERVICES_ECR_REGISTRY_CONTAINER_NAME="$REGISTRY" \
  -e FLOCI_SERVICES_ECR_REGISTRY_IMAGE="$REGISTRY_IMAGE" \
  -e FLOCI_SERVICES_ECR_KEEP_RUNNING_ON_SHUTDOWN=false \
  "floci/floci:$FLOCI_VERSION" >/dev/null
for _ in $(seq 1 60); do
  curl -fs "$ENDPOINT/_floci/health" >/dev/null && break
  sleep 1
done
curl -fs "$ENDPOINT/_floci/health" >/dev/null

# --- 2. build the application image ---------------------------------------------------------
scripts/build-image.sh "$IMAGE"

# --- 3. same two-step sequence as the AWS deployment: ECR first, then image, then Lambda ----
tf init >/dev/null
tf apply -auto-approve "${tf_vars[@]}" >/dev/null
repo_url="$(tf output -raw ecr_repository_url)"
digest="$(DOCKER_CONFIG="$TMP/docker" scripts/push-image.sh "$repo_url" "$IMAGE" it-1)"
echo "pushed $repo_url@$digest"
tf apply -auto-approve "${tf_vars[@]}" -var "image_digest=$digest" >/dev/null
function_url="$(tf output -raw function_url)"
echo "Function URL: $function_url"

# --- 4. real HTTP requests through the Function URL -----------------------------------------
failures=0
check() { # description expected actual
  if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected '$2', got '$3'" >&2; failures=$((failures + 1)); fi
}
call() { # method path [body]; sets $status, $body, $content_type
  local out="$TMP/response"
  # *.localhost is not resolvable everywhere, so connect to the local port and keep the Function URL Host header
  status="$(curl -s -m 90 --connect-to "::127.0.0.1:$PORT" -o "$out" -D "$TMP/headers" -w '%{http_code}' \
    -X "$1" "${function_url%/}$2" ${3:+-H 'Content-Type: application/json' -d "$3"})"
  body="$(cat "$out")"
  content_type="$(grep -i '^content-type:' "$TMP/headers" | tr -d '\r' | cut -d' ' -f2-)"
}

call GET /health
check "GET /health status" 200 "$status"
check "GET /health body" ok "$(jq -r .status <<<"$body")"
check "GET /health content-type" "application/json; charset=utf-8" "$content_type"

call POST /hello '{"name":"Ana"}'
check "POST /hello status" 200 "$status"
check "POST /hello greeting" "Hello, Ana!" "$(jq -r .greeting <<<"$body")"

call POST '/hello?greeting=Hi' '{"name":"Ana"}'
check "POST /hello?greeting=Hi query parameter reaches Sharaf" "Hi, Ana!" "$(jq -r .greeting <<<"$body")"

call POST /hello '{"nope":1}'
check "POST /hello invalid body status" 400 "$status"

call GET /does-not-exist
check "unknown route status" 404 "$status"

if [ "$failures" -ne 0 ]; then
  echo "$failures assertion(s) failed" >&2
  exit 1
fi
echo "All Floci integration assertions passed"
