libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.0",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.0",
  "com.chuusai" %% "shapeless" % "2.0.0",
  "org.typelevel" %% "shapeless-scalaz" % "0.3",
  "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
)

initialCommands in console := "import scalaz._, Scalaz._"
