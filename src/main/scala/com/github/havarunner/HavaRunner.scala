package com.github.havarunner

import org.junit.runner.{Description, Runner}
import org.junit.runner.notification.RunNotifier
import scala.collection.JavaConversions._
import Validations._
import java.lang.reflect.{Field, InvocationTargetException}
import org.junit.runner.manipulation.{Filter, Filterable}
import com.github.havarunner.HavaRunner._
import com.github.havarunner.exception.TestDidNotRiseExpectedException
import com.github.havarunner.Parser._
import com.github.havarunner.Reflections._
import org.junit.internal.AssumptionViolatedException
import com.github.havarunner.TestInstanceCache._
import com.github.havarunner.ConcurrencyControl._
import com.github.havarunner.ExceptionHelper._
import org.junit.runners.model.Statement
import org.junit.rules.TestRule
import org.junit.runner.notification.Failure
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

/**
 * Usage: @org.junit.runner.RunWith(HavaRunner.class)
 *
 * @author Lauri Lehmijoki
 */
class HavaRunner(parentClass: Class[_ <: Any]) extends Runner with Filterable {

  private var filterOption: Option[Filter] = None // The Filterable API requires us to use a var

  def getDescription = {
    val description = Description.createSuiteDescription(parentClass)
    children.iterator() foreach (child => description.addChild(describeChild(child)))
    description
  }

  def run(notifier: RunNotifier) {
    reportIfSuite()
    val afterAllFutures = children
      .groupBy(_.criterion)
      .map {
        case (_, testsAndParameters) => runTestsOfSameGroup(testsAndParameters, notifier)
      }

    waitAndHandleRestOfErrors(afterAllFutures)
  }

  private def runTestsOfSameGroup(testsAndParameters: Iterable[TestAndParameters], notifier: RunNotifier): Future[Any] = {
    val resultsOfSameGroup: Iterable[Future[TestLifecycle]] = testsAndParameters.flatMap(runChild(_, notifier))
    Future.sequence(resultsOfSameGroup).map {
      (result: Iterable[TestLifecycle]) =>
        result.headOption map {
          case FailedInstantiation => // Instantiating the test object failed - there's nothing we can do anymore
          case CompletedCycle(testInstance, testMethodResult) => // Instantiating the object succeeded – run the @AfterAll methods
            testsAndParameters.head.afterAll.foreach(invoke(_)(testInstance)) // It suffices to run the @AfterAlls against any instance of the group
        }
    }
  }

  private def waitAndHandleRestOfErrors(afterAllFutures: Iterable[Future[Any]]) {
    val allTests = Future.sequence(afterAllFutures)
    var failure: Option[Throwable] = None
    allTests onFailure {
      case t: Throwable => failure = Some(t) // Unlift the exception from the Future container, so that we can handle it in the main thread
    }
    Await.ready(allTests, 2 hours)
    failure.foreach(throw _) // If @AfterAll methods throw exceptions, re-throw them here
  }

  def filter(filter: Filter) {
    this.filterOption = Some(filter)
  }

  private[havarunner] def reportIfSuite() =
    children.filter(_.testContext.isInstanceOf[SuiteContext]).foreach(testAndParameters => {
      val suiteContext: SuiteContext = testAndParameters.testContext.asInstanceOf[SuiteContext]
      println(s"[HavaRunner] Running ${testAndParameters.minimalToString} as a part of ${suiteContext.suiteClass.getSimpleName}")
    })

  private[havarunner] def runChild(implicit testAndParameters: TestAndParameters, notifier: RunNotifier): Option[Future[TestLifecycle]] = {
    implicit val description = describeChild
    val testIsInvalidReport = reportInvalidations
    if (testIsInvalidReport.isEmpty) {
      scheduleOrIgnore
    } else {
      reportFailure(testIsInvalidReport)
      None
    }
  }

  private[havarunner] val classesToTest = findDeclaredClasses(parentClass)

  private[havarunner] lazy val children: java.lang.Iterable[TestAndParameters] =
    parseTestsAndParameters(classesToTest).filter(acceptChild(_, filterOption))
}

private object HavaRunner {
  private def acceptChild(testParameters: TestAndParameters, filterOption: Option[Filter]): Boolean =
    filterOption.map(filter => {
      val FilterDescribePattern = "Method (.*)\\((.*)\\)".r
      filter.describe() match {
        case FilterDescribePattern(desiredMethodName, desiredClassName) =>
          val methodNameMatches = testParameters.testMethod.getName.equals(desiredMethodName)
          val classNameMatches: Boolean = testParameters.testClass.getName.equals(desiredClassName)
          classNameMatches && methodNameMatches
        case unexpected => throw new IllegalArgumentException(s"Filter#describe returned an unexpected string $unexpected")
      }
    }).getOrElse(true)

  private def describeChild(implicit testAndParameters: TestAndParameters) =
    Description createTestDescription(
      testAndParameters.testClass,
      testAndParameters.testMethod.getName + testAndParameters.scenarioToString
      )

  private def scheduleOrIgnore(implicit testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description): Option[Future[TestLifecycle]] =
    if (testAndParameters.ignored) {
      notifier fireTestIgnored description
      None
    } else {
      Some(schedule)
    }

  private def schedule(implicit testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description): Future[TestLifecycle] =
    testInstance flatMap {
      implicit testInstance => scheduleTest map { testMethodResult => CompletedCycle(testInstance, testMethodResult) }
    } recover {
      case errorFromConstructor: Throwable =>
        handleException(errorFromConstructor) // We come here when the test instance constructor throws an exception
        FailedInstantiation
    }

  def scheduleTest(implicit testInstance: TestInstance, testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description): 
    Future[TestMethodResult] =
      future {
        withThrottle {
          runWithRules {
            runTest
          }
          PassedTestMethod
        }
      } recover {
        case errorFromMethod: Throwable =>
          handleException(errorFromMethod) // We come here if any of the test, the @Before or the @After methods throw an exception
          FailedTestMethod
      }

  private def runWithRules(f: => Any)(implicit testAndParameters: TestAndParameters, testInstance: TestInstance) {
    val inner = new Statement {
      def evaluate() {
        f
      }
    }
    def applyRuleAndHandleException(rule: Field, accumulator: Statement) =
      try {
        val testRule: TestRule = rule.get(testInstance.instance).asInstanceOf[TestRule]
        testRule.apply(accumulator, describeChild)
      } catch {
        case e: InvocationTargetException =>
          if (e.getCause.getClass == classOf[AssumptionViolatedException]) inner
          else {
            println(e.getMessage)
            throw e
          }
      }
    val foldedRules = testAndParameters
      .rules
      .foldLeft(inner) {
        (accumulator: Statement, rule: Field) => 
          applyRuleAndHandleException(rule, accumulator)
      }
    foldedRules.evaluate()
  }

  private def runTest(implicit testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description, testInstance: TestInstance) {
    notifier fireTestStarted description
    try {
      invokeEach(testAndParameters.before)
      maybeTimeouting { testAndParameters.testMethod.invoke(testInstance.instance)}
      failIfExpectedExceptionNotThrown
    } finally {
      try {
        invokeEach(testAndParameters.after)
      } finally {
        notifier fireTestFinished description
      }
    }
  }

  private def handleException(e: Throwable)(implicit testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description) {
    Option(e) match {
      case Some(exception) if exception.isInstanceOf[AssumptionViolatedException] =>
        val msg = s"[HavaRunner] Ignored $testAndParameters, because it did not meet an assumption"
        notifier fireTestAssumptionFailed new Failure(description, new AssumptionViolatedException(msg))
      case Some(exception) if testAndParameters.expectedException.isDefined =>
        if (exception.getClass == testAndParameters.expectedException.get) {
          // Expected exception. All ok.
        }
      case Some(exception) if exception.isInstanceOf[InvocationTargetException] =>
         handleException(exception.asInstanceOf[InvocationTargetException].getTargetException)
      case Some(exception) =>
        notifier fireTestFailure new Failure(description, exception)
    }
  }

  private def maybeTimeouting(op: => Any)(implicit testAndParameters: TestAndParameters) {
    testAndParameters.timeout.map(timeout => {
      val start = System.currentTimeMillis()
      op
      val duration = System.currentTimeMillis() - start
      if (duration >= timeout) {
        throw new RuntimeException(s"Test timed out after $duration milliseconds")
      }
    }).getOrElse(op)
  }

  private def failIfExpectedExceptionNotThrown(implicit testAndParameters: TestAndParameters, notifier: RunNotifier, description: Description) {
    testAndParameters.expectedException.foreach(expected =>
      notifier fireTestFailure new Failure(description, new TestDidNotRiseExpectedException(testAndParameters.expectedException.get, testAndParameters))
    )
  }
  
  private[havarunner] trait TestMethodResult
  private[havarunner] object FailedTestMethod extends TestMethodResult
  private[havarunner] object PassedTestMethod extends TestMethodResult

  private[havarunner] trait TestLifecycle
  private[havarunner] object FailedInstantiation extends TestLifecycle
  private[havarunner] case class CompletedCycle(testInstance: TestInstance, testMethodResult: TestMethodResult) extends TestLifecycle
}
