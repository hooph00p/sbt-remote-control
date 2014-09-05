package sbt
package server

import BasicCommands._
import BasicCommandStrings._
import CommandStrings._
import BuiltinCommands._
import CommandUtil._
import Project.LoadAction.{ Value => LoadActionValue }
import Project.loadActionParser
import complete.DefaultParsers._
import sbt.StateOps

/**
 * Represents overrides of the project loading commands that are required for appropriate server
 * usage.
 *
 * For example:
 * - We do not want to hit System.in on load failure for confirmation of retry, but instead hit a client.
 * - We want to "hook" the notion of `reload plugins`/`reload return` for special handling.
 */
object ServerBootCommand {

  /** A new load failed command which handles the server requirements */
  private def serverLoadFailed(eventSink: JsonSink[protocol.BuildFailedToLoad]) =
    Command(LoadFailed)(loadProjectParser)(doServerLoadFailed(eventSink, _, _))

  private def projectReload(engine: ServerEngine) = {
    Command(LoadProject)(_ => Project.loadActionParser) { (state, action) =>
      action match {
        case Project.LoadAction.Current =>
          engine.installBuildHooks(BuiltinCommands.doLoadProject(state, action))
        // TODO : Detect setting changes and fire an event
        case _ =>
          throw new IllegalArgumentException("'reload' command is not supported for plugins.")
      }
    }
  }

  /** List of commands which override sbt's default commands. */
  def commandOverrides(engine: ServerEngine, eventSink: JsonSink[protocol.BuildFailedToLoad]) = Seq(serverLoadFailed(eventSink), projectReload(engine))

  def isOverriden(cmd: Command): Boolean =
    cmd == loadFailed

  /** Actual does the failing to load for the sbt server. */
  private[this] def doServerLoadFailed(eventSink: JsonSink[protocol.BuildFailedToLoad], s: State, action: String): State = {
    s.log.error("Failed to load project.")
    eventSink.send(protocol.BuildFailedToLoad())
    // this causes the command loop to exit which should make the whole server exit,
    // though we may get fancier someday and try to reload.
    s.exit(ok = false)
  }

  // TODO - Copied out of BuiltInCommands
  private[this] def loadProjectParser = (s: State) => matched(loadActionParser)
}
