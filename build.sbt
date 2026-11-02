import scala.scalanative.build.Mode

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / version := "0.1.0-SNAPSHOT"

publish / skip := true

val munit = "org.scalameta" %% "munit" % "1.2.1" % Test
val sttp = "com.softwaremill.sttp.client4" %% "core" % "4.0.13" % Test

lazy val root = project
  .in(file("."))
  .aggregate(app, appJvmTest)
  .settings(name := "sharaf-aws-lambda-starter")

lazy val app = project
  .in(file("app"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "sharaf-lambda",
    Compile / mainClass := Some("demo.Main"),
    Compile / crossTarget := baseDirectory.value / "target" / "native",
    libraryDependencies ++= Seq(
      "ba.sake" %% "sharaf-http4s" % "0.20.0",
      "org.http4s" %% "http4s-ember-server" % "1.0.0-M48"
    ),
    nativeConfig ~= { _.withMode(Mode.releaseFast).withMultithreading(true) }
  )

// The native application is tested through its real HTTP boundary by Floci. This small JVM
// project keeps the existing route tests fast and runnable without a native linker or Docker.
lazy val appJvmTest = project
  .in(file("app-jvm-test"))
  .settings(
    name := "sharaf-lambda-jvm-test",
    Compile / unmanagedSourceDirectories := Seq((app / Compile / scalaSource).value),
    Test / unmanagedSourceDirectories := Seq(baseDirectory.value / "src" / "test" / "scala"),
    libraryDependencies ++= Seq(
      "ba.sake" %% "sharaf-http4s" % "0.20.0",
      "org.http4s" %% "http4s-ember-server" % "1.0.0-M48",
      "ba.sake" %% "sharaf-undertow" % "0.20.0",
      munit,
      sttp
    )
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
