# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12_8-jdk-jammy AS build
ADD --checksum=sha256:eb41442f870bcae75aa5d6622ac96a173dae14fed59f71c97125eb21536bb060 \
    https://github.com/VirtusLab/scala-cli/releases/download/v1.18.0/scala-cli-x86_64-pc-linux.gz /tmp/scala-cli.gz
RUN gunzip -c /tmp/scala-cli.gz > /usr/local/bin/scala-cli && chmod +x /usr/local/bin/scala-cli
WORKDIR /build
COPY app/ app/
RUN scala-cli --power package --server=false --assembly --main-class demo.Main app -o app.jar

FROM eclipse-temurin:21.0.12_8-jre-jammy
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
COPY --from=build /build/app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-jar", "/app/app.jar"]
