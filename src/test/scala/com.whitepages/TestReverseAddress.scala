package com.whitepages

import scalaz.{\/-, -\/}

import org.scalatest.{FlatSpec, Matchers}
import shapeless._
import scala.concurrent.duration._
import scalaz._
import shapeless.contrib.scalaz.sequence

import scalaz.-\/
import scalaz.concurrent.{Task, Future}
import scalaz.\/._
import ComplexTask._

case class Warning(msg: String, exOpt: Option[Throwable] = None)

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

  case class DSOutputCassifiedAddresses(addressOpt: Option[String]) {
    def isFuzzy: Boolean = true
  }
  case class DSOutputDictionaryAndRoots(roots: List[String], dictionary: String, totalResultSize: Option[Int] = None)

  def start(): ComplexTask[String, Warning] = ComplexTask("hi", Nil)
  def getAddressDigest(a: String) = ComplexTask(DSOutputCassifiedAddresses(Some(a)), Nil)
  def getAddressDigestLong(a: String) = new ComplexTask(Future.schedule((\/-(DSOutputCassifiedAddresses(Some(a))), Nil), 500.milliseconds))
  def getWindows(a: String) = ComplexTask[Int, Warning](5, Nil)
  def getWindowsFail(a: String) = ComplexTask({ throw new Exception("hi"); 5 }, Nil)
  def getLocByDigest(a: String) = ComplexTask(DSOutputDictionaryAndRoots(List("root"), "dictionary"), Nil)
  def getLocByDigestFail(a: String) = ComplexTask({ throw new Exception("getLocByDigestFail")
                                                 DSOutputDictionaryAndRoots(List("root"), "dictionary")}, Nil)
  def prepareResults(dictAndRoots: DSOutputDictionaryAndRoots, isFuzzy: Boolean, numWindows: Option[Int]) =
    ComplexTask((dictAndRoots.roots, isFuzzy, numWindows, dictAndRoots.dictionary), Nil)


  case class ValidationException(msg: String) extends Throwable

  "total success" should "work" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigest(startAddr)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get)}
      val numWindows = getWindows(startAddr)
      seq3(cassRes, dictOut, toOpt(numWindows, t => Warning("hi", Some(t)))) flatMap {
        case (cassVal, dictVal, numWindowsVal) => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
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
      val cassRes = getAddressDigest(startAddr)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get)}
      val numWindows = getWindowsFail(startAddr)
      seq3(cassRes, dictOut, toOpt(numWindows, t => Warning("ok", Some(t)))) flatMap {
        case (cassVal, dictVal, numWindowsVal) => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
      }
    }

    val (resOut, warnings) = res.run
    resOut match {
      case (List("root"), true, None, "dictionary") => println("yay") /* nop */
      case s => fail(s"did not expect: $s")
    }
    warnings should have size(1)
  }

  "getLocByDigest failure" should "fail everything" in {
    val res = start.flatMap { startAddr =>
      val cassRes = getAddressDigest(startAddr)
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigestFail(cassAddr.addressOpt.get)}
      val numWindows = getWindowsFail(startAddr)
      seq3(cassRes, dictOut, toOpt(numWindows, t => Warning("s", Some(t)))) flatMap {
        case (cassVal, dictVal, numWindowsVal) => prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
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
      val cassRes = getAddressDigestLong(startAddr).timed(1.second) // takes 500 milliseconds
      val dictOut = cassRes.flatMap { cassAddr => getLocByDigest(cassAddr.addressOpt.get).timed(50.milliseconds) }
      val numWindows = getWindows(startAddr).timed(1.second)
      seq3(cassRes, dictOut, toOpt(numWindows, t => Warning("why", Some(t)))) flatMap {
        case (cassVal, dictVal, numWindowsVal)=> prepareResults(dictVal, cassVal.isFuzzy, numWindowsVal)
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
