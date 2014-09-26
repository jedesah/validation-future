import sbt._
import sbt.Keys._

object PlanexecutorBuild extends Build {

  lazy val planexecutor = Project(
    id = "plan-executor",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      name := "plan-executor",
      organization := "com.whitepages",
      version := "0.1-SNAPSHOT",
      scalaVersion := "2.11.2"
      // add other settings here
    )
  )
}
