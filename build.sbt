val scala213 = "2.13.15"
val scala3   = "3.5.2"

val commonSettings = Seq(
  Compile / compile / wartremoverErrors ++= Warts.allBut(
    Wart.Any,
    Wart.Nothing,
    Wart.ImplicitParameter,
    Wart.Throw,
    Wart.DefaultArguments,
    Wart.Recursion,
    Wart.ImplicitConversion,
    Wart.Overloading,
    Wart.OptionPartial
  ),
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) =>
        Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
      case _ => Nil
    }
  },
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Xsource:3", "-Xlint:unused")
      case _            => Seq("-source:future", "-Wunused:imports")
    }
  },
  Compile / scalacOptions ++= Seq("-Xfatal-warnings", "-deprecation"),
  Test / parallelExecution := false
)

inThisBuild(
  List(
    organization := "io.github.jchapuis",
    licenses     := List("Apache License, Version 2.0" -> url("https://opensource.org/license/apache-2-0/")),
    homepage     := Some(url("https://github.com/jchapuis/leases4s")),
    developers := List(
      Developer(
        "jchapuis",
        "Jonas Chapuis",
        "me@jonaschapuis.com",
        url("https://jonaschapuis.com")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeProjectHosting := Some(
      xerial.sbt.Sonatype.GitHubHosting("jchapuis", "leases4s", "me@jonaschapuis.com")
    ),
    versionPolicyIntention := Compatibility.BinaryCompatible,
    versionScheme          := Some("early-semver"),
    versionPolicyIgnoredInternalDependencyVersions := Some(
      "^\\d+\\.\\d+\\.\\d+\\+\\d+".r
    ) // Support for versions generated by sbt-dynver
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val core = (project in file("core"))
  .settings(scalaVersion := scala213, crossScalaVersions := Seq(scala213, scala3))
  .settings(commonSettings *)
  .settings(name := "leases4s-core")
  .settings(
    libraryDependencies ++= Seq(
      "com.goyeau"    %% "kubernetes-client"   % "0.11.0",
      "org.typelevel" %% "log4cats-core"       % "2.7.0",
      "org.typelevel" %% "literally"           % "1.2.0",
      "co.fs2"        %% "fs2-core"            % "3.11.0",
      "org.scalameta" %% "munit"               % "1.0.2" % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0" % Test,
      "org.typelevel" %% "cats-effect-testkit" % "3.5.5" % Test,
      "org.typelevel" %% "log4cats-slf4j"      % "2.7.0" % Test,
      "ch.qos.logback" % "logback-classic"     % "1.5.9" % Test
    )
  )

lazy val patterns = (project in file("patterns"))
  .settings(scalaVersion := scala213, crossScalaVersions := Seq(scala213, scala3))
  .settings(commonSettings *)
  .settings(name := "leases4s-patterns")
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"               % "1.0.2" % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0" % Test,
      "org.typelevel" %% "cats-effect-testkit" % "3.5.5" % Test
    )
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val example = (project in file("example"))
  .settings(scalaVersion := scala213, crossScalaVersions := Nil)
  .settings(commonSettings *)
  .settings(name := "leases4s-example")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s"             %% "http4s-dsl"          % "0.23.28",
      "org.http4s"             %% "http4s-circe"        % "0.23.28",
      "org.http4s"             %% "http4s-ember-server" % "0.23.28",
      "io.circe"               %% "circe-generic"       % "0.14.10",
      "com.lihaoyi"            %% "scalatags"           % "0.13.1",
      "org.scala-lang.modules" %% "scala-xml"           % "2.3.0",
      "org.typelevel"          %% "log4cats-slf4j"      % "2.7.0",
      "org.typelevel"          %% "munit-cats-effect"   % "2.0.0"   % Test,
      "org.http4s"             %% "http4s-ember-client" % "0.23.28" % Test
    ),
    libraryDependencies ++= Seq(
      "org.jsoup"              % "jsoup"           % "1.18.1",
      "software.amazon.awssdk" % "s3"              % "2.29.9",
      "ch.qos.logback"         % "logback-classic" % "1.5.9"
    )
  )
  .settings(run / fork := true, publish / skip := true)
  .settings(
    jibBaseImage               := "eclipse-temurin:21",
    jibUser                    := Some("65534:65534"),
    jibRegistry                := "localhost",
    jibAllowInsecureRegistries := true,
    jibPlatforms := Set(if (System.getProperty("os.arch") == "aarch64") JibPlatforms.arm64 else JibPlatforms.amd64),
    jibVersion   := "local"
  )

lazy val root =
  project.aggregate(core, patterns, example).settings(crossScalaVersions := Nil).settings(publish / skip := true)
