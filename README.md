# sharaf-aws-lambda-starter

A minimal [Sharaf](https://sake92.github.io/sharaf/) (Scala 3) HTTP app running on **AWS Lambda** through the
[AWS Lambda Web Adapter](https://github.com/aws/aws-lambda-web-adapter) (LWA), deployed with **Terraform**, with an
automated integration test on [Floci](https://github.com/floci-io/floci) (a local AWS emulator) via
[Testcontainers](https://testcontainers.com/modules/floci/).

There is no custom Lambda handler and no Lambda SDK: the app is an ordinary Undertow-based Sharaf server.

| Path | What |
|---|---|
| `app/` | Sharaf app (scala-cli): `GET /health`, `POST /hello` (+ munit tests) |
| `Dockerfile` | Builds the app and adds LWA as a Lambda extension |
| `terraform/` | ECR, IAM role, Lambda (container image), public Function URL |
| `scripts/build-image.sh`, `scripts/push-image.sh` | Docker build / ECR push (kept out of Terraform) |
| `integration-test/` | Floci + Testcontainers integration test (scala-cli, munit) |
| `scripts/floci-test.sh` | The one-command integration test (`scala-cli test integration-test`) |

## Prerequisites

Docker and [scala-cli](https://scala-cli.virtuslab.org/) (for the app and the Floci test); Terraform >= 1.6 and AWS CLI v2 for deploying.
Deploying needs an AWS account and credentials; the Floci test needs neither.

## Run locally (no AWS)

```bash
cd app && scala-cli run .                 # listens on 0.0.0.0:8080; override with PORT=9000
curl localhost:8080/health                # {"status":"ok"}
curl -X POST localhost:8080/hello -d '{"name":"Ana"}'                 # {"greeting":"Hello, Ana!"}
curl -X POST 'localhost:8080/hello?greeting=Hi' -d '{"name":"Ana"}'   # {"greeting":"Hi, Ana!"}
```

Unit tests and formatting: `cd app && scala-cli test . && scala-cli fmt --check .`
(Terraform: `terraform -chdir=terraform fmt -check`.)

The same image runs outside Lambda too (the adapter does nothing without the Lambda runtime API):
`scripts/build-image.sh && docker run --rm -p 8080:8080 sharaf-lambda:local`.

## Floci integration test

```bash
scripts/floci-test.sh
```

`integration-test/FlociLambdaSuite` uses the [Testcontainers Floci module](https://github.com/floci-io/testcontainers-floci)
(`io.floci:testcontainers-floci-aws`, with Floci pinned to `floci/floci:2.3.0`). It:

1. runs `scripts/build-image.sh` (the same image build as for AWS), tagging it with an AWS-shaped ECR URI;
2. starts Floci (Docker socket + dedicated network are handled by the module);
3. creates a container-image Lambda and a Function URL with the AWS SDK (Floci runs the locally built image as-is, so no
   registry push is needed);
4. sends real HTTP requests to the Function URL and asserts status codes and JSON for `/health` and `/hello`, that the
   `?greeting=` query parameter reaches Sharaf, that an invalid body yields `400` and an unknown route `404`.

Testcontainers removes the containers and network, also on failure; Floci logs are printed when an assertion fails.
The test only talks to the Testcontainers-managed Floci endpoint with its dummy credentials, never to real AWS.

Path under test: `HTTP client -> Floci Function URL -> Lambda Web Adapter (extension in the Lambda container) -> Sharaf`.

Terraform is deliberately **not** exercised against Floci (an emulator says little about real IAM/permission behaviour);
CI only runs `terraform fmt` and `validate`.

Floci notes: it starts `/opt/extensions/*` itself, so LWA works unchanged. It does not enforce IAM or the Function URL
permissions, and Function URLs are routed by Host header, so the test keeps the generated `*.lambda-url.<region>.localhost`
host name and swaps in the mapped container port.

## Deploy to AWS

> **The Function URL is public and unauthenticated** (`authorization_type = "NONE"`): anyone on the internet can call
> it and you pay for it. Demo only; destroy it when done. Since Oct 2025 a public URL needs both
> `lambda:InvokeFunctionUrl` (auth type NONE) and `lambda:InvokeFunction` (invoked via Function URL) permissions;
> both are in `terraform/main.tf`.

The Lambda needs an image that must already exist in ECR, hence two applies:

```bash
export AWS_REGION=eu-west-1 TF_VAR_region=eu-west-1    # TF_VAR_name_prefix=... to change the default "sharaf-demo"
terraform -chdir=terraform init
terraform -chdir=terraform apply                       # 1) ECR repository, IAM role, log group
scripts/build-image.sh
DIGEST=$(scripts/push-image.sh "$(terraform -chdir=terraform output -raw ecr_repository_url)")
terraform -chdir=terraform apply -var "image_digest=$DIGEST"   # 2) Lambda + Function URL
```

The function is deployed from the immutable digest (`repo@sha256:...`); the ECR repo is tag-immutable, so
`push-image.sh` uses a fresh timestamp tag each time.

Call it:

```bash
URL=$(terraform -chdir=terraform output -raw function_url)
curl "${URL}health"
curl -X POST "${URL}hello" -d '{"name":"Ana"}'
```

**Update the image:** change the code, then repeat `build-image.sh`, `push-image.sh`, and
`terraform apply -var "image_digest=$DIGEST"`.

**Remove everything** (Lambda, URL, role, log group, ECR repository *including its images*; the repo uses `force_delete`):

```bash
terraform -chdir=terraform destroy
```
Local images/tags can be removed with `docker rmi`.

## How the adapter works

LWA is a Lambda extension (`/opt/extensions/lambda-adapter`). It polls the Lambda Runtime API for invocations; for a
Function URL each one is a payload-format-2.0 HTTP event (method, path, query string, headers, base64 body). LWA waits
at start-up until `GET /health` (`AWS_LWA_READINESS_CHECK_PATH`) on `AWS_LWA_PORT` (8080) returns 2xx, converts every
event into a normal HTTP request to `127.0.0.1:8080`, and converts the HTTP response back into the Lambda response
(status, headers, body). Sharaf never knows it is on Lambda. The image's `ENTRYPOINT` is just `java -jar`.

## What is verified where

Verified by `scripts/floci-test.sh`: the image builds and starts as a Lambda container image; LWA starts as an
extension, passes its readiness check and proxies events; Function URL requests reach Sharaf with method, path, query,
body and JSON responses intact.

**Not** verified (needs a real AWS account): the Terraform configuration and that AWS accepts the IAM/permission setup
(the public URL really being callable without auth), ECR push/auth and image pull, real Lambda init timing/cold start
(JVM, 1024 MB, 30 s timeout are untuned), CloudWatch logging, and the exact real Function URL event.
