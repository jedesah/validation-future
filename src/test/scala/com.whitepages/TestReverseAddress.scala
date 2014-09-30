package com.whitepages

import java.util.concurrent.TimeUnit

import shapeless._

import scalaz.{\/-, -\/}

import org.scalatest.{FlatSpec, Matchers}
import shapeless._
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.concurrent.duration._
import scalaz._
import shapeless.contrib.scalaz.sequence

import scalaz.-\/
import scalaz.concurrent.{Task, Future}
import scalaz.\/._
import PlanStep._

class TestReverseAddress extends FlatSpec with Matchers {
  def ensureAddress(cassifiedAddresses: DSOutputCassifiedAddresses, warnings: List[Warning]) = {
    if (!cassifiedAddresses.addressOpt.isDefined) (-\/(ValidationException("addressOpt must be defined!")), warnings)
    else                                          (\/-(cassifiedAddresses), warnings)
  }

  def ensureOneRoot(dictAndRoots: DSOutputDictionaryAndRoots, warnings: List[Warning]) = dictAndRoots match {
    case dsr if dsr.roots.isEmpty && dsr.totalResultSize.getOrElse(0) == 0 =>
      // create ephemeral version
      val ephemeral = dsr
      (\/-(ephemeral), warnings :+ Warning("created Ephemeral"))
    case m =>
      (\/-(m), warnings)
  }

  trait Address
  trait Dictionary
  case class DSOutputCassifiedAddresses(addressOpt: Option[String]) {
    def isFuzzy: Boolean = true
  }
  case class DSOutputDictionaryAndRoots(roots: List[String], dictionary: String, totalResultSize: Option[Int] = None)

  def start(): PlanStep[String] = PlanStep("hi", Nil)
  def getAddressDigest(a: String) = PlanStep(DSOutputCassifiedAddresses(Some(a)), Nil)
  def getAddressDigestLong(a: String) = new PlanStep(Future.schedule((\/-(DSOutputCassifiedAddresses(Some(a))), Nil), 500.milliseconds))
  def getWindows(a: String) = PlanStep(5, Nil)
  def getWindowsFail(a: String) = PlanStep({ throw new Exception("hi"); 5 }, Nil)
  def getLocByDigest(a: String) = PlanStep(DSOutputDictionaryAndRoots(List("root"), "dictionary"), Nil)
  def getLocByDigestFail(a: String) = PlanStep({ throw new Exception("getLocByDigestFail"); DSOutputDictionaryAndRoots(List("root"), "dictionary")}, Nil)
  def prepareResults(dictAndRoots: DSOutputDictionaryAndRoots, isFuzzy: Boolean, numWindows: Option[Int]) = PlanStep((dictAndRoots.roots, isFuzzy, numWindows, dictAndRoots.dictionary), Nil)


  case class ValidationException(msg: String) extends Throwable

  "total success" should "work" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigest(startAddr).validateWith(ensureAddress)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get)}.validateWith(ensureOneRoot)
      val numWindows = getWindows(startAddr)
      sequence(cassRes :: dictOut :: toOpt(numWindows) :: HNil) flatMap {
        case cassVal :: dictVal :: numWindowsVal :: HNil => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
      }
    }

    val (resOut, warnings) = res.run
    resOut match {
      case (List("root"), true, Some(5), "dictionary") => println("yay") /* nop */
      case s => fail(s"did not expect: $s")
    }
    warnings should be('empty)
  }

  "getWindows failure" should "still succeed" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigest(startAddr).validateWith(ensureAddress)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get)}.validateWith(ensureOneRoot)
      val numWindows = getWindowsFail(startAddr)
      sequence(cassRes :: dictOut :: toOpt(numWindows) :: HNil) flatMap {
        case cassVal :: dictVal :: numWindowsVal :: HNil => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
      }
    }

    val (resOut, warnings) = res.run
    resOut match {
      case (List("root"), true, None, "dictionary") => println("yay") /* nop */
      case s => fail(s"did not expect: $s")
    }
    warnings should be('empty)
  }

  "getLocByDigest failure" should "fail everything" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigest(startAddr).validateWith(ensureAddress)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigestFail(cassAddr.addressOpt.get)}.validateWith(ensureOneRoot)
      val numWindows = getWindowsFail(startAddr)
      sequence(cassRes :: dictOut :: toOpt(numWindows) :: HNil) flatMap {
        case cassVal :: dictVal :: numWindowsVal :: HNil => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
      }
    }

    val (either, warnings) = res.attemptRun
    either match {
      case -\/(e) => e.getMessage should equal("getLocByDigestFail")
      case s => fail(s"did not expect: $s")
    }
    warnings should be('empty)
  }

  // Note that it is crucial that the .timed on getLocByDigest takes place within the flatMap
  "stacked timings" should "work" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigestLong(startAddr).validateWith(ensureAddress).timed(1.second) // takes 500 milliseconds
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get).timed(50.milliseconds) }.validateWith(ensureOneRoot)
      val numWindows = getWindows(startAddr).timed(1.second)
      sequence(cassRes :: dictOut :: toOpt(numWindows) :: HNil) flatMap {
        case cassVal :: dictVal :: numWindowsVal :: HNil => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
      }
    }

    val timedRes = res.timed(5.seconds)
    val (either, warnings) = timedRes.attemptRun
    either match {
      case \/-(_) => println("yay") /* nop */
      case s => fail(s"did not expect: $s")
    }
    warnings should be('empty)
  }

}
