package com.whitepages

import java.util.concurrent.ExecutorService

import scalaz.concurrent.Future
import scalaz.concurrent.Strategy
import scalaz.\/._
import scalaz.{\/, -\/, \/-}

case class Warning(msg: String)

class PlanStep[+A](val get: Future[(Throwable \/ A, List[Warning])]) {

  def flatMap[B](f: A => PlanStep[B]): PlanStep[B] =
    new PlanStep(get flatMap {
      case (-\/(e), warnings) => Future.now(-\/(e), warnings)
      case (\/-(a), warnings) => PlanStep.Try((f(a), Nil)) match {
        case (-\/(e), newWarnings) => Future.now((-\/(e), warnings ++ newWarnings))
        case (\/-(step), newWarnings) => {
          val f = step.get
          f.map{ case (result, warnings) => (result, warnings ++ newWarnings) }
        }
      }
    })

  def map[B](f: A => B): PlanStep[B] =
    new PlanStep(get map { case (result, warnings) =>
      result match {
        case -\/(e) => (-\/(e), warnings)
        case \/-(a) => {
          val (result, newWarnings) = PlanStep.Try((f(a), Nil))
          (result, warnings ++ newWarnings)
        }
      }
    })

  // If IntelliJ is telling you this does not compile. Ignore it!
  def attempt: PlanStep[Throwable \/ A] =
    new PlanStep[Throwable \/ A](get map {
      case (-\/(e), warnings) => (\/-(-\/(e)), warnings)
      case (\/-(a), warnings) => (\/-(\/-(a)), warnings)
    })

  def onFinish(f: Option[Throwable] => PlanStep[Unit]): PlanStep[A] = ???

  def handle[B>:A](f: PartialFunction[Throwable,B]): PlanStep[B] =
    handleWith(f andThen PlanStep.now)

  def handleWith[B>:A](f: PartialFunction[Throwable,PlanStep[B]]): PlanStep[B] =
    attempt flatMap {
      case -\/(e) => f.lift(e) getOrElse PlanStep.fail(e)
      case \/-(a) => PlanStep.now(a)
    }

  def or[B>:A](t2: PlanStep[B]): PlanStep[B] =
    new PlanStep(this.get flatMap {
      case (-\/(_), warnings) => t2.get
      case (\/-(a), warnings) => Future.now((\/-(a), warnings))
    })

  def run: (A, List[Warning]) = get.run match {
    case (-\/(e), warnings) => throw e
    case (\/-(a), warnings) => (a, warnings)
  }

  def attemptRun: (Throwable \/ A, List[Warning]) =
    get.run

}

object PlanStep {
  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => (A, List[Warning]))(implicit pool: ExecutorService = Strategy.DefaultExecutorService): PlanStep[A] =
    new PlanStep(Future(Try(a))(pool))

  def fail(e: Throwable): PlanStep[Nothing] = new PlanStep[Nothing](Future.now(-\/(e), Nil))

  def now[A](a: A): PlanStep[A] = new PlanStep(Future.now(\/-(a), Nil))

  def Try[A](a: => (A, List[Warning])): (Throwable \/ A, List[Warning]) =
    try {
      val (executedA, warnings) = a
      (\/-(executedA), warnings)
    } catch { case e: Throwable => (-\/(e), Nil)}
}
