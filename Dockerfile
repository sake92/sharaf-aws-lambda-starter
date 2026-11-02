# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-jammy AS build
ADD --checksum=sha256:d173ae4bd6eb07fd70b000a12cb7275c7aab26c657e3c9932f497d9578d71eb1 \
    https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/2.0.12/sbt-launch-2.0.12.jar /opt/sbt-launch.jar
RUN apt-get update && apt-get install -y --no-install-recommends \
    clang libgc-dev libunwind-dev zlib1g-dev libssl-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY build.sbt ./
COPY project/ project/
COPY app/ app/
RUN java -jar /opt/sbt-launch.jar "app/nativeLink"

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgc1 libunwind8 zlib1g libssl3 \
    && rm -rf /var/lib/apt/lists/*
# Lambda Web Adapter runs as a Lambda extension: it polls the Lambda Runtime API and
# forwards every invocation to the HTTP server below as an ordinary HTTP request.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.1.0 /lambda-adapter /opt/extensions/lambda-adapter
# The app listens on $PORT; the adapter forwards to AWS_LWA_PORT and waits for GET /health to return 2xx.
ENV PORT=8080 \
    AWS_LWA_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PORT=8080 \
    AWS_LWA_READINESS_CHECK_PROTOCOL=http \
    AWS_LWA_READINESS_CHECK_PATH=/health \
    AWS_LWA_READINESS_CHECK_HEALTHY_STATUS=200-299
COPY --from=build /build/app/target/native/sharaf-lambda /app/sharaf-lambda
ENTRYPOINT ["/app/sharaf-lambda"]
