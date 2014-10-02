package com.whitepages

import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scalaz.{-\/, \/-}
import scalaz.concurrent.Future

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
  }

}
