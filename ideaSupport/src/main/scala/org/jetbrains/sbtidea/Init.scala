package org.jetbrains.sbtidea

import org.jetbrains.sbtidea.download._
import org.jetbrains.sbtidea.packaging.PackagingKeys._
import org.jetbrains.sbtidea.packaging.artifact.IdeaArtifactXmlBuilder
import sbt.Keys._
import sbt.complete.DefaultParsers
import sbt.{file, _}

trait Init { this: Keys.type =>

  private val targetFileParser = DefaultParsers.fileParser(file("/"))
  private lazy val homePrefix: File = sys.props.get("tc.idea.prefix").map(new File(_)).getOrElse(Path.userHome)
  private lazy val ivyHomeDir: File = Option(System.getProperty("sbt.ivy.home")).fold(homePrefix / ".ivy2")(file)

  lazy val globalSettings: Seq[Setting[_]] = Seq(
    dumpStructureTo in Global:= Def.inputTaskDyn {
      val path = targetFileParser.parsed
      createIDEAArtifactXml.?.all(ScopeFilter(inProjects(LocalRootProject))).value.flatten
      val fromStructure = dumpStructureTo.in(Global).?.value
      if (fromStructure.isDefined) {
        Def.inputTask {
          fromStructure.get.fullInput(path.getAbsolutePath).evaluated
        }.toTask("")
      } else
        Def.task { new File(".") }
    }.evaluated,
    dumpStructure := Def.task {
      createIDEAArtifactXml.?.all(ScopeFilter(inProjects(LocalRootProject))).value.flatten
      dumpStructure.in(Global).?.value
    }.value
  )

  lazy val buildSettings: Seq[Setting[_]] = Seq(
    ideaPluginName      := name.in(LocalRootProject).value,
    ideaBuild           := "LATEST-EAP-SNAPSHOT",
    ideaEdition         := IdeaEdition.Community,
    ideaDownloadSources := true,
    ideaPluginDirectory   := homePrefix / s".${ideaPluginName.value}Plugin${ideaEdition.value.shortname}",
    ideaBaseDirectory     := ideaDownloadDirectory.value / ideaBuild.value,
    ideaDownloadDirectory := ideaPluginDirectory.value / "sdk",
    ideaTestConfigDir     := ideaPluginDirectory.value / "test-config",
    ideaTestSystemDir     := ideaPluginDirectory.value / "test-system",
    concurrentRestrictions in Global += Tags.limit(Tags.Test, 1), // IDEA tests can't be run in parallel
    updateIdea := {
      val logger = new SbtPluginLogger(streams.value)
      new CommunityIdeaUpdater(ideaBaseDirectory.value.toPath, logger)
        .updateIdeaAndPlugins(
          BuildInfo(
            ideaBuild.value,
            ideaEdition.value
          ),
          ideaExternalPlugins.?.all(ScopeFilter(inAnyProject)).value.flatten.flatten,
          ideaDownloadSources.value
        )
    },
    cleanUpTestEnvironment := {
      IO.delete(ideaTestSystemDir.value)
      IO.delete(ideaTestConfigDir.value)
    },

    onLoad in Global := ((s: State) => {
      "updateIdea" :: s
    }) compose (onLoad in Global).value
  )

  lazy val projectSettings: Seq[Setting[_]] = Seq(
    ideaInternalPlugins := Seq.empty,
    ideaExternalPlugins := Seq.empty,
    ideaMainJars := (ideaBaseDirectory.value / "lib" * "*.jar").classpath,
    ideaPluginJars :=
      tasks.CreatePluginsClasspath(ideaBaseDirectory.value / "plugins",
        ideaInternalPlugins.value,
        ideaExternalPlugins.value,
        new SbtPluginLogger(streams.value)),

    ideaFullJars := ideaMainJars.value ++ ideaPluginJars.value,
    unmanagedJars in Compile ++= ideaFullJars.value,

    packageOutputDir := target.value / "plugin" / ideaPluginName.value,
    ideaPluginFile   := target.value / s"${ideaPluginName.value}-${version.value}.zip",
    ideaPublishSettings := PublishSettings("", "", "", None),
    publishPlugin := tasks.PublishPlugin.apply(ideaPublishSettings.value, ideaPluginFile.value, streams.value),

    createIDEAArtifactXml := Def.taskDyn {
      val buildRoot = baseDirectory.in(ThisBuild).value
      val projectRoot = baseDirectory.in(ThisProject).value

      if (buildRoot == projectRoot)
        Def.task {
          val outputDir = packageOutputDir.value
          val mappings  = packageMappingsOffline.value
          val projectName = thisProject.value.id
          val result = new IdeaArtifactXmlBuilder(projectName, outputDir).produceArtifact(mappings)
          val file = buildRoot / ".idea" / "artifacts" / s"$projectName.xml"
          IO.write(file, result)
        }
      else Def.task { }
    }.value,
    aggregate.in(packageArtifactZip) := false,
    aggregate.in(packageMappings) := false,
    aggregate.in(packageArtifact) := false,
    aggregate.in(updateIdea) := false,
    unmanagedJars in Compile += file(System.getProperty("java.home")).getParentFile / "lib" / "tools.jar",



    // Test-related settings

    fork in Test := true,
    parallelExecution := false,
    logBuffered := false,
    javaOptions in Test := Seq(
      "-Xms128m",
      "-Xmx4096m",
      "-server",
      "-ea",
      s"-Didea.system.path=${ideaTestSystemDir.value}",
      s"-Didea.config.path=${ideaTestConfigDir.value}",
      s"-Dsbt.ivy.home=$ivyHomeDir",
      s"-Dplugin.path=${packageOutputDir.value}"
    ),
    envVars in Test += "NO_FS_ROOTS_ACCESS_CHECK" -> "yes"
  )
}
