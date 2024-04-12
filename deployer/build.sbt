val scala3 = "3.3.1"

ThisBuild / scalaVersion      := scala3
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
      "org.virtuslab" %% "besom-core"            % "0.2.2",
      "org.virtuslab" %% "besom-compiler-plugin" % "0.2.2",
      "org.virtuslab" %% "besom-aws"             % "6.23.0-core.0.2",
      "org.virtuslab" %% "besom-kubernetes"      % "4.8.0-core.0.2"
    )
  )
