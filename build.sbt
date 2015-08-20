name := "WindowsPush"

version := "1.0"

scalaVersion := "2.11.6"

val finagleVersion = "6.26.0"

libraryDependencies ++= Seq(
  "com.twitter" %% "finagle-http" % finagleVersion
)