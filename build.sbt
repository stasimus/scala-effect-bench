ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "bench"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .aggregate(bench, ioBench)
    .settings(name := "scala-effect-bench", publish / skip := true)

lazy val bench = (project in file("bench"))
    .enablePlugins(JmhPlugin)
    .settings(
        name := "bench",
        libraryDependencies ++= Seq(
            "io.getkyo"     %% "kyo-core"    % "1.0.0-RC6",
            "org.typelevel" %% "cats-effect" % "3.7.1",
            "org.typelevel" %% "cats-core"   % "2.13.0",
            "co.fs2"        %% "fs2-core"    % "3.13.0"
        ),
        scalacOptions ++= Seq("-deprecation", "-feature", "-Wconf:msg=unused:s")
    )

lazy val ioBench = (project in file("io-bench"))
    .dependsOn(bench)
    .enablePlugins(JmhPlugin)
    .settings(
        name := "io-bench",
        libraryDependencies ++= Seq(
            "ch.epfl.lamp" %% "gears" % "0.3.1",
            "com.softwaremill.ox" %% "core" % "1.0.6"
        ),
        scalacOptions ++= Seq("-deprecation", "-feature")
    )
