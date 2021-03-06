organization := "fr.maif"
name := "example-play"

lazy val `example-play` = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(NoPublish)
  .disablePlugins(BintrayPlugin)

scalaVersion := "2.13.3"

val akkaVersion = "2.6.3"

PlayKeys.devSettings := Seq("play.server.http.port" -> "8080")

resolvers ++= Seq(
  Resolver.jcenterRepo,
  ("Artifactory Realm" at "http://oss.jfrog.org/artifactory/oss-snapshot-local").withAllowInsecureProtocol(true)
)

libraryDependencies ++= Seq(
  ws,
  "de.svenkubiak"            % "jBCrypt"         % "0.4.1", //  ISC/BSD
  "com.auth0"                % "java-jwt"        % "3.3.0", // MIT license
  "com.softwaremill.macwire" %% "macros"         % "2.3.3" % "provided", // Apache 2.0
  "com.typesafe.akka"        %% "akka-stream"    % akkaVersion, // Apache 2.0
  "org.typelevel"            %% "cats-core"      % "2.0.0", // MIT license
  "org.typelevel"            %% "cats-effect"    % "2.0.0", // MIT license
  "org.iq80.leveldb"         % "leveldb"         % "0.10", // Apache 2.0
  "fr.maif"                  %% "izanami-client" % version.value,
  "com.github.pureconfig"    %% "pureconfig"     % "0.12.1" // Apache 2.0
)
