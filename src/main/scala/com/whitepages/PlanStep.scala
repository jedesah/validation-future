package com.whitepages

import shapeless.{HNil, Id, HList}
import shapeless.contrib.scalaz.{Apply2, Sequencer}

import scala.concurrent.duration.FiniteDuration
import scalaz.concurrent._
import java.util.concurrent.{TimeUnit, ExecutorService}
import scalaz.\/._
import scalaz._

case class Warning(msg: String, exOpt: Option[Throwable] = None)

class PlanStep[+A](val get: Future[(Throwable \/ A, List[Warning])]) {

//  def getValidated = get map {
//    case e @ (-\/(_), _) => e
//    case (\/-(a), warnings) =>
//      try { validate(a, warnings) } catch { case t: Throwable => (-\/(t), warnings) }
//  }

  def validateWith[B >: A](validate: (B, List[Warning]) => (Throwable \/ B, List[Warning])): PlanStep[B] =
    new PlanStep(get map {
      case e @ (-\/(_), _) => e
      case (\/-(a), warnings) =>
        try { validate(a, warnings) } catch { case t: Throwable => (-\/(t), warnings) }
    })

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
  def toOpt[A](step: PlanStep[A]): PlanStep[Option[A]] = {
    step.map(Some(_)) handle { case _: Throwable => Option.empty[A] }
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





object Storage {

  //  implicit val planStepInstance: Applicative[PlanStep] = new Applicative[PlanStep] {
  //    def ap[A,B](fa: => PlanStep[A])(f: => PlanStep[A => B]): PlanStep[B] = {
  //      new PlanStep(fa.get.map { case (result, warnings) =>
  //        result match {
  //          case -\/(e) => (-\/(e), warnings)
  //          case \/-(a) => {
  //            val x = f.get map { case (fResultFun, fWarnings) =>
  //              fResultFun match {
  //                case -\/(fE) => (-\/(fE), warnings ++ fWarnings)
  //                case \/-(fFun) => {
  //                  (\/-(fFun.apply(a)), warnings ++ fWarnings)
  //                }
  //              }
  //            }
  //            x.map { xf => PlanStep(xf) }
  //          }
  //        }
  //      }
  //      )
  //    }
  //  }
  //    def map[A, B](fa: PlanStep[A])(f: A => B): PlanStep[B] =
  //      new PlanStep(fa.get map { case (result, warnings) =>
  //        result match {
  //          case -\/(e) => (-\/(e), warnings)
  //          case \/-(a) => {
  //            val (result, newWarnings) = PlanStep.Try((f(a), Nil))
  //            (result, warnings ++ newWarnings)
  //          }
  //        }
  //      })

  //  implicit val planStepInstance: Nondeterminism[PlanStep] with Catchable[PlanStep] with MonadError[({type λ[α,β] = Task[β]})#λ,Throwable] = new Nondeterminism[Task] with Catchable[Task] with MonadError[({type λ[α,β] = Task[β]})#λ,Throwable] {
  //    val F = Nondeterminism[Future]
  //    def point[A](a: => A) = new Task(Future.delay(Try(a)))
  //    def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] =
  //      a flatMap f
  //    def chooseAny[A](h: Task[A], t: Seq[Task[A]]): Task[(A, Seq[Task[A]])] =
  //      new Task ( F.map(F.chooseAny(h.get, t map (_ get))) { case (a, residuals) =>
  //        a.map((_, residuals.map(new Task(_))))
  //      })
  //    override def gatherUnordered[A](fs: Seq[Task[A]]): Task[List[A]] = {
  //      new Task (F.map(F.gatherUnordered(fs.map(_ get)))(eithers =>
  //        Traverse[List].sequenceU(eithers)
  //      ))
  //    }
  //    def fail[A](e: Throwable): Task[A] = new Task(Future.now(-\/(e)))
  //    def attempt[A](a: Task[A]): Task[Throwable \/ A] = a.attempt
  //    def raiseError[A](e: Throwable): Task[A] = fail(e)
  //    def handleError[A](fa: Task[A])(f: Throwable => Task[A]): Task[A] =
  //      fa.handleWith { case t => f(t) }
  //  }

  //  def planSequence(one(1) :: two(2) :: HNil, three(3, 4) :: four(4, 5) :: HNil): PlanStep[Int :: Int :: HNil :: Option[Int] :: Option[Int] :: HNil]
  import shapeless.poly._

  //  object option extends (PlanStep ~> ({ type λ[α] = PlanStep[Option[α]] })#λ) {
  //    def apply[T](step: PlanStep[T]): PlanStep[Option[T]] = step.map { t => Some(t) } or { PlanStep((Option.empty[T], Nil)) }
  //  }
  //  object option extends (Id ~> ({ type λ[α] = PlanStep[Option[α]] })#λ) {
  //    def apply[T](step: T): PlanStep[Option[_]] = {
  //
  //      step match {
  //        case s: PlanStep[X] => s.map { t => Some(t) } or { PlanStep((Option.empty[X], Nil)) }
  //      }
  //    }
  ////  }
  //
  //  def sequenceOptional[L <: HList](in: L)(implicit seq: Sequencer[L]): seq.Out = {
  //    val inOpts = in map option
  //    seq(in)
  //  }

  import shapeless._
  import scalaz.syntax.apply._
  //
  //trait LowPrioritySequencerOptional {
  //  type Aux[L <: HList, Out0] = Sequencer[L] { type Out = Out0 }
  //
  //  implicit def consSequencerAux[FH, FT <: HList, OutT]
  //  (implicit
  //   un: Unapply[PlanStep, FH],
  //   st: Aux[FT, OutT],
  //   ap: Apply2[FH, OutT]
  //    ): Aux[FH :: FT, ap.Out] =
  //    new Sequencer[FH :: FT] {
  //      type Out = ap.Out
  //      def apply(in: FH :: FT): Out = ap(in.head, st(in.tail)) // un.TC.apply2(un(in.head), st(in.tail)) { _ :: _ }
  //    }
  //}
  //
  //object SequencerOptional extends LowPrioritySequencerOptional {
  //  implicit def nilSequencerAux: Aux[HNil, PlanStep[HNil]] =
  //    new Sequencer[HNil] {
  //      type Out = PlanStep[HNil]
  //      def apply(in: HNil): PlanStep[HNil] = Applicative[PlanStep].pure(HNil: HNil)
  //    }
  //
  //  implicit def singleSequencerAux[FH]
  //  (implicit
  //   un: Unapply[PlanStep, FH]
  //    ): Aux[FH :: HNil, un.M[un.A :: HNil]] =
  //    new Sequencer[FH :: HNil] {
  //      type Out = un.M[un.A :: HNil]
  //      def apply(in: FH :: HNil): Out = un.TC.map(un(in.head)) { _ :: HNil }
  //    }
  //}
}