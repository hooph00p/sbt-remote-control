package com.typesafe.sbtrc
package it
package loading

import sbt.client._
import sbt.protocol._
import concurrent.duration.Duration.Inf
import concurrent.Await
import concurrent.ExecutionContext
import concurrent.Promise
import java.io.File
import sbt.client.ScopedKey
import java.util.concurrent.Executors

class CanLoadSimpleProject extends SbtClientTest {
  // TODO - Don't hardcode sbt versions, unless we have to...
  val dummy = utils.makeDummySbtProject("test")

  sbt.IO.write(new java.io.File(dummy, "stdout.sbt"),

    """| val printOut = taskKey[Unit]("Print to stdout and see if we get it.")
       |  
       | printOut := {
       |   System.out.println("test-out")
       |   System.err.println("test-err")
       |   streams.value.log.info("test-info")
       | }
       |""".stripMargin)

  val errorFile = new java.io.File(dummy, "src/main/scala/error.scala")

  sbt.IO.write(errorFile,
    """object Foo(x: String)""".stripMargin)

  withSbt(dummy) { client =>
    val build = Promise[MinimalBuildStructure]
    val executorService = Executors.newSingleThreadExecutor()
    implicit val keepEventsInOrderExecutor = ExecutionContext.fromExecutorService(executorService)
    client watchBuild build.trySuccess
    val result = waitWithError(build.future, "Never got build structure.")
    assert(result.projects.size == 1, "Found too many projects!")
    val project = result.projects.head
    assert(project.id.name == "test", "failed to discover project name == file name.")
    assert(project.plugins contains "sbt.plugins.JvmPlugin", s"failed to discover default plugins in project, found: ${project.plugins.mkString(", ")}")

    // Here we check autocompletions:
    val completes = waitWithError(client.possibleAutocompletions("hel", 0), "Autocompletions not returned in time.")
    assert(completes.exists(_.append == "p"), "Failed to autocomplete `help` command.")

    def testTask(requestExecution: => concurrent.Future[Long]): Unit = {
      // Attempt to call a custom task and ensure we get our stdout events.
      val stdoutCaptured = Promise[Unit]
      val logInfoCaptured = Promise[Unit]
      val stderrCaptured = Promise[Unit]
      val executionDone = Promise[Unit]
      val stdoutSub = (client handleEvents {
        case TaskLogEvent(_, LogStdOut(line)) if line contains "test-out" =>
          stdoutCaptured.success(())
        case TaskLogEvent(_, LogStdErr(line)) if line contains "test-err" =>
          stderrCaptured.success(())
        case TaskLogEvent(_, LogMessage("info", line)) if line contains "test-info" =>
          logInfoCaptured.success(())
        case _: ExecutionSuccess | _: ExecutionFailure =>
          executionDone.trySuccess(())
        case _ =>
      })(keepEventsInOrderExecutor)
      try {
        requestExecution
        // Now we wait for the futures to fill out.
        waitWithError(stdoutCaptured.future, "Unable to read known stdout lines from server")
        waitWithError(stderrCaptured.future, "Unable to read known stderr lines from server")
        waitWithError(logInfoCaptured.future, "Unable to read known log info lines from server")
        // we block on this so there aren't leftover events to confuse later tests
        waitWithError(executionDone.future, "execution did not complete")
      } finally {
        stdoutSub.cancel()
      }
    }
    testTask(client.requestExecution("printOut", None))
    testTask {
      client.lookupScopedKey("printOut") flatMap { keys => client.requestExecution(keys.head, None) }
    }

    // Now we check compilation failure messages
    val compileErrorCaptured = Promise[CompilationFailure]
    val compileErrorSub = (client handleEvents {
      case CompilationFailure(taskId, failure) => compileErrorCaptured.trySuccess(failure)
      case _: ExecutionFailure | _: ExecutionSuccess =>
        compileErrorCaptured.tryFailure(new AssertionError("compile execution ended with no CompilationFailure"))
      case _ =>
    })(keepEventsInOrderExecutor)
    client.requestExecution("compile", None)
    val error = try {
      waitWithError(compileErrorCaptured.future, "Never received compilation failure!")
    } finally {
      compileErrorSub.cancel()
    }
    assert(error.severity == xsbti.Severity.Error, "Failed to capture appropriate error.")

    val keysFuture = client.lookupScopedKey("compile")
    val keys = waitWithError(keysFuture, "Never received key lookup response!")
    assert(!keys.isEmpty && keys.head.key.name == "compile", s"Failed to find compile key: $keys!")

    // check receiving the value of a setting key
    val baseDirectoryKeysFuture = client.lookupScopedKey(s"${project.id.name}/baseDirectory")
    val baseDirectoryKeys = waitWithError(baseDirectoryKeysFuture, "Never received key lookup response!")

    val baseDirectoryPromise = concurrent.Promise[File]
    client.watch(SettingKey[File](baseDirectoryKeys.head)) { (a, b) =>
      b match {
        case TaskSuccess(file) =>
          baseDirectoryPromise.trySuccess(file.value.get)
        case TaskFailure(msg) =>
          baseDirectoryPromise.tryFailure(new Exception(msg))
      }
    }

    val baseDirectory = waitWithError(baseDirectoryPromise.future, "Never received watch setting key first value")
    assert(dummy.getAbsoluteFile() == baseDirectory, s"Failed to received correct baseDirectory: $baseDirectory")

    // check receiving the initial value of a task key
    val unmanagedSourcesKeysFuture = client.lookupScopedKey(s"${project.id.name}/compile:unmanagedSources")
    val unmanagedSourcesKeys = waitWithError(unmanagedSourcesKeysFuture, "Never received key lookup response!")

    val unmanagedSourcesPromise = concurrent.Promise[collection.Seq[File]]
    client.watch(TaskKey[collection.Seq[File]](unmanagedSourcesKeys.head)) { (a, b) =>
      b match {
        case TaskSuccess(files) =>
          unmanagedSourcesPromise.trySuccess(files.value.get)
        case TaskFailure(msg) =>
          unmanagedSourcesPromise.tryFailure(new Exception(msg))
      }
    }

    val unmanagedSources = waitWithError(unmanagedSourcesPromise.future, "Never received watch task key first value")
    val expectedSources =
      Seq(
        new File(dummy, "src/main/scala/hello.scala").getCanonicalFile,
        new File(dummy, "src/main/scala/error.scala").getCanonicalFile)
    assert(unmanagedSources.sorted == expectedSources.sorted, s"Failed to received correct unmanagedSources: $unmanagedSources, expected $expectedSources")

    // delete the broken file
    errorFile.delete()

    // Now check that we get test events
    @volatile var testEvents: List[TestEvent] = Nil
    val executionId = concurrent.Promise[Long]
    val executionDone = concurrent.Promise[Unit]
    val testEventSub = (client handleEvents { event =>
      val id = waitWithError(executionId.future, "never got executionId")
      event match {
        case TestEvent(taskId, testEvent) =>
          testEvents +:= testEvent
        case ExecutionSuccess(successId) if successId == id =>
          executionDone.trySuccess(())
        case ExecutionFailure(failId) if failId == id =>
          // this is actually the expected case since we know we have failing tests
          executionDone.trySuccess(())
        case _ =>
      }
    })(keepEventsInOrderExecutor)
    try {
      val id = waitWithError(executionId.completeWith(client.requestExecution("test", None)).future, "never received execution ID")
      waitWithError(executionDone.future, "execution of 'test' never completed")
    } finally {
      testEventSub.cancel()
    }
    implicit object TestEventOrdering extends Ordering[TestEvent] {
      val optionStringOrdering = implicitly[Ordering[Option[String]]]
      override def compare(a: TestEvent, b: TestEvent): Int = {
        a.name.compare(b.name) match {
          case 0 => optionStringOrdering.compare(a.description, b.description) match {
            case 0 => optionStringOrdering.compare(a.error, b.error) match {
              case 0 => a.outcome.success.compare(b.outcome.success)
              case other => other
            }
            case other => other
          }
          case other => other
        }
      }
    }
    val expected = List(TestEvent("OnePassTest.testThatShouldPass", None, TestPassed, None, -1),
      TestEvent("OneFailTest.testThatShouldFail", None, TestFailed, Some("this is not true"), -1),
      TestEvent("OnePassOneFailTest.testThatShouldPass", None, TestPassed, None, -1),
      TestEvent("OnePassOneFailTest.testThatShouldFail", None, TestFailed, Some("this is not true"), -1)).sorted
    assertEquals(expected, testEvents.sorted)

    // Now test execution analysis for three cases

    // analyze a task key
    val keyAnalysis = waitWithError(client.analyzeExecution("compile"), "failed to analyze execution of 'compile'")
    keyAnalysis match {
      case ExecutionAnalysisKey(keys) =>
        assert(keys.nonEmpty)
        assert(keys.head.key.name.contains("compile"))
      case other => throw new AssertionError("'compile' was not analyzed as a key")
    }

    // analyze a command
    val commandAnalysis = waitWithError(client.analyzeExecution("help"), "failed to analyze execution of 'help'")
    commandAnalysis match {
      case ExecutionAnalysisCommand(nameOption) =>
        assertEquals(Some("help"), nameOption)
      case other => throw new AssertionError("'help' was not analyzed as a command")
    }

    // analyze some nonsense
    val errorAnalysis = waitWithError(client.analyzeExecution("invalidNope"), "failed to analyze invalid execution")
    errorAnalysis match {
      case ExecutionAnalysisError(message) =>
        assert(message.nonEmpty)
      case other =>
        throw new AssertionError("didn't get an error analyzing an invalid execution")
    }
  }
}
