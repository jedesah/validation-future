package com.whitepages

import java.util.concurrent.TimeUnit

import shapeless._
import syntax.std.tuple._
import scalaz._
import scalaz.std.list._
import scalaz.std.tuple.tuple2Monoid
import scalaz.std.anyVal._

object Planexecutor extends App {
  import scalaz.concurrent.Future
  import scalaz.concurrent.Task
  import scala.concurrent.duration._
  import shapeless.contrib.scalaz.sequence
  import shapeless.contrib.scalaz.Sequencer

  def startThing() = ComplexTask[Int, (List[String], Int)]((5, (Nil, 5)))
  def one(startThing: Int) = ComplexTask[Int, (List[String], Int)]((startThing * 8, (Nil, 10)))
  def two(otherThing: Int) = ComplexTask[Int, (List[String], Int)]((otherThing / 1, (Nil, 20)))
  def three(oneThing: Int, twoThing: Int) = ComplexTask[String, (List[String], Int)]((oneThing.toString + twoThing, (Nil, 100)))
  def four(oneThing: Int, twoThing: Int) = ComplexTask[Boolean, (List[String], Int)]((oneThing == twoThing, (Nil, 3)))
  def prepareResult(a: String, b: Boolean) = ComplexTask[String, (List[String],Int)]((a + b, (Nil, 40)))

//  def sequence[L <: HList, M <: HList](mandatory: L, optional: M)(implicit seq: Sequencer[L]): Sequencer[L]#Out :: M =
//    scalaz.sequence(mandatory)(seq)
  import ComplexTask._

  val other = Monad[({type n[a] = ComplexTask[a, (List[String], Int)]})#n].map(startThing)((x: Int) => x)

  val a = startThing.flatMap{ thing =>
    one(thing).flatMap{ otherThing =>
      two(otherThing).flatMap{ secondThing =>
        val one = three(otherThing, secondThing)
        val two = four(otherThing, secondThing)
//        sequence(one :: two :: toOpt(one, _ => ()) :: toOpt(two, _ => ()) :: HNil).map {
//          case o :: t :: oOpt :: tOpt :: HNil => tOpt
//        }
//        sequence(one :: two :: HNil).flatMap{case aa :: bb :: HNil => prepareResult(aa, bb)}
//        join2(join2(one, two), joinOpt2(one, two)).flatMap {
//          case ((rOne, rTwo), (rThree, rFour)) =>
//            prepareResult(rOne, rTwo)
//        }
        two
      }
    }
  }

//  planSequence(one(1) :: two(2) :: HNil, three(3, 4) :: four(4, 5) :: HNil): PlanStep[Int :: Int :: HNil :: Option[Int] :: Option[Int] :: HNil]

//  val x = sequence(one(1) :: two(2) :: HNil)
//  println("sequence: " + x)

  val b = a.attemptRun
//  println(b)

  // 2 problems
  // How do each futures collect their own warnings and non-critical failures
  // How do non-critical failures not stop the whole thing
  // Salvaging
  // any critical failure should trigger an immediate shutdown of the system

  def fibo(n: Int): Int = n match {
    case 0 => 1
    case 1 => 1
    case _ => fibo(n - 1) + fibo(n - 2)
  }

  val f = Future.apply(try { fibo(100) } catch { case _: Throwable => println("caught!!!!!!!!!"); 10 }).timed(1.second)

//  println("hello")
  val result = f.run
  println(result)


  val list = one(1) :: two(2) :: HNil



  scalaz.concurrent.Strategy.DefaultTimeoutScheduler.shutdown()
}
