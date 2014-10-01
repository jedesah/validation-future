package com.whitepages

import java.util.concurrent.atomic.AtomicBoolean

import scalaz.{\/-, -\/}

import org.scalatest.{FlatSpec, Matchers}
import shapeless._
import scala.concurrent.duration._
import scalaz._
import shapeless.contrib.scalaz.sequence

import scalaz.-\/
import scalaz.concurrent.{Task, Future}
import scalaz.\/._
import PlanStep._

class TestSequence extends FlatSpec with Matchers {
  "sequence" should "fail fast" in {
    val start = System.currentTimeMillis

    val a = new PlanStep(Future.schedule((\/-(1), Nil), 5.seconds))
    val b = new PlanStep(Future.now((-\/(new Exception("hi")), Nil)))

    val c = PlanStep.seq2(a, b)
    val (either, warnings) = c.attemptRun

    either match {
      case -\/(e) => e.getMessage should equal("hi")
      case s => s"did not expect result: $s"
    }

    val elapsed = System.currentTimeMillis() - start
    println(elapsed)
    assert(elapsed < 1000, "elapsed time should be well under one second")
  }

  "sequence" should "work in success case" in {
    val a = new PlanStep(Future.schedule((\/-(1), List(Warning("1"))), 10.milliseconds))
    val b = new PlanStep(Future.now((\/-("hi"), List(Warning("2"), Warning("3")))))

    val c = PlanStep.seq2(a, b)
    val (cOut, warnings) = c.run

    cOut should equal(1, "hi")
    warnings.map(_.msg) should equal(List("1", "2", "3"))
//    val d = sequence(a :: b :: HNil).map { case aOut :: bOut :: HNil => (aOut, bOut) }
//
  }

  "multiple callbacks" should "only honor the first one" in {
    val start = System.currentTimeMillis()

    val short = Future.schedule("short", 1.milliseconds)
    val long = Future.schedule("long", 100.milliseconds)
    val a: Future[String] = Future.Async { cb =>
      short.runAsync(s => cb(s))
      long.runAsync(s => cb(s))
    }
    val res = a.run

    val elapsed = System.currentTimeMillis - start
    res should equal("short")
    assert(elapsed < 100, "elapsed time should be < 100 milliseconds")
  }
}
