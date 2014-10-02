package com.whitepages

import java.util.concurrent.{TimeUnit, TimeoutException}

import com.whitepages.ComplexTask._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scalaz.{-\/, _}
import scalaz.concurrent.Future

class TestErrorHandling extends FlatSpec with Matchers {

  "Plan Step" should "pass warnings in success case" in {
    val success = ComplexTask[String, Warning](("hi", List(Warning("warning1"))))
    val failure: ComplexTask[String, Warning] = new ComplexTask(Future.now(-\/(new IllegalArgumentException("why")), List(Warning("warning2"))))
//    val res = join2(success, joinOpt1(failure))
    val res = seq2(success, toOpt(failure, t => Warning("k", Some(t))))
    val (resOut, warnings) = res.run
    resOut._1 should equal("hi")
    resOut._2 should equal(None)
    warnings.map(_.msg).toSet should have size(3) //warning1, warning2, java.lang.IlleaglArgumentException as warning
  }

  "Plan Step" should "pass warnings in failure case" in {
    val success = ComplexTask[String, Warning](("hi", List(Warning("warning1"))))
    val ex = new IllegalArgumentException("why")
    val failure: ComplexTask[String, Warning] = new ComplexTask(Future.schedule((-\/(ex), List(Warning("warning2"))), 50.milliseconds))
    val res = seq2(success, failure)
    val (resOut, warnings) = res.attemptRun
    resOut should equal(-\/(ex))
    warnings.map(_.msg).toSet should equal(Set("warning1", "warning2"))
  }

  "timeout" should "work" in {
    val timeoutF: Future[(Throwable \/ String, List[Warning])] = Future.schedule((\/-("hi"), List.empty[Warning]), FiniteDuration(1, TimeUnit.SECONDS))
    val timeoutStep: ComplexTask[String, Warning] = new ComplexTask(timeoutF)
    val timedStep = timeoutStep.timed(FiniteDuration(10, TimeUnit.MILLISECONDS))
    val (resOut, warnings) = timedStep.attemptRun
    resOut match {
      case -\/(ex: TimeoutException) => /* nop */
      case s => fail(s"expected -\/(TimeoutException), got: $s")
    }
    warnings should be('empty)
  }

  "split warnings" should "not duplicate" in {
    val success = ComplexTask[String, Warning](("hi", List(Warning("warning1"))))
    val a = success.map(s => s"$s there")
    val b = success.map(s => s"oh $s")
    val prep = seq2(a, b).map { case (aVal, bVal) => s"$aVal, $bVal" }
    val (resOut, warnings) = prep.run
    warnings should have size(1)
  }

  "future" should "flatmap" in {
    val long = Future.schedule((), 1.second)
    val start = Future.schedule(5, 10.milliseconds)
    var x = 0
    val f: Future[Int] = Future.async { cb => start.runAsync { i => cb(i) } }
    val mapped = f.flatMap { i => Future.now { x = i + 1; 12 } }
    mapped.runAsync(println)
    long.run
    x should equal(6)
  }
}
