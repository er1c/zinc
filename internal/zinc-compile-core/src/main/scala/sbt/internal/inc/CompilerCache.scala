/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package sbt
package internal
package inc

import java.io.File
import java.util

import xsbti.{ AnalysisCallback, Reporter, Logger => xLogger }
import xsbti.compile._
import sbt.util.InterfaceUtil.{ toSupplier => f0 }

/**
 * Manage a number of <code>maxInstance</code> of cached Scala compilers.
 * @param maxInstances The maximum number to be cached.
 */
// TODO Remove this class and associated code in SBT/Zinc.
// This is unfinished business from https://github.com/sbt/zinc/issues/340
final class CompilerCache(val maxInstances: Int) extends GlobalsCache {

  /** Define a least-recently used cache indexed by a generated key. */
  private[this] val cache = lru[CompilerKey, CachedCompiler](maxInstances)
  private[this] def lru[A, B](max: Int) = {
    new util.LinkedHashMap[A, B](8, 0.75f, true) {
      override def removeEldestEntry(eldest: util.Map.Entry[A, B]): Boolean =
        size > max
    }
  }

  override def apply(
      args: Array[String],
      output: Output,
      forceNew: Boolean,
      c: CachedCompilerProvider,
      log: xLogger,
      reporter: Reporter
  ): CachedCompiler = synchronized {
    val scalaVersion = c.scalaInstance.actualVersion
    val key = CompilerKey(dropSources(args.toList), scalaVersion)
    if (forceNew) cache.remove(key)
    cache.get(key) match {
      case null =>
        log.debug(f0(s"Compiler cache miss. $key "))
        val compiler = c.newCachedCompiler(args, output, log, reporter)
        val newCompiler: CachedCompiler = new CachedCompiler with java.io.Closeable {
          override def commandArguments(sources: Array[File]): Array[String] = {
            compiler.commandArguments(sources)
          }
          override def run(sources: Array[File],
                           changes: DependencyChanges,
                           callback: AnalysisCallback,
                           logger: xLogger,
                           delegate: Reporter,
                           progress: CompileProgress): Unit = {
            compiler.run(sources, changes, callback, logger, delegate, progress)
          }
          override def close(): Unit = {
            // Dont' close the underlying Global.
          }
        }
        cache.put(key, newCompiler)
        newCompiler
      case cachedCompiler =>
        val hexHashCode = cachedCompiler.hashCode.toLong.toHexString
        log.debug(f0(s"Compiler cache hit ($hexHashCode). $key"))
        cachedCompiler
    }
  }

  override def clear(): Unit = synchronized { cache.clear() }

  private[this] def dropSources(args: Seq[String]): Seq[String] =
    args.filterNot(arg => arg.endsWith(".scala") || arg.endsWith(".java"))

  private[this] case class CompilerKey(args: Seq[String], scalaVersion: String) {
    override def toString: String =
      s"scala $scalaVersion, args: ${args.mkString(" ")}"
  }
}

final class FreshCompilerCache extends GlobalsCache {
  def clear(): Unit = ()
  def apply(
      args: Array[String],
      output: Output,
      forceNew: Boolean,
      c: CachedCompilerProvider,
      log: xLogger,
      reporter: Reporter
  ): CachedCompiler = c.newCachedCompiler(args, output, log, reporter)
}
