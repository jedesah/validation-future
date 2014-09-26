package com.whitepages

import shapeless._
import syntax.std.tuple._
import scalaz._

object Planexecutor extends App {
  import scalaz.concurrent.Future
  import scalaz.concurrent.Task
  import scala.concurrent.duration._
  import shapeless.contrib.scalaz
  import shapeless.contrib.scalaz.sequence
  import shapeless.contrib.scalaz.Sequencer

  def start() = Task(5)
  def one(startThing: Int) = Task(startThing * 8)
  def two(otherThing: Int) = Task(otherThing / 1)
  def three(oneThing: Int, twoThing: Int): Task[String] = Task(oneThing.toString + twoThing)
  def four(oneThing: Int, twoThing: Int): Task[Boolean] = Task(oneThing == twoThing)
  def prepareResult(a: String, b: Boolean): Task[String] = Task(a + b)

//  def sequence[L <: HList, M <: HList](mandatory: L, optional: M)(implicit seq: Sequencer[L]): Sequencer[L]#Out :: M =
//    scalaz.sequence(mandatory)(seq)

  val a = start.flatMap{ thing =>
    one(thing).flatMap{ otherThing =>
      two(otherThing).flatMap{ secondThing =>
        val one = three(otherThing, secondThing)
        val two = four(otherThing, secondThing)
        sequence(one :: two :: HNil).flatMap{case one :: two :: HNil => prepareResult(one, two)}
      }
    }
  }

  val b = a.attemptRun
  println(b)

  // 2 problems
  // How do each futures collect their own warnings and non-critical failures
  // How do non-critical failures not stop the whole thing

  def fibo(n: Int): Int = n match {
    case 0 => 1
    case 1 => 1
    case _ => fibo(n - 1) + fibo(n - 2)
  }

  val f = Future.apply(fibo(100)).timed(1.second)

  println("hello")
  val result = f.run
  println(result)

  scalaz.concurrent.Strategy.DefaultTimeoutScheduler.shutdown()
}
