/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
import BuildSettings._
import Dependencies._
import Generators._
import com.lightbend.sbt.javaagent.JavaAgent.JavaAgentKeys.{javaAgents, resolvedJavaAgents}
import com.typesafe.tools.mima.plugin.MimaKeys.{mimaPreviousArtifacts, mimaReportBinaryIssues}
import interplay.PlayBuildBase.autoImport._
import interplay.ScalaVersions._
import pl.project13.scala.sbt.JmhPlugin.generateJmhSourcesAndResources
import sbt.Keys.parallelExecution
import sbt.ScriptedPlugin._
import sbt._

lazy val livongoPublishSettings = Seq(
  publishTo := Some("Artifactory Realm" at "http://52.7.159.52/artifactory/ext-release-local"),
  credentials += Credentials("Artifactory Realm", "52.7.159.52", "admin", "AP51HF7hmZL7Y9oLgMWbt9ABDBr")
)

lazy val BuildLinkProject = PlayNonCrossBuiltProject("Build-Link", "build-link")
    .dependsOn(PlayExceptionsProject)
  .settings(livongoPublishSettings: _*)

// run-support project is only compiled against sbt scala version
lazy val RunSupportProject = PlaySbtProject("Run-Support", "run-support")
    .settings(
      target := target.value / "run-support",
      libraryDependencies ++= runSupportDependencies((sbtVersion in pluginCrossBuild).value)
    ).dependsOn(BuildLinkProject)
  .settings(livongoPublishSettings: _*)

lazy val RoutesCompilerProject = PlayDevelopmentProject("Routes-Compiler", "routes-compiler")
    .enablePlugins(SbtTwirl)
    .settings(
      libraryDependencies ++= routesCompilerDependencies(scalaVersion.value),
      // TODO: Remove when updating to Scala 2.13.0-M4
      // Should be removed when we update to Scala 2.13.0-M4 since this is the
      // version added by interplay.
      //
      // See also:
      // 1. the root project at build.sbt file.
      // 2. RoutesCompilerProject project
      crossScalaVersions := Seq(scala211, scala212, "2.13.0-M3"),
      TwirlKeys.templateFormats := Map("twirl" -> "play.routes.compiler.ScalaFormat")
    )

lazy val SbtRoutesCompilerProject = PlaySbtProject("SBT-Routes-Compiler", "routes-compiler")
    .enablePlugins(SbtTwirl)
    .settings(
      target := target.value / "sbt-routes-compiler",
      libraryDependencies ++= routesCompilerDependencies(scalaVersion.value),
      TwirlKeys.templateFormats := Map("twirl" -> "play.routes.compiler.ScalaFormat")
    )
  .settings(livongoPublishSettings: _*)
  .settings(publishMavenStyle := true)

lazy val StreamsProject = PlayCrossBuiltProject("Play-Streams", "play-streams")
    .settings(libraryDependencies ++= streamsDependencies)
  .settings(livongoPublishSettings: _*)

lazy val PlayExceptionsProject = PlayNonCrossBuiltProject("Play-Exceptions", "play-exceptions")
  .settings(livongoPublishSettings: _*)

lazy val PlayJodaFormsProject = PlayCrossBuiltProject("Play-Joda-Forms", "play-joda-forms")
    .settings(
      libraryDependencies ++= joda
    )
    .dependsOn(PlayProject, PlaySpecs2Project % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayProject = PlayCrossBuiltProject("Play", "play")
    .enablePlugins(SbtTwirl)
    .settings(
      libraryDependencies ++= runtime(scalaVersion.value) ++ scalacheckDependencies ++ cookieEncodingDependencies :+
        jimfs % Test,

      sourceGenerators in Compile += Def.task(PlayVersion(
        version.value,
        scalaVersion.value,
        sbtVersion.value,
        jettyAlpnAgent.revision,
        (sourceManaged in Compile).value)).taskValue,

      sourceDirectories in(Compile, TwirlKeys.compileTemplates) := (unmanagedSourceDirectories in Compile).value,
      TwirlKeys.templateImports += "play.api.templates.PlayMagic._",
      mappings in(Compile, packageSrc) ++= {
        // Add both the templates, useful for end users to read, and the Scala sources that they get compiled to,
        // so omnidoc can compile and produce scaladocs for them.
        val twirlSources = (sources in(Compile, TwirlKeys.compileTemplates)).value pair
            relativeTo((sourceDirectories in(Compile, TwirlKeys.compileTemplates)).value)

        val twirlTarget = (target in(Compile, TwirlKeys.compileTemplates)).value
        // The pair with errorIfNone being false both creates the mappings, and filters non twirl outputs out of
        // managed sources
        val twirlCompiledSources = (managedSources in Compile).value.pair(relativeTo(twirlTarget), errorIfNone = false)

        twirlSources ++ twirlCompiledSources
      },
      Docs.apiDocsIncludeManaged := true
    ).settings(Docs.playdocSettings: _*)
    .dependsOn(
      BuildLinkProject,
      StreamsProject
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayServerProject = PlayCrossBuiltProject("Play-Server", "play-server")
    .settings(libraryDependencies ++= playServerDependencies)
    .dependsOn(
      PlayProject,
      PlayGuiceProject % "test"
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayNettyServerProject = PlayCrossBuiltProject("Play-Netty-Server", "play-netty-server")
    .settings(libraryDependencies ++= netty)
    .dependsOn(PlayServerProject)
  .settings(livongoPublishSettings: _*)

import AkkaDependency._
lazy val PlayAkkaHttpServerProject = PlayCrossBuiltProject("Play-Akka-Http-Server", "play-akka-http-server")
    .dependsOn(PlayServerProject, StreamsProject)
    .dependsOn(PlayGuiceProject % "test")
    .settings(
      libraryDependencies ++= specs2Deps.map(_ % "test")
    )
  .addAkkaModuleDependency("akka-http-core")
  .settings(livongoPublishSettings: _*)

lazy val PlayAkkaHttp2SupportProject = PlayCrossBuiltProject("Play-Akka-Http2-Support", "play-akka-http2-support")
    .dependsOn(PlayAkkaHttpServerProject)
    .addAkkaModuleDependency("akka-http2-support")
  .settings(livongoPublishSettings: _*)

lazy val PlayJdbcApiProject = PlayCrossBuiltProject("Play-JDBC-Api", "play-jdbc-api")
    .dependsOn(PlayProject)
  .settings(livongoPublishSettings: _*)

lazy val PlayJdbcProject: Project = PlayCrossBuiltProject("Play-JDBC", "play-jdbc")
    .settings(libraryDependencies ++= jdbcDeps)
    .dependsOn(PlayJdbcApiProject)
    .dependsOn(PlaySpecs2Project % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayJdbcEvolutionsProject = PlayCrossBuiltProject("Play-JDBC-Evolutions", "play-jdbc-evolutions")
    .settings(libraryDependencies += derbyDatabase % Test)
    .dependsOn(PlayJdbcApiProject)
    .dependsOn(PlaySpecs2Project % "test")
    .dependsOn(PlayJdbcProject % "test->test")
    .dependsOn(PlayJavaJdbcProject % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayJavaJdbcProject = PlayCrossBuiltProject("Play-Java-JDBC", "play-java-jdbc")
    .dependsOn(PlayJdbcProject % "compile->compile;test->test", PlayJavaProject)
    .dependsOn(PlaySpecs2Project % "test", PlayGuiceProject % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayJpaProject = PlayCrossBuiltProject("Play-Java-JPA", "play-java-jpa")
    .settings(libraryDependencies ++= jpaDeps)
    .dependsOn(PlayJavaJdbcProject % "compile->compile;test->test")
    .dependsOn(PlayJdbcEvolutionsProject % "test")
    .dependsOn(PlaySpecs2Project % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayTestProject = PlayCrossBuiltProject("Play-Test", "play-test")
    .settings(
      libraryDependencies ++= testDependencies ++ Seq(h2database % "test"),
      parallelExecution in Test := false
    ).dependsOn(
  PlayGuiceProject,
  PlayAkkaHttpServerProject
)
  .settings(livongoPublishSettings: _*)

lazy val PlaySpecs2Project = PlayCrossBuiltProject("Play-Specs2", "play-specs2")
    .settings(
      libraryDependencies ++= specs2Deps,
      parallelExecution in Test := false
    ).dependsOn(PlayTestProject)
  .settings(livongoPublishSettings: _*)

lazy val PlayJavaProject = PlayCrossBuiltProject("Play-Java", "play-java")
    .settings(libraryDependencies ++= javaDeps ++ javaTestDeps)
    .dependsOn(
      PlayProject % "compile;test->test",
      PlayTestProject % "test",
      PlaySpecs2Project % "test",
      PlayGuiceProject % "test"
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayJavaFormsProject = PlayCrossBuiltProject("Play-Java-Forms", "play-java-forms")
    .settings(
      libraryDependencies ++= javaDeps ++ javaFormsDeps ++ javaTestDeps,
      compileOrder in Test := CompileOrder.JavaThenScala // work around SI-9853 - can be removed when dropping Scala 2.11 support
    ).dependsOn(
      PlayJavaProject % "compile;test->test"
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayDocsProject = PlayCrossBuiltProject("Play-Docs", "play-docs")
    .settings(Docs.settings: _*)
    .settings(
      libraryDependencies ++= playDocsDependencies
    ).dependsOn(PlayAkkaHttpServerProject)
  .settings(livongoPublishSettings: _*)

lazy val PlayGuiceProject = PlayCrossBuiltProject("Play-Guice", "play-guice")
    .settings(libraryDependencies ++= guiceDeps ++ specs2Deps.map(_ % "test"))
    .dependsOn(
      PlayProject % "compile;test->test"
    )
  .settings(livongoPublishSettings: _*)

lazy val SbtPluginProject = PlaySbtPluginProject("SBT-Plugin", "sbt-plugin")
    .settings(
      libraryDependencies ++= sbtDependencies((sbtVersion in pluginCrossBuild).value, scalaVersion.value),
      sourceGenerators in Compile += Def.task(PlayVersion(
        version.value,
        (scalaVersion in PlayProject).value,
        sbtVersion.value,
        jettyAlpnAgent.revision,
        (sourceManaged in Compile).value)).taskValue,

      // This only publishes the sbt plugin projects on each scripted run.
      // The runtests script does a full publish before running tests.
      // When developing the sbt plugins, run a publishLocal in the root project first.
      scriptedDependencies := {
        val () = publishLocal.value
        val () = (publishLocal in RoutesCompilerProject).value
      }
    ).dependsOn(SbtRoutesCompilerProject, RunSupportProject)
  .settings(livongoPublishSettings: _*)
//  .settings(publishMavenStyle := true)

lazy val PlayLogback = PlayCrossBuiltProject("Play-Logback", "play-logback")
    .settings(
      libraryDependencies += logback,
      parallelExecution in Test := false,
      // quieten deprecation warnings in tests
      scalacOptions in Test := (scalacOptions in Test).value diff Seq("-deprecation")
    )
    .dependsOn(PlayProject)
    .dependsOn(PlaySpecs2Project % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayWsProject = PlayCrossBuiltProject("Play-WS", "play-ws")
    .settings(
      libraryDependencies ++= playWsDeps,
      parallelExecution in Test := false,
      // quieten deprecation warnings in tests
      scalacOptions in Test := (scalacOptions in Test).value diff Seq("-deprecation")
  ).dependsOn(PlayProject)
  .dependsOn(PlayTestProject % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayAhcWsProject = PlayCrossBuiltProject("Play-AHC-WS", "play-ahc-ws")
  .settings(
    libraryDependencies ++= playAhcWsDeps,
    parallelExecution in Test := false,
    // quieten deprecation warnings in tests
    scalacOptions in Test := (scalacOptions in Test).value diff Seq("-deprecation")
  ).dependsOn(PlayWsProject, PlayCaffeineCacheProject % "test")
    .dependsOn(PlaySpecs2Project % "test")
    .dependsOn(PlayTestProject % "test->test")
  .settings(livongoPublishSettings: _*)

lazy val PlayOpenIdProject = PlayCrossBuiltProject("Play-OpenID", "play-openid")
  .settings(
    parallelExecution in Test := false,
    // quieten deprecation warnings in tests
    scalacOptions in Test := (scalacOptions in Test).value diff Seq("-deprecation")
  ).dependsOn(PlayAhcWsProject)
  .dependsOn(PlaySpecs2Project % "test")
  .settings(livongoPublishSettings: _*)

lazy val PlayFiltersHelpersProject = PlayCrossBuiltProject("Filters-Helpers", "play-filters-helpers")
    .settings(
      libraryDependencies ++= playFilterDeps,
      parallelExecution in Test := false
    ).dependsOn(PlayProject, PlayTestProject % "test",
        PlayJavaProject % "test", PlaySpecs2Project % "test", PlayAhcWsProject % "test")
  .settings(livongoPublishSettings: _*)

// This project is just for testing Play, not really a public artifact
lazy val PlayIntegrationTestProject = PlayCrossBuiltProject("Play-Integration-Test", "play-integration-test")
    .enablePlugins(JavaAgent)
    .settings(
      libraryDependencies += okHttp % Test,
      parallelExecution in Test := false,
      mimaPreviousArtifacts := Set.empty,
      fork in Test := true,
      javaOptions in Test += "-Dfile.encoding=UTF8",
      javaAgents += jettyAlpnAgent % "test"
    )
    .dependsOn(
      PlayProject % "test->test",
      PlayLogback % "test->test",
      PlayAhcWsProject % "test->test",
      PlayServerProject % "test->test",
      PlaySpecs2Project
    )
    .dependsOn(PlayFiltersHelpersProject)
    .dependsOn(PlayJavaProject)
    .dependsOn(PlayJavaFormsProject)
    .dependsOn(PlayAkkaHttpServerProject)
    .dependsOn(PlayAkkaHttp2SupportProject)
    .dependsOn(PlayNettyServerProject)

// This project is just for microbenchmarking Play. Not published.
// NOTE: this project depends on JMH, which is GPLv2.
lazy val PlayMicrobenchmarkProject = PlayCrossBuiltProject("Play-Microbenchmark", "play-microbenchmark")
    .enablePlugins(JmhPlugin, JavaAgent)
    .settings(
      // Change settings so that IntelliJ can handle dependencies
      // from JMH to the integration tests. We can't use "compile->test"
      // when we depend on the integration test project, we have to use
      // "test->test" so that IntelliJ can handle it. This means that
      // we need to put our JMH sources into src/test so they can pick
      // up the integration test files.
      // See: https://github.com/ktoso/sbt-jmh/pull/73#issue-163891528

      classDirectory in Jmh := (classDirectory in Test).value,
      dependencyClasspath in Jmh := (dependencyClasspath in Test).value,
      generateJmhSourcesAndResources in Jmh := ((generateJmhSourcesAndResources in Jmh) dependsOn(compile in Test)).value,

      // Add the Jetty ALPN agent to the list of agents. This will cause the JAR to
      // be downloaded and available. We need to tell JMH to use this agent when it
      // forks its benchmark processes. We use a custom runner to read a system
      // property and add the agent JAR to JMH's forked process JVM arguments.
      javaAgents += jettyAlpnAgent,
      javaOptions in (Jmh, run) += {
        val javaAgents = (resolvedJavaAgents in Jmh).value
        assert(javaAgents.length == 1)
        val jettyAgentPath = javaAgents.head.artifact.absString
        s"-Djetty.anlp.agent.jar=$jettyAgentPath"
      },
      mainClass in (Jmh, run) := Some("play.microbenchmark.PlayJmhRunner"),

      parallelExecution in Test := false,
      mimaPreviousArtifacts := Set.empty
    )
    .dependsOn(
      PlayProject % "test->test",
      PlayLogback % "test->test",
      PlayIntegrationTestProject % "test->test",
      PlayAhcWsProject,
      PlaySpecs2Project,
      PlayFiltersHelpersProject,
      PlayJavaProject,
      PlayNettyServerProject
    )

lazy val PlayCacheProject = PlayCrossBuiltProject("Play-Cache", "play-cache")
    .settings(
      libraryDependencies ++= playCacheDeps
    )
    .dependsOn(
      PlayProject,
      PlaySpecs2Project % "test"
    )
  .settings(livongoPublishSettings: _*)


lazy val PlayEhcacheProject = PlayCrossBuiltProject("Play-Ehcache", "play-ehcache")
    .settings(
      libraryDependencies ++= playEhcacheDeps
    )
    .dependsOn(
      PlayProject,
      PlayCacheProject,
      PlaySpecs2Project % "test"
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayCaffeineCacheProject = PlayCrossBuiltProject("Play-Caffeine-Cache", "play-caffeine-cache")
    .settings(
      mimaPreviousArtifacts := Set.empty,
      libraryDependencies ++= playCaffeineDeps
    )
    .dependsOn(
      PlayProject,
      PlayCacheProject,
      PlaySpecs2Project % "test"
    )
  .settings(livongoPublishSettings: _*)

// JSR 107 cache bindings (note this does not depend on ehcache)
lazy val PlayJCacheProject = PlayCrossBuiltProject("Play-JCache", "play-jcache")
    .settings(
      libraryDependencies ++= jcacheApi
    )
    .dependsOn(
      PlayProject,
      PlayCaffeineCacheProject % "test", // provide a cachemanager implementation
      PlaySpecs2Project % "test"
    )
  .settings(livongoPublishSettings: _*)

lazy val PlayDocsSbtPlugin = PlaySbtPluginProject("Play-Docs-SBT-Plugin", "play-docs-sbt-plugin")
    .enablePlugins(SbtTwirl)
    .settings(
      libraryDependencies ++= playDocsSbtPluginDependencies
    ).dependsOn(SbtPluginProject)
  .settings(livongoPublishSettings: _*)

lazy val publishedProjects = Seq[ProjectReference](
  PlayProject,
  PlayGuiceProject,
  BuildLinkProject,
  RoutesCompilerProject,
  SbtRoutesCompilerProject,
  PlayAkkaHttpServerProject,
  PlayAkkaHttp2SupportProject,
  PlayCacheProject,
  PlayEhcacheProject,
  PlayCaffeineCacheProject,
  PlayJCacheProject,
  PlayJdbcApiProject,
  PlayJdbcProject,
  PlayJdbcEvolutionsProject,
  PlayJavaProject,
  PlayJavaFormsProject,
  PlayJodaFormsProject,
  PlayJavaJdbcProject,
  PlayJpaProject,
  PlayNettyServerProject,
  PlayServerProject,
  PlayLogback,
  PlayWsProject,
  PlayAhcWsProject,
  PlayOpenIdProject,
  RunSupportProject,
  SbtPluginProject,
  PlaySpecs2Project,
  PlayTestProject,
  PlayExceptionsProject,
  PlayDocsProject,
  PlayFiltersHelpersProject,
  PlayIntegrationTestProject,
  PlayDocsSbtPlugin,
  StreamsProject
)

lazy val PlayFramework = Project("Play-Framework", file("."))
    .enablePlugins(PlayRootProject)
    .enablePlugins(PlayWhitesourcePlugin)
    .enablePlugins(CrossPerProjectPlugin)
    .settings(playCommonSettings: _*)
    .settings(
      scalaVersion := (scalaVersion in PlayProject).value,
      // TODO: Remove when updating to Scala 2.13.0-M4
      // Should be removed when we update to Scala 2.13.0-M4 since this is the
      // version added by interplay.
      //
      // See also:
      // 1. playRuntimeSettings in project/BuildSettings.scala
      // 2. RoutesCompilerProject project
      crossScalaVersions := Seq(scala211, scala212, "2.13.0-M3"),
      playBuildRepoName in ThisBuild := "playframework",
      concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
      libraryDependencies ++= (runtime(scalaVersion.value) ++ jdbcDeps),
      Docs.apiDocsInclude := false,
      Docs.apiDocsIncludeManaged := false,
      mimaReportBinaryIssues := (),
      commands += Commands.quickPublish
    ).settings(Release.settings: _*)
  .settings(livongoPublishSettings: _*)
    .aggregate(publishedProjects: _*)
