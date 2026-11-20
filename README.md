# sharaf-aws-lambda-starter

A minimal [Sharaf](https://sake92.github.io/sharaf/) (Scala 3) HTTP app running on **AWS Lambda** through the
[AWS Lambda Web Adapter](https://github.com/aws/aws-lambda-web-adapter) (LWA), deployed with **OpenTofu**, with an
automated integration test on [Floci](https://github.com/floci-io/floci) (a local AWS emulator) via
[Testcontainers](https://testcontainers.com/modules/floci/).

The app uses Sharaf's experimental Potateh adapter (`0.24.0`), compiled with GraalVM Native Image.
The Docker build uses a statically linked musl executable and an Alpine runtime image.
The previous http4s/Ember version was manually deployed to AWS with OpenTofu; this version needs a new AWS verification.

## Deployment architecture

```mermaid
flowchart TB
    build["Local machine / CI<br/>Docker build + push scripts"]
    client[HTTP client]

    subgraph aws["AWS resources managed by OpenTofu"]
        ecr["ECR repository<br/>App + adapter image"]
        url["Public Lambda Function URL<br/>No authentication"]
        role["IAM execution role<br/>CloudWatch logging + DynamoDB GetItem + PutItem"]
        logs["CloudWatch Logs<br/>7-day retention"]
        table["DynamoDB<br/>Greeting templates"]

        subgraph fn["Lambda function - container image"]
            runtime[Lambda Runtime API]
            adapter[Lambda Web Adapter extension]
            app["Sharaf / Potateh<br/>GraalVM native executable"]
            runtime <-->|Invocation event / response| adapter
            adapter <-->|HTTP on 127.0.0.1:8080| app
        end

        url <-->|Invoke / return result| runtime
        ecr -.->|Deploy image by digest| fn
        role -.->|Assumed by Lambda| fn
        fn -->|Logs| logs
        fn -->|GetItem / PutItem| table
    end

    build -->|Push image| ecr
    client <-->|HTTPS| url
```

OpenTofu creates the ECR repository, execution role, log group and DynamoDB table first. The scripts build and push the
image. A second apply uses its digest to create the Lambda function and Function URL. ECR stores the deployment image;
requests go through the Function URL to the running Lambda environment.

## Prerequisites

JDK 21 or later and [sbt](https://www.scala-sbt.org/download/) (the build pins sbt 2.0.12) for JVM development;
GraalVM 25 with `native-image` and a C toolchain for local native builds; Docker for the image and Floci test;
[OpenTofu](https://opentofu.org/docs/intro/install/) >= 1.13.2 and AWS CLI v2 for deploying.
Deploying to AWS needs an AWS account and credentials. Local deployment uses Docker Compose and dummy credentials.

## Run and test locally

Start the app:

```bash
AWS_REGION=us-east-1 sbt app/run
```

In another terminal, [try the HTTP endpoints](#try-the-http-endpoints).

Run the route tests:

```bash
sbt app/testFull
```

Build the local executable with GraalVM on `PATH`:

```bash
sbt app/nativeImage
target/out/jvm/scala-3.9.0/sharaf-lambda/native-image/sharaf-lambda
```

Run the end-to-end integration test with Floci (requires Docker). The suite creates an empty table and
checks greeting creation, lookup and validation through the Function URL. This verifies the container and
HTTP integration locally. AWS deployment and cold-start behavior require checks on AWS.

The app uses the fixed DynamoDB table name `sharaf-demo-greetings`. For local DynamoDB requests, set
`AWS_ENDPOINT_URL_DYNAMODB` to your Floci endpoint and use dummy credentials. The Floci suite configures
these and creates the table automatically.

```bash
sbt integrationTest/testFull
```

## Provision resources locally with OpenTofu and Floci

From the repo root, use Docker Compose to start Floci 2.3.0 and wait for its health check.
Compose creates the network that Floci's registry and Lambda containers use.

```bash
docker compose up --wait
```

For the initial setup, apply the base resources in the `floci` workspace. It uses the same
`terraform/` configuration as AWS, with loopback endpoints and state under
`terraform/terraform.tfstate.d/floci/`.

```bash
tofu -chdir=terraform init -lockfile=readonly
TF_WORKSPACE=floci tofu -chdir=terraform plan
TF_WORKSPACE=floci tofu -chdir=terraform apply
```

To deploy the function, run the script below. It builds the image, pushes it to Floci's
registry using the AWS CLI with dummy credentials, and checks the Function URL after
you approve the OpenTofu plan. Rerun it after changing the app.

```bash
scripts/deploy-local.sh
```

[Try the HTTP endpoints](#try-the-http-endpoints) using the Floci Function URL.

When you're done, destroy the local resources before removing the emulator.

```bash
TF_WORKSPACE=floci tofu -chdir=terraform destroy
docker compose down
```

## Deploy to AWS

> **The Function URL is public and unauthenticated** (`authorization_type = "NONE"`): anyone on the internet can call
> it, create public demo greetings, and incur charges on the deploying account. Destroy the demo when you are done.
> Since October 2025, a public URL needs both `lambda:InvokeFunctionUrl` (auth type NONE) and `lambda:InvokeFunction`
> (invoked via Function URL). Both permissions are in `terraform/main.tf`.

The Lambda needs an image that must already exist in ECR, hence two applies:

```bash
export AWS_REGION=eu-west-1 TF_VAR_region=eu-west-1    # TF_VAR_name_prefix=... to change the default "sharaf-demo"
export TF_WORKSPACE=default

tofu -chdir=terraform init
tofu -chdir=terraform plan
tofu -chdir=terraform apply                       # 1) ECR repository, IAM role, log group, DynamoDB table

scripts/build-image.sh
DIGEST=$(scripts/push-image.sh "$(tofu -chdir=terraform output -raw ecr_repository_url)")
tofu -chdir=terraform plan -var "image_digest=$DIGEST"
tofu -chdir=terraform apply -var "image_digest=$DIGEST"   # 2) Lambda + Function URL
```

The function is deployed from the immutable digest (`repo@sha256:...`); the ECR repo is tag-immutable, so
`push-image.sh` uses a fresh timestamp tag each time.

[Try the HTTP endpoints](#try-the-http-endpoints) using the AWS Function URL.

The fixed table name allows one starter deployment per AWS account and Region. If you deployed an earlier version
with a custom prefix, review the plan before applying. OpenTofu will replace the differently named table.
The DynamoDB Standard table provisions 1 read and 1 write capacity unit. AWS includes 25 of each
and 25 GB of storage in the DynamoDB Free Tier, shared per Region and payer account. Lambda, ECR,
and CloudWatch Logs have their own pricing. [DynamoDB pricing](https://aws.amazon.com/dynamodb/pricing/)

**Update the image:** change the code, then repeat `build-image.sh`, `push-image.sh`, and
`tofu -chdir=terraform apply -var "image_digest=$DIGEST"`.

**Remove everything** (Lambda, URL, role, log group, DynamoDB table and its data, ECR repository *including its images*; the repo uses `force_delete`):

```bash
tofu -chdir=terraform destroy
```
Local images/tags can be removed with `docker rmi`.

## Try the HTTP endpoints

Set `URL` using the command for the app you started. Run the OpenTofu commands from the repo root.

| App | Command |
| --- | --- |
| Direct local server | `URL=http://localhost:8080` |
| Floci deployment | `URL=$(TF_WORKSPACE=floci tofu -chdir=terraform output -raw function_url)` |
| AWS deployment | `URL=$(TF_WORKSPACE=default tofu -chdir=terraform output -raw function_url)` |

```bash
curl "${URL%/}/health"
# {"status":"ok"}

curl -X POST "${URL%/}/hello" -d '{"name":"Ana"}'
# {"greeting":"Hello, Ana!"}

curl -X POST "${URL%/}/hello?greeting=Hi" -d '{"name":"Ana"}'
# {"greeting":"Hi, Ana!"}
```

The greetings endpoints need DynamoDB. Both deployments provision the table. For the direct local server,
provision the Floci table and set `AWS_ENDPOINT_URL_DYNAMODB` and dummy AWS credentials before starting the app.
Send non-blank text of up to 256 characters.

```bash
curl -i -X POST "${URL%/}/greetings" -H 'Content-Type: application/json' \
  -d '{"greeting":"Welcome from DynamoDB!"}'
# HTTP 201 Created with a generated id in the JSON body and a Location header

# Replace <id> with the generated id from the response.
curl "${URL%/}/greetings/<id>"
# {"greeting":"Welcome from DynamoDB!"}

curl -i "${URL%/}/greetings/missing"
# HTTP 404
```

## How the adapter works

The AWS Lambda Web Adapter translates Lambda invocation events into HTTP requests to the ordinary Sharaf server and converts its HTTP responses back into Lambda responses.
The adapter and the GraalVM native app are packaged together in the deployment Docker image.
