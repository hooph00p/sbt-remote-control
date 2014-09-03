package sbt

import play.api.libs.json._

sealed trait InteractionService {
  /** Prompts the user for input, optionally with a mask for characters. */
  def readLine(prompt: String, mask: Boolean): Option[String]
  /** Ask the user to confirm something (yes or no) before continuing. */
  def confirm(msg: String): Boolean

  // TODO - Ask for input with autocomplete?
}

sealed trait SendEventService {
  /** Sends an event out to all registered event listeners. */
  def sendEvent[T: Writes](event: T): Unit
}

/**
 * Represents a Manifest/Format pair we can use
 *  to serialize task values + events later.
 */
sealed trait RegisteredFormat {
  type T
  def manifest: Manifest[T]
  def format: Format[T]
}
object RegisteredFormat {
  def apply[U](f: Format[U])(implicit mf: Manifest[U]): RegisteredFormat =
    new RegisteredFormat {
      type T = U
      override val format = f
      override val manifest = mf
    }
}
object UIKeys {
  val interactionService = taskKey[InteractionService]("Service used to ask for user input through the current user interface(s).")
  val sendEventService = taskKey[SendEventService]("Service used to send events to the current user interface(s).")
  val registeredFormats = settingKey[Seq[RegisteredFormat]]("All the formats needed to serialize events/messages to the client.")

  // this is a setting not a task because semantically it's required to always be the same one
  val jobService = settingKey[BackgroundJobService]("Job manager used to run background jobs.")
  val jobList = taskKey[Seq[BackgroundJobHandle]]("List running background jobs.")
  val jobStop = inputKey[Unit]("Stop a background job by providing its ID.")
  val jobWaitFor = inputKey[Unit]("Wait for a background job to finish by providing its ID.")
  val backgroundRun = inputKey[BackgroundJobHandle]("Start an application's default main class as a background job")
  val backgroundRunMain = inputKey[BackgroundJobHandle]("Start a provided main class as a background job")
}
private[sbt] trait AbstractInteractionService extends InteractionService
private[sbt] trait AbstractSendEventService extends SendEventService
