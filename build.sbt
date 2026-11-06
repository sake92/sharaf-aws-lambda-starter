import scala.scalanative.build.Mode

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / resolvers += "Sonatype snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"

publish / skip := true

val munit = "org.scalameta" %% "munit" % "1.2.1" % Test
val nativeMunit = "org.scalameta" %% "munit" % "1.3.6" % Test
val sttp = "com.softwaremill.sttp.client4" %% "core" % "4.0.13" % Test

lazy val root = project
  .in(file("."))
  .aggregate(app)
  .settings(name := "sharaf-aws-lambda-starter")

lazy val app = project
  .in(file("app"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "sharaf-lambda",
    Compile / mainClass := Some("demo.Main"),
    Compile / crossTarget := baseDirectory.value / "target" / "native",
    libraryDependencies ++= Seq(
      "ba.sake" %% "sharaf-jdk-httpserver" % "0.20.1-SNAPSHOT",
      nativeMunit,
      sttp
    ),
    nativeConfig ~= { _.withMode(Mode.releaseFast).withMultithreading(true) }
  )

// Run explicitly: this suite builds a native image and requires Docker.
lazy val integrationTest = project
  .in(file("integration-test"))
  .settings(
    libraryDependencies ++= Seq(
      munit,
      sttp,
      "io.floci" % "testcontainers-floci-aws" % "2.17.0" % Test,
      "software.amazon.awssdk" % "lambda" % "2.57.1" % Test,
      "software.amazon.awssdk" % "url-connection-client" % "2.57.1" % Test
    ),
    Test / fork := true
  )
