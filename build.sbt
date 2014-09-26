libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.0",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.0",
  "com.chuusai" %% "shapeless" % "2.0.0",
  "org.typelevel" %% "shapeless-scalaz" % "0.3"
)

initialCommands in console := "import scalaz._, Scalaz._"
