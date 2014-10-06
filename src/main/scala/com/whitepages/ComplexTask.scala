package com.whitepages

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future => SFuture}
import scalaz.Free.Trampoline
import scalaz._
import scalaz.concurrent._
import scalaz.syntax.monad._

case class TimeoutFuture(timeout: Future[Unit])

class ComplexTask[+A, +E](val get: Future[(Throwable \/ A, List[E])]) {

//  val get: Future[(Throwable \/ A, List[E])] = Future.Async { cb =>
//    in.runAsync(out => cb(out))
//    overallTimeoutOpt.map(t => t.timeout.runAsync(_ => cb((-\/(new TimeoutException), List.empty[E]))))
//  }

  // add timeout to get
  def timed(timeout: FiniteDuration) = {
    new ComplexTask(get.timed(timeout).map {
      case -\/(t) => (-\/(t), List.empty[E])
      case \/-(success) => success
    })
  }

  def flatMap[B, E1 >: E](f: A => ComplexTask[B, E1]) =
    new ComplexTask[B, E1](get flatMap {
      case (-\/(e), warnings) => Future.now(-\/(e), warnings)
      case (\/-(a), warnings) => ComplexTask.Try((f(a), List.empty[E1])) match {
        case (-\/(e), newWarnings) => Future.now((-\/(e), warnings ++ newWarnings))
        case (\/-(step), newWarnings) => {
          val f = step.get
          f.map { case (result, warnings3) => (result, warnings ++ newWarnings ++ warnings3) }
        }
      }
    })

  def map[B](f: A => B): ComplexTask[B, E] =
    new ComplexTask(get map { case (result, warnings) =>
      result match {
        case -\/(e) => (-\/(e), warnings)
        case \/-(a) => {
          val (result, newWarnings) = ComplexTask.Try((f(a), List.empty[E]))
          (result, warnings ++ newWarnings)
        }
      }
    })

  // If IntelliJ is telling you this does not compile. Ignore it!
  def attempt: ComplexTask[Throwable \/ A, E] =
    new ComplexTask[Throwable \/ A, E](get map {
      case (-\/(e), warnings) => (\/-(-\/(e)), warnings)
      case (\/-(a), warnings) => (\/-(\/-(a)), warnings)
    })

  def onFinish[E1 >: E](f: Option[Throwable] => ComplexTask[Unit, E1]): ComplexTask[A, E] = ???

  def handle[B>:A](f: PartialFunction[Throwable,B]): ComplexTask[B, E] =
    handleWith(f andThen ComplexTask.now)

  def handleWith[B>:A,E1>:E](f: PartialFunction[Throwable,ComplexTask[B, E1]]): ComplexTask[B, E1] =
    attempt flatMap {
      case -\/(e) => f.lift(e) getOrElse ComplexTask.fail(e)
      case \/-(a) => ComplexTask.now(a)
    }

  def mapFailure(f: PartialFunction[Throwable, Throwable]): ComplexTask[A, E] = {
    attempt flatMap {
      case -\/(e) => ComplexTask.fail(f(e))
      case \/-(a) => ComplexTask.now(a)
    }
  }

  def or[A1>:A,E1>:E](t2: ComplexTask[A1, E1]): ComplexTask[A1, E1] =
    new ComplexTask[A1, E1](this.get flatMap {
      case (-\/(_), warnings) => t2.get
      case (\/-(a), warnings) => Future.now((\/-(a), warnings))
    })

  def run[E1>:E]: (A, List[E1]) = get.run match {
    case (-\/(e), warnings) => throw e
    case (\/-(a), warnings) => (a, warnings.toSet.toList)
  }

  def attemptRun[E1>:E]: (Throwable \/ A, List[E1]) = {
    get.run
  }

}

object ComplexTask {
  def toZFuture[A, E](f: SFuture[(Throwable \/ A, List[E])]): Future[(Throwable \/ A, List[E])] = {
    Future.async { cb =>
      f.onSuccess { case success => cb(success) }
      f.onFailure { case failure => cb((-\/(failure), List.empty[E])) }
    }
  }

  def seq1[A, E](a: ComplexTask[A, E]): ComplexTask[A, E] = a

  def seq2[A, B, E](a: ComplexTask[A, E], b: ComplexTask[B, E]): ComplexTask[(A, B), E] = {
    new ComplexTask[(A, B), E](Future.Async { cb =>
      val interrupt = new AtomicBoolean(false)
      var resultA: (A, List[E]) = null
      var resultB: (B, List[E]) = null
      val togo = new AtomicInteger(2)

      def tryComplete = {
        if (togo.decrementAndGet() == 0) {
          cb((\/-(resultA._1, resultB._1), resultA._2 ++ resultB._2))
        } else {
          Trampoline.done(())
        }
      }

      def tryFailure(e: (-\/[Throwable], List[E])): Trampoline[Unit] = {
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

      val handleA: ((Throwable \/ A, List[E])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultA = (success, warnings)
          tryComplete
        case (-\/(e), warnings) =>
          val bWarnings = Option(resultB).map(_._2).getOrElse(Nil)
          tryFailure((-\/(e), warnings ++ bWarnings))
      }

      val handleB: ((Throwable \/ B, List[E])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultB = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => {
          val aWarnings = Option(resultA).map(_._2).getOrElse(Nil)
          tryFailure((-\/(e), warnings ++ aWarnings))
        }
      }

      a.get.listenInterruptibly(handleA, interrupt)
      b.get.listenInterruptibly(handleB, interrupt)
    })

  }

  def seq3[A, B, C, E](a: ComplexTask[A, E], b: ComplexTask[B, E], c: ComplexTask[C, E]): ComplexTask[(A, B, C), E] = {
    new ComplexTask[(A, B, C), E](Future.Async { cb =>
      val interrupt = new AtomicBoolean(false)
      var resultA: (A, List[E]) = null
      var resultB: (B, List[E]) = null
      var resultC: (C, List[E]) = null
      val togo = new AtomicInteger(3)

      def tryComplete = {
        if (togo.decrementAndGet() == 0) {
          cb((\/-(resultA._1, resultB._1, resultC._1), resultA._2 ++ resultB._2 ++ resultC._2))
        } else {
          Trampoline.done(())
        }
      }

      def tryFailure(e: (-\/[Throwable], List[E])): Trampoline[Unit] = {
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

      val handleA: ((Throwable \/ A, List[E])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultA = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      val handleB: ((Throwable \/ B, List[E])) => Trampoline[Unit] = {
        case (\/-(success), warnings) =>
          resultB = (success, warnings)
          tryComplete
        case (-\/(e), warnings) => tryFailure((-\/(e), warnings))
      }

      val handleC: ((Throwable \/ C, List[E])) => Trampoline[Unit] = {
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

  def toOpt[A, E](step: ComplexTask[A, E], f: Throwable => E): ComplexTask[Option[A], E] = {
    step.map(Some(_)) handleWith { case t: Throwable =>
      new ComplexTask(Future.now((\/-(Option.empty[A]), List(f(t)))))
    }
  }

//  def joinOpt1[A](a: PlanStep[A]): PlanStep[Option[A]] = toOpt(a)
//
//  def joinOpt2[A, B](a: PlanStep[A], b: PlanStep[B]): PlanStep[(Option[A], Option[B])] = {
//    val (aOpt, bOpt) = (toOpt(a), toOpt(b))
//    aOpt.flatMap { aVal => bOpt.map { bVal => (aVal, bVal) } }
//  }
//
//  def joinOpt3[A, B, C, E](a: PlanStep[A, E], b: PlanStep[B, E], c: PlanStep[C, E]): PlanStep[(Option[A], Option[B], Option[C]), E] = {
//    val (aOpt, bOpt, cOpt) = (toOpt(a), toOpt(b), toOpt(c))
//    aOpt.flatMap { aVal => bOpt.flatMap { bVal => cOpt.map { cVal => (aVal, bVal, cVal) } } }
//  }

  def join1[A, E](a: ComplexTask[A, E]): ComplexTask[A, E] = a

  def join2[A, B, E](a: ComplexTask[A, E], b: ComplexTask[B, E]): ComplexTask[(A, B), E] = {
    a.flatMap { aVal => b.map { bVal => (aVal, bVal) } }
  }

  def join3[A, B, C, E](a: ComplexTask[A, E], b: ComplexTask[B, E], c: ComplexTask[C, E]): ComplexTask[(A, B, C), E] = {
    a.flatMap { aVal => b.flatMap { bVal => c.map{ cVal => (aVal, bVal, cVal) } } }
  }

  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A, E](a: => (A, List[E]))(implicit pool: ExecutorService = Strategy.DefaultExecutorService): ComplexTask[A, E] =
    new ComplexTask(Future(Try(a))(pool))

  def Salvage[A, E](a: => (A, List[E]))(implicit pool: ExecutorService = Strategy.DefaultExecutorService): ComplexTask[A, E] =
    new ComplexTask(Future(Try(a))(pool))


  implicit val unitPlanStep = planStepInstance[Unit]

  implicit def planStepInstance[E] = {
    new Monad[({ type n[a] = ComplexTask[a, E] })#n] {
      def point[A](a: => A) = new ComplexTask[A, E](Future.delay(Try(a, List.empty[E])))

      def bind[A, B](fa: ComplexTask[A, E])(f: A => ComplexTask[B, E]): ComplexTask[B, E] = {
        fa flatMap f
      }
    }
  }
//
//   implicit val planStepInstance: Monad[PlanStep] = new Monad[PlanStep] {
//     def point[A](a: => A) = new PlanStep(Future.delay(Try(a, List())))
//
//     def bind[A, B](fa: PlanStep[A])(f: A => PlanStep[B]): PlanStep[B] = {
//       fa flatMap f
//     }
//   }

  def fail[E](e: Throwable, es: List[E] = List.empty[E])= new ComplexTask[Nothing, E](Future.now(-\/(e), es))

  def now[A, E <: Throwable](a: A): ComplexTask[A, E] = new ComplexTask(Future.now(\/-(a), List.empty[E]))

  def Try[A, E](a: => (A, List[E])): (Throwable \/ A, List[E]) =
    try {
      val (executedA, warnings) = a
      (\/-(executedA), warnings)
    } catch { case e: Throwable => (-\/(e), List.empty[E])}
}
