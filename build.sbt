ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / resolvers += "Sonatype snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"

publish / skip := true

val awsSdkVersion = "2.57.1"
val munit = "org.scalameta" %% "munit" % "1.3.6" % Test
val sttp = "com.softwaremill.sttp.client4" %% "core" % "4.0.13" % Test

lazy val root = project
  .in(file("."))
  .aggregate(app)
  .settings(name := "sharaf-aws-lambda-starter")

lazy val app = project
  .in(file("app"))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name := "sharaf-lambda",
    Compile / mainClass := Some("demo.runServer"),
    libraryDependencies ++= Seq(
      "ba.sake" %% "sharaf-potateh" % "0.24.0",
      ("software.amazon.awssdk" % "dynamodb" % awsSdkVersion)
        .exclude("software.amazon.awssdk", "apache-client")
        .exclude("software.amazon.awssdk", "apache5-client")
        .exclude("software.amazon.awssdk", "netty-nio-client"),
      "software.amazon.awssdk" % "url-connection-client" % awsSdkVersion,
      munit,
      sttp
    ),
    nativeImageCommand := Seq("native-image"),
    nativeImageOptions ++= Seq("--no-fallback", "-march=compatibility", "--enable-url-protocols=http,https")
  )

// Run explicitly: this suite builds a native image and requires Docker.
lazy val integrationTest = project
  .in(file("integration-test"))
  .settings(
    libraryDependencies ++= Seq(
      munit,
      sttp,
      "io.floci" % "testcontainers-floci-aws" % "2.17.0" % Test,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion % Test,
      "software.amazon.awssdk" % "lambda" % awsSdkVersion % Test,
      "software.amazon.awssdk" % "url-connection-client" % awsSdkVersion % Test
    ),
    Test / fork := true
  )
