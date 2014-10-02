package com.whitepages

import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scalaz.{-\/, \/-}
import scalaz.concurrent.Future

class TestSequence extends FlatSpec with Matchers {
  "sequence" should "fail fast" in {
    val start = System.currentTimeMillis

    val a = new ComplexTask(Future.schedule((\/-(1), Nil), 5.seconds))
    val b = new ComplexTask(Future.now((-\/(new Exception("hi")), Nil)))

    val c = ComplexTask.seq2(a, b)
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
    val a = new ComplexTask(Future.schedule((\/-(1), List(Warning("1"))), 10.milliseconds))
    val b = new ComplexTask(Future.now((\/-("hi"), List(Warning("2"), Warning("3")))))

    val c = ComplexTask.seq2(a, b)
    val (cOut, warnings) = c.run

    cOut should equal(1, "hi")
    warnings.map(_.msg) should equal(List("1", "2", "3"))
  }

}
