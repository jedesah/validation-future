package com.whitepages

import java.util.concurrent.{TimeoutException, TimeUnit}

import org.scalatest.{FlatSpec, Matchers}
import shapeless._
import scalaz._
import shapeless.contrib.scalaz.sequence

import scalaz.-\/
import scalaz.concurrent.{Task, Future}
import scalaz.\/._
import PlanStep._
import scala.concurrent.duration._

class TestErrorHandling extends FlatSpec with Matchers {

  "Plan Step" should "pass warnings in success case" in {
    val success = PlanStep("hi", List(Warning("warning1")))
    val failure: PlanStep[String] = new PlanStep(Future.now(-\/(new IllegalArgumentException("why")), List(Warning("warning2"))))
//    val res = join2(success, joinOpt1(failure))
    val res = sequence(success :: toOpt(failure) :: HNil)
    val (resOut, warnings) = res.run
    resOut.head should equal("hi")
    resOut.tail.head should equal(None)
    warnings.map(_.msg).toSet should have size(3) //warning1, warning2, java.lang.IlleaglArgumentException as warning
  }

  "Plan Step" should "pass warnings in failure case" in {
    val success = PlanStep("hi", List(Warning("warning1")))
    val ex = new IllegalArgumentException("why")
    val failure: PlanStep[String] = new PlanStep(Future.now(-\/(ex), List(Warning("warning2"))))
    val res = sequence(success :: failure :: HNil)
    val (resOut, warnings) = res.attemptRun
    resOut should equal(-\/(ex))
    warnings.map(_.msg).toSet should equal(Set("warning1", "warning2"))
  }

  "timeout" should "work" in {
    val timeoutF: Future[(Throwable \/ String, List[Warning])] = Future.schedule((\/-("hi"), List.empty[Warning]), FiniteDuration(1, TimeUnit.SECONDS))
    val timeoutStep: PlanStep[String] = new PlanStep(timeoutF)
    val timedStep = timeoutStep.timed(FiniteDuration(10, TimeUnit.MILLISECONDS))
    val (resOut, warnings) = timedStep.attemptRun
    resOut match {
      case -\/(ex: TimeoutException) => /* nop */
      case s => fail(s"expected -\/(TimeoutException), got: $s")
    }
    warnings should be('empty)
  }

  "split warnings" should "not duplicate" in { // TODO: give warnings ids on creation and use those to make unique?
    val success = PlanStep("hi", List(Warning("warning1")))
    val a = success.map(s => s"$s there")
    val b = success.map(s => s"oh $s")
    val prep = sequence(a :: b :: HNil).map { case aVal :: bVal :: HNil => s"$aVal, $bVal" }
    val (resOut, warnings) = prep.run
    warnings should have size(1)
  }

  "future" should "flatmap" in {
    val long = Future.schedule((), 3.second)
    val start = Future.schedule(5, 10.milliseconds)
    var x = 0
    val f: Future[Int] = Future.async { cb => start.runAsync { i => cb(i) } }
    val mapped = f.flatMap { i => Future.now { x = i + 1; 12 } }
    mapped.runAsync(println)
    long.run
    x should equal(6)
  }
}
