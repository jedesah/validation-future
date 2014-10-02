package com.whitepages

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}

import shapeless.{HNil, Id, HList}
import shapeless.contrib.scalaz.{Apply2, Sequencer}
import scala.concurrent.{Future => SFuture}
import scalaz.syntax.monad._

import scala.concurrent.duration.FiniteDuration
import scalaz.Free.Trampoline
import scalaz.concurrent._
import java.util.concurrent.{TimeoutException, TimeUnit, ExecutorService}
import scalaz.\/._
import scalaz._

case class Warning(msg: String, exOpt: Option[Throwable] = None)
case class TimeoutFuture(timeout: Future[Unit])

class PlanStep[+A](val get: Future[(Throwable \/ A, List[Warning])]) {

//  val get: Future[(Throwable \/ A, List[Warning])] = Future.Async { cb =>
//    in.runAsync(out => cb(out))
//    overallTimeoutOpt.map(t => t.timeout.runAsync(_ => cb((-\/(new TimeoutException), Nil))))
//  }

  // allow direct access to warnings with map-like behavior.
  // TODO: also allow access to warnings in failure state?
  def validateWith[B >: A](validate: (B, List[Warning]) => (Throwable \/ B, List[Warning])): PlanStep[B] =
    new PlanStep(get map {
      case e @ (-\/(_), _) => e
      case (\/-(a), warnings) =>
        try { validate(a, warnings) } catch { case t: Throwable => (-\/(t), warnings) }
    })

  // add timeout to get
  def timed(timeout: FiniteDuration) = {
    new PlanStep(get.timed(timeout).map {
      case -\/(t) => (-\/(t), Nil)
      case \/-(success) => success
    })
  }

  def flatMap[B](f: A => PlanStep[B]): PlanStep[B] =
    new PlanStep(get flatMap {
      case (-\/(e), warnings) => Future.now(-\/(e), warnings)
      case (\/-(a), warnings) => PlanStep.Try((f(a), Nil)) match {
        case (-\/(e), newWarnings) => Future.now((-\/(e), warnings ++ newWarnings))
        case (\/-(step), newWarnings) => {
          val f = step.get
          f.map{ case (result, warnings3) => (result, warnings ++ newWarnings ++ warnings3) }
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
  def toZFuture[A](f: SFuture[(Throwable \/ A, List[Warning])]): Future[(Throwable \/ A, List[Warning])] = {
    Future.async { cb =>
      f.onSuccess { case success => cb(success) }
      f.onFailure { case failure => cb((-\/(failure), Nil)) }
    }
  }

  def seq1[A](a: PlanStep[A]): PlanStep[A] = a

  def seq2[A, B](a: PlanStep[A], b: PlanStep[B]): PlanStep[(A, B)] = {
    new PlanStep(Future.Async { cb =>
      val interrupt = new AtomicBoolean(false)
      var resultA: (A, List[Warning]) = null
      var resultB: (B, List[Warning]) = null
      val togo = new AtomicInteger(2)

      def tryComplete = {
        if (togo.decrementAndGet() == 0) {
          cb((\/-(resultA._1, resultB._1), resultA._2 ++ resultB._2))
        } else {
          Trampoline.done(())
        }
      }

      def tryFailure(e: (-\/[Throwable], List[Warning])): Trampoline[Unit] = {
        @annotation.tailrec
        def firstFailure: Boolean = {
          val current = togo.get
          if (current > 0) {
            if (togo.compareAndSet(current,0)) true
            else firstFailure
          }
          else false
        }

        if (firstFailure)
          cb(e) *> Trampoline.delay { interrupt.set(true); () }
        else
          Trampoline.done(())
      }

      val handleA: ((Throwable \/ A, List[Warning])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultA = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      val handleB: ((Throwable \/ B, List[Warning])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultB = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      a.get.listenInterruptibly(handleA, interrupt)
      b.get.listenInterruptibly(handleB, interrupt)
    })

  }

  def seq3[A, B, C](a: PlanStep[A], b: PlanStep[B], c: PlanStep[C]): PlanStep[(A, B, C)] = {
    new PlanStep(Future.Async { cb =>
      val interrupt = new AtomicBoolean(false)
      var resultA: (A, List[Warning]) = null
      var resultB: (B, List[Warning]) = null
      var resultC: (C, List[Warning]) = null
      val togo = new AtomicInteger(2)

      def tryComplete = {
        if (togo.decrementAndGet() == 0) {
          cb((\/-(resultA._1, resultB._1, resultC._1), resultA._2 ++ resultB._2 ++ resultC._2))
        } else {
          Trampoline.done(())
        }
      }

      def tryFailure(e: (-\/[Throwable], List[Warning])): Trampoline[Unit] = {
        @annotation.tailrec
        def firstFailure: Boolean = {
          val current = togo.get
          if (current > 0) {
            if (togo.compareAndSet(current,0)) true
            else firstFailure
          }
          else false
        }

        if (firstFailure)
          cb(e) *> Trampoline.delay { interrupt.set(true); () }
        else
          Trampoline.done(())
      }

      val handleA: ((Throwable \/ A, List[Warning])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultA = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      val handleB: ((Throwable \/ B, List[Warning])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultB = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      val handleC: ((Throwable \/ C, List[Warning])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultC = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      a.get.listenInterruptibly(handleA, interrupt)
      b.get.listenInterruptibly(handleB, interrupt)
      c.get.listenInterruptibly(handleC, interrupt)
    })

  }

  def toOpt[A](step: PlanStep[A]): PlanStep[Option[A]] = {
    step.map(Some(_)) handleWith { case t: Throwable =>
      new PlanStep(Future.now((\/-(Option.empty[A]), List(Warning(s"`$t`: (caused optional step failure)", Some(t))))))
    }
  }

  def joinOpt1[A](a: PlanStep[A]): PlanStep[Option[A]] = toOpt(a)

  def joinOpt2[A, B](a: PlanStep[A], b: PlanStep[B]): PlanStep[(Option[A], Option[B])] = {
    val (aOpt, bOpt) = (toOpt(a), toOpt(b))
    aOpt.flatMap { aVal => bOpt.map { bVal => (aVal, bVal) } }
  }

  def joinOpt3[A, B, C](a: PlanStep[A], b: PlanStep[B], c: PlanStep[C]): PlanStep[(Option[A], Option[B], Option[C])] = {
    val (aOpt, bOpt, cOpt) = (toOpt(a), toOpt(b), toOpt(c))
    aOpt.flatMap { aVal => bOpt.flatMap { bVal => cOpt.map { cVal => (aVal, bVal, cVal) } } }
  }

  def join1[A](a: PlanStep[A]): PlanStep[A] = a

  def join2[A, B](a: PlanStep[A], b: PlanStep[B]): PlanStep[(A, B)] = {
    a.flatMap { aVal => b.map { bVal => (aVal, bVal) } }
  }

  def join3[A, B, C](a: PlanStep[A], b: PlanStep[B], c: PlanStep[C]): PlanStep[(A, B, C)] = {
    a.flatMap { aVal => b.flatMap { bVal => c.map{ cVal => (aVal, bVal, cVal) } } }
  }

  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => (A, List[Warning]))(implicit pool: ExecutorService = Strategy.DefaultExecutorService): PlanStep[A] =
    new PlanStep(Future(Try(a))(pool))

  def Salvage[A](a: => (A, List[Warning]))(implicit pool: ExecutorService = Strategy.DefaultExecutorService): PlanStep[A] =
    new PlanStep(Future(Try(a))(pool))

   implicit val planStepInstance: Monad[PlanStep] = new Monad[PlanStep] {
     def point[A](a: => A) = new PlanStep(Future.delay(Try(a, Nil)))

     def bind[A, B](fa: PlanStep[A])(f: A => PlanStep[B]): PlanStep[B] = {
       fa flatMap f
     }
   }

  def fail(e: Throwable): PlanStep[Nothing] = new PlanStep[Nothing](Future.now(-\/(e), Nil))

  def now[A](a: A): PlanStep[A] = new PlanStep(Future.now(\/-(a), Nil))

  def Try[A](a: => (A, List[Warning])): (Throwable \/ A, List[Warning]) =
    try {
      val (executedA, warnings) = a
      (\/-(executedA), warnings)
    } catch { case e: Throwable => (-\/(e), Nil)}
}
