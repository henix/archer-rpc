name := "archer"

organization in Global := "henix"

version in Global := "0.1"

licenses in Global := Seq("3-clause BSD" -> url("http://opensource.org/licenses/BSD-3-Clause"))

scalaVersion in Global := "2.11.4"

scalacOptions in Global ++= Seq("-deprecation", "-feature", "-Yno-adapted-args")

lazy val macros = project.in(file("macros"))

lazy val root = project.in(file(".")).dependsOn(macros).aggregate(macros)

libraryDependencies ++= Seq(
  "io.netty" % "netty-all" % "4.0.24.Final",
  "org.slf4j" % "slf4j-api" % "1.7.8"
)
