/**
 * Copyright (C) 2014 TU Berlin (peel@dima.tu-berlin.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.peelframework.core.beans.system

import java.io.File
import java.nio.file.Paths

import org.peelframework.core.beans.experiment.Experiment.Run
import org.peelframework.core.util.shell

import scala.collection.Seq
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.util.matching.Regex

/** A trait that implements log collection behaviour for systems.
  *
  * In difference to [[LogCollection]] the logs are copied via ssh, thus it does not require a
  * shared folder.
  *
  * Subclasses of this trait must provide a list of `slaves` which are used to gather the logs via ssh.
  */
trait DistributedLogCollection {
  self: System =>

  def slaves: Seq[String]

  case class FileEntry(slave: String, file: String)

  /** Log file line counts at the beginning of the last run. */
  private var logFileCounts: Map[FileEntry, Long] = null

  /** Executed before each experiment run that depends on this system. */
  override def beforeRun(run: Run[System]): Unit = {
    // handle dependencies
    for (s <- dependencies) {
      s.beforeRun(run)
    }
    // get the log path of this system
    implicit val patterns = logFilePatterns()

    val findIn = (slave: String, logFolder: String) =>
      s"""ssh $slave "find $logFolder -type f -exec ls {} \\; 2> /dev/null"""".trim

    val countFiles = (slave: String, logFile: String) =>
      s"""ssh $slave "wc -l $logFile | xargs | cut -d' ' -f1""""

    // collect relevant log files asynchronously
    val futureLogFileCounts = Future.traverse(slaves)(slave => {
      val logPath = config.getString(s"system.$configKey.path.log")
      for {
        files <- Future {
          (shell !! findIn(slave, logPath)).split('\n').filter(f => canProcess(new File(f)) && f.contains(slave)).toSeq
        }
        count <- Future.traverse(files)(logFile => Future {
          FileEntry(slave, logFile) -> (shell !! countFiles(slave, logFile)).trim.toLong
        })
      } yield count
    })

    // await for all future log file counts and convert the result to a map
    logFileCounts = Await.result(futureLogFileCounts, (5 * slaves.size).seconds).flatten.toMap
  }

  /** Executed after each experiment run that depends on this system. */
  override def afterRun(run: Run[System]): Unit = {
    // ensure target folder where log should be stored exists, is empty and is writable
    for (folder <- Some(Paths.get(run.home, "logs", name, beanName))) {
      shell.ensureFolderIsWritable(folder)
      shell.rm(folder.toString)
      shell.ensureFolderIsWritable(folder)
    }

    val copyFile = (slave: String, logFile: String, count: Long) =>
      s"""ssh $slave "tail -n +${count + 1} $logFile" > ${run.home}/logs/$name/$beanName/${Paths.get(logFile).getFileName}"""

    // dump the new files in the log folder asynchronously
    val futureCopyOps = Future.traverse(logFileCounts)(logFileCount => {
      val (FileEntry(slave, logFile), count) = logFileCount
      Future(shell ! copyFile(slave, logFile, count))
    })

    // await for all copy operations to finish
    Await.result(futureCopyOps, (5 * logFileCounts.size).seconds)

    // handle dependencies
    for (s <- dependencies) {
      s.afterRun(run)
    }
  }

  /** The patterns of the log files to watch. */
  protected def logFilePatterns(): Seq[Regex]

  /** A file can be processed if and only if at least one of the file patterns matches. */
  private def canProcess(file: File)(implicit patterns: Seq[Regex]): Boolean = {
    patterns.exists(_.pattern.matcher(file.getName).matches())
  }
}
