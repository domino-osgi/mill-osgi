package de.tobiasroeser.mill.osgi

import scala.collection.JavaConverters._

import aQute.bnd.osgi.Builder
import aQute.bnd.osgi.Constants
import ammonite.ops.LsSeq
import ammonite.ops.mkdir
import ammonite.ops.rm
import mill._
import mill.define.Task
import mill.eval.PathRef
import mill.scalalib.JavaModule
import mill.scalalib.PublishModule

trait OsgiBundleModule extends JavaModule {

  import OsgiBundleModule._

  /**
   * The transitive version of `localClasspath`.
   * This overrides [[JavaModule.transitiveLocalClasspath]], but uses the final
   * JAR files instead of just the classes directories where possible.
   */
  override def transitiveLocalClasspath: T[Agg[PathRef]] = T {
    Task.traverse(recursiveModuleDeps) { m =>
      T.task {
        Agg(m.jar())
      }
    }().flatten
  }

  /**
   * Build the final bundle.
   * Overrides [[JavaModule.jar]] and links to [[osgiBundle]] instead.
   */
  override def jar: T[PathRef] = T {
    osgiBundle()
  }

  /**
   * The bundle symbolic name used to initialize [[osgiHeaders]].
   * If the module is a [[PublishModule]], it calculated the bundle symbolic name
   * from [[PublishModule.artifactMetadata]]
   */
  def bundleSymbolicName: T[String] = this match {
    case pm: PublishModule => T {
      calcBundleSymbolicName(pm.pomSettings().organization, artifactName())
    }
    case _ =>
      artifactName()
  }

  /**
   * The bundle version used to initialize [[osgiHeaders]].
   * If the module is a [[PublishModule]], it uses the [[PublishModule.publishVersion]]
   */
  def bundleVersion: T[String] = this match {
    case pm: PublishModule => T {
      pm.publishVersion()
    }
    case _ => "0.0.0"
  }

  /**
   * Instruct bnd to create a reproducible bundle file.
   */
  def reproducibleBundle: T[Boolean] = T {
    true
  }

  /**
   * Embed these JAR files and also add them to the bundle classpath.
   */
  def embeddedJars: T[Seq[PathRef]] = T {
    Seq[PathRef]()
  }

  /**
   * Embed the content of the given JAR files into the bundle.
   */
  def explodedJars: T[Seq[PathRef]] = T {
    Seq[PathRef]()
  }

  def osgiHeaders: T[OsgiHeaders] = {
    def withDefaults: OsgiHeaders => OsgiHeaders = h => h.copy(
      `Import-Package` = Seq("*")
    )

    this match {
      case pm: PublishModule => T {
        val pom = pm.pomSettings()
        withDefaults(OsgiHeaders(
          `Bundle-SymbolicName` = bundleSymbolicName(),
          `Bundle-Version` = Option(bundleVersion()),
          `Bundle-License` = pom.licenses.map(l => l.url.toString),
          `Bundle-Vendor` = Option(pom.organization),
          `Bundle-Description` = Option(pom.description)
        ))
      }
      case _ => T {
        withDefaults(OsgiHeaders(
          `Bundle-SymbolicName` = bundleSymbolicName(),
          `Bundle-Version` = Option(bundleVersion()),
        ))
      }
    }
  }

  /**
   * Iff `true` include sources in the final bundle under `OSGI-OPT/src`.
   */
  def includeSources: T[Boolean] = T {
    false
  }

  /**
   * Resources to include into the final bundle.
   * Defaults to include [[JavaModule.resources()]].
   */
  def includeResource: T[Seq[String]] = T {
    // default: add contents of resources to final bundle
    resources()
      // only take non-empty directories to avoid bnd warning/error
      .filter(p => p.path.toIO.exists()) //  && Option(p.path.toIO.list()).map(!_.isEmpty).getOrElse(false))
      // add to the root of the JAR
      .map(dir => dir.path.toIO.getAbsolutePath())
  }

  // TODO: do we want support default Mill Jar headers?

  /**
   * Additional headers to add to the bundle manifest.
   * Warning: All headers added here will override their previous value,
   * hence, be careful to not add standard OSGi headers here, but via [[osgiHeaders]].
   */
  def additionalHeaders: T[Map[String, String]] = T {
    Map[String, String]()
  }

  /**
   * Build the OSGi Bundle.
   */
  def osgiBundle: T[PathRef] = T {
    val log = T.ctx().log

    val builder = new Builder()
    if (reproducibleBundle()) {
      builder.setProperty(Constants.REPRODUCIBLE, "true")
    }

    // TODO: check if all dependencies have proper Manifests (are bundled as jars instead of class folders)
    val bndClasspath = (compileClasspath() ++ localClasspath()).toList.map(p => p.path.toIO).filter(_.exists()).asJava
    builder.setClasspath(bndClasspath)

    // TODO: scan classes directory and auto-add all dirs as private package
    val classesPath = compile().classes.path
    val ps: LsSeq = ammonite.ops.ls.rec.!(classesPath)
    val packages = ps.filter(_.isFile).flatMap { pFull =>
      val p = pFull.relativeTo(classesPath)
      if (p.segments.size > 1) {
        Seq((p / ammonite.ops.up).segments.mkString("."))
      } else {
        // Find way to include top-level package
        Seq(".")
      }
    }.distinct

    if (!packages.isEmpty) {
      builder.setProperty(Constants.PRIVATE_PACKAGE, packages.mkString(","))
    }
    //    // Special case, files in top level package
    //    // Those can't be exported, but we include them
    //    val rootPackageFiles: LsSeq = ammonite.ops.ls ! (classesPath)
    //    if (!rootPackageFiles.filter(_.isFile).isEmpty) {
    //      println("Found files in top level package")
    //      // mergeSeqProps(builder, Constants.INCLUDERESOURCE, Seq(classesPath.toIO.getAbsolutePath() + ";recursive:=false"))
    //      mergeSeqProps(builder, Constants.PRIVATE_PACKAGE, Seq(".;-split-package:=last"))
    //    }

    allSources().foreach { dir =>
      builder.setProperty(Constants.SOURCEPATH, dir.path.toIO.getAbsolutePath())
    }

    if (includeSources()) {
      mergeSeqProps(builder, Constants.INCLUDERESOURCE,
        allSources().filter(_.path.toIO.exists()).map(s => "OSGI-OPT/src=" + s.path.toIO.getAbsolutePath()).toList)
    }

    // TODO: Some validation that should at least war
    // * Fragment and activator at the same time
    // * Activator in exported package
    // * Packages not part of export or private

    // TODO: handle special props with defaults

    // handle included resources
    mergeSeqProps(builder, Constants.INCLUDERESOURCE, includeResource())

    // handle embedded Jars
    embeddedJars().foreach { jar =>
      mergeSeqProps(builder, Constants.INCLUDERESOURCE, Seq(jar.path.toIO.getAbsolutePath()))
    }

    // handle exploded Jars
    explodedJars().foreach { jar =>
      mergeSeqProps(builder, Constants.INCLUDERESOURCE, Seq("@" + jar.path.toIO.getAbsolutePath()))
    }

    builder.addProperties(osgiHeaders().toProperties)

    builder.addProperties(additionalHeaders().asJava)

    //    println("Props:" + builder.getProperties().asScala.toList.map {
    //      case (k, v) =>
    //        if (v.indexOf(",") > 0) {
    //          s"${k}:\n    ${v.split("[,]").mkString(",\n    ")}"
    //        } else {
    //          s"${k}: ${v}"
    //        }
    //    }.mkString("\n  "))

    val jar = builder.build()

    builder.getErrors().asScala.foreach(msg => log.error("bnd error: " + msg))
    builder.getWarnings().asScala.foreach(msg => log.error("bnd warning: " + msg))

    val outputPath = T.ctx().dest / "out.jar"
    mkdir(outputPath / ammonite.ops.up)
    rm(outputPath)

    jar.write(outputPath.toIO)

    PathRef(outputPath)
  }

}

object OsgiBundleModule {

  def calcBundleSymbolicName(group: String, artifact: String): String = {
    val groupParts = group.split("[.]")
    val nameParts = artifact.split("[.]").flatMap(_.split("[-]"))

    val parts =
      if (nameParts.startsWith(groupParts)) nameParts
      else (groupParts.lastOption, nameParts.headOption) match {
        case (Some(last), Some(head)) if last == head => groupParts ++ nameParts.tail
        case (Some(last), Some(head)) if head.startsWith(last) => groupParts.take(groupParts.size - 1) ++ nameParts
        case _ => groupParts ++ nameParts
      }

    parts.mkString(".")
  }

  def mergeSeqProps(builder: Builder, key: String, value: Seq[String]): Unit = {
    val existing = builder.getProperty(key) match {
      case null => Seq()
      case p => Seq(p)
    }
    builder.setProperty(key, (existing ++ value).mkString(","))
  }

}
