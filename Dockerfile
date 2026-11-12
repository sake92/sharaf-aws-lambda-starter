# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine-3.24 AS build
ADD --checksum=sha256:d173ae4bd6eb07fd70b000a12cb7275c7aab26c657e3c9932f497d9578d71eb1 \
    https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/2.0.12/sbt-launch-2.0.12.jar /opt/sbt-launch.jar
RUN apk add --no-cache clang lld build-base openssl-dev
WORKDIR /build
COPY build.sbt ./
COPY project/ project/
COPY app/ app/
RUN java -jar /opt/sbt-launch.jar "app/nativeLink"

FROM alpine:3.24
RUN apk add --no-cache libcrypto3
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
