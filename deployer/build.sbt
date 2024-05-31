val scala3 = "3.4.1"

ThisBuild / scalaVersion := scala3
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  .settings(name := "deployer")
  .settings(
    scalacOptions ++= Seq(
      "-deprecation",
      "-Wunused:imports",
      "-Xfatal-warnings"
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.virtuslab" %% "besom-core" % "0.3.1",
      "org.virtuslab" %% "besom-compiler-plugin" % "0.3.1",
      "org.virtuslab" %% "besom-aws" % "6.31.0-core.0.3",
      "org.virtuslab" %% "besom-kubernetes" % "4.11.0-core.0.3"
    )
  )
