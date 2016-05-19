package controllers

import shapeless._
import syntax.typeable._
import nat._
import syntax.sized._
import ops.nat._
import LT._

import ops.{ hlist, coproduct }
import scala.language.experimental.macros
import scala.annotation.{ StaticAnnotation, tailrec }
import scala.reflect.macros.{ blackbox, whitebox }

import app._
import models._
import java.util.Base64
import java.nio.charset.StandardCharsets
import play.api.libs.json._
import play.api.libs.ws._
import ch.bfh.unicrypt.math.algebra.multiplicative.classes.GStarModSafePrime
import java.math.BigInteger
import scala.concurrent.{Future, Promise}
import scala.util.{Try, Success, Failure}
//import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import scala.collection.mutable.Queue
import java.util.concurrent.atomic.AtomicInteger
import java.sql.Timestamp
import akka.http.scaladsl.model._
import com.github.nscala_time.time.Imports._
import services.BoardConfig
import utils.Response

trait GetType {  
  def getElectionTypeCreated[W <: Nat : ToInt] (election: Election[W, Created]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Created]"
  }
  
  def getElectionCreated[W <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Created]"
  }
  
  def getElectionTypeShares[W <: Nat : ToInt, T <: Nat : ToInt] (election: Election[W, Shares[T]]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Shares[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionShares[W <: Nat : ToInt, T <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Shares[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionTypeCombined[W <: Nat : ToInt] (election: Election[W, Combined]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Combined]"
  }
  
  def getElectionCombined[W <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Combined]"
  }
  
  def getElectionTypeVotes[W <: Nat : ToInt] (election: Election[W, Votes]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Votes[${election.state.addVoteIndex}]]"
  }
  
  def getElectionVotes[W <: Nat : ToInt](numVotes: Int) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Votes[${numVotes}]]"
  }
  
  def getElectionTypeVotesStopped[W <: Nat : ToInt] (election: Election[W, VotesStopped]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.VotesStopped]"
  }
  
  def getElectionVotesStopped[W <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.VotesStopped]"
  }
  
  def getElectionTypeMixing[W <: Nat : ToInt, T <: Nat : ToInt] (election: Election[W, Mixing[T]]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Mixing[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionMixing[W <: Nat : ToInt, T <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Mixing[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionTypeMixed[W <: Nat : ToInt] (election: Election[W, Mixed]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Mixed]"
  }
  
  def getElectionMixed[W <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Mixed]"
  }
  
  def getElectionTypeDecryptions[W <: Nat : ToInt, T <: Nat : ToInt] (election: Election[W, Decryptions[T]]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Decryptions[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionDecryptions[W <: Nat : ToInt, T <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Decryptions[shapeless.nat._${toInt[T]}]]"
  }
  
  def getElectionTypeDecrypted[W <: Nat : ToInt] (election: Election[W, Decrypted]) : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Decrypted]"
  }
  
  def getElectionDecrypted[W <: Nat : ToInt]() : String = {
    s"app.Election[shapeless.nat._${toInt[W]},app.Decrypted]"
  }
}

class ElectionSubscriber[W <: Nat : ToInt](val uid : String) extends GetType {
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  println("GG ElectionSubscriber::constructor")
  private var map = Map[String, Any]()
  
  private def getOrAdd(key: String, value: Any) : Any = {
    map.synchronized {
      map.get(key) match {
        case Some(any) =>
          any
        case None =>
          map += (key -> value)
          value
      }
    }
  }
  
  def push[B <: ElectionState](election : Election[W, B], electionType: String) = {
    println("GG ElectionSubscriber::push electionType " + electionType)
    val promise = Promise[Election[W, B]]()
    val any = getOrAdd(electionType, promise)
    Try {
      any.asInstanceOf[Promise[Election[W, B]]]
    } map { p =>
      if (p.isCompleted) {
        println("Error: trying to complete an already completed future")
      } else {
        p.success(election)
      }
    }
  }
  
  private def pull[B <: ElectionState](electionType: String): Future[Election[W, B]] = {
    println("GG ElectionSubscriber::pull electionType " + electionType)
    val realPromise = Promise[Election[W, B]]()
    Future {
      val promise = Promise[Election[W, B]]()
      val any = getOrAdd(electionType, promise)
      Try {
        any.asInstanceOf[Promise[Election[W, B]]]
      } match {
        case Success(p) =>
          realPromise.completeWith(p.future)
        case Failure(e) =>
          realPromise.failure(e)
      }
    }
    realPromise.future
  }
  
  def create() : Future[Election[W, Created]] = 
    pull[Created](getElectionCreated[W])
  
  def startShares() : Future[Election[W, Shares[_0]]] = 
    pull[Shares[_0]](getElectionShares[W, _0])
  
  def addShare[T <: Nat : ToInt](): Future[Election[W, Shares[T]]] =
    pull[Shares[T]](getElectionShares[W, T])
    
  def combineShares() : Future[Election[W, Combined]]  = 
    pull[Combined](getElectionCombined[W])
    
  def startVotes() : Future[Election[W, Votes]] =
    pull[Votes](getElectionVotes[W](0))
    
  def addVote(numVotes: Int) : Future[Election[W, Votes]]  = 
    pull[Votes](getElectionVotes[W](numVotes))
  
  def addVotes(numVotes: Int) : Future[Election[W, Votes]]  = 
    pull[Votes](getElectionVotes[W](numVotes))
  
  def stopVotes() : Future[Election[W, VotesStopped]]  = 
    pull[VotesStopped](getElectionVotesStopped[W])
  
  def startMixing() : Future[Election[W, Mixing[_0]]]  = 
    pull[Mixing[_0]](getElectionMixing[W, _0])
  
  def addMix[T <: Nat : ToInt](): Future[Election[W, Mixing[T]]]  = 
    pull[Mixing[T]](getElectionMixing[W, T])
  
  def stopMixing() : Future[Election[W, Mixed]]  = 
    pull[Mixed](getElectionMixed[W])
  
  def startDecryptions() : Future[Election[W, Decryptions[_0]]]  = 
    pull[Decryptions[_0]](getElectionDecryptions[W, _0])
  
  def addDecryption[T <: Nat : ToInt](): Future[Election[W, Decryptions[T]]]  = 
    pull[ Decryptions[T]](getElectionDecryptions[W, T])
  
  def combineDecryptions() : Future[Election[W, Decrypted]]  = 
    pull[Decrypted](getElectionDecrypted[W])
}

object ElectionDTOData {
  val REGISTERED = "registered"
  val CREATED = "created"
  val CREATE_ERROR = "create_error"
  val STARTED = "started"
  val STOPPED = "stopped"
  val TALLY_OK = "tally_ok"
  val TALLY_ERROR = "tally_error"
  val RESULTS_OK = "results_ok"
  val DOING_TALLY = "doing_tally"
  val RESULTS_PUB = "results_pub"
}

class ElectionDTOData(val id: Long, val numAuth: Int) {
  
  private var state = initDTO()
  def apply() = state
  
  private def genAuthArray(): Array[String] = {
    var authArray : Array[String] = Array()
    if(numAuth > 1) {
      for(index <- 2 to numAuth) {
        authArray = authArray :+ ("auth" + index)
      }
    }
    authArray
  }
  
  private def initDTO(): ElectionDTO = {
    val startDate = new Timestamp(2015, 1, 27, 16, 0, 0, 1)
    ElectionDTO(
        id,
        ElectionConfig(
            id,
            "simple",
            "auth1",
            genAuthArray(),
            "Election title",
            "Election description",
            Array(
                Question(
                    "Question 0",
                    "accordion",
                    1,
                    1,
                    1,
                    "Question title",
                    true,
                    "plurality-at-large",
                    "over-total-valid-votes",
                    Array(
                      Answer(
                          0,
                          "",
                          "",
                          0,
                          Array(),
                          "voting option A"
                      ),
                      Answer(
                          1,
                          "",
                          "",
                          1,
                          Array(),
                          "voting option B"
                      )
                    )
                )
            ),
            startDate,
            startDate,
            ElectionPresentation(
                "",
                "default",
                Array(),
                "",
                None
            ),
            false,
            None
        ),
        ElectionDTOData.REGISTERED,
        startDate,
        startDate,
        None,
        None,
        None,
        false
    )
  }
  
  def setState(newState: String) {
    state = 
      ElectionDTO(
          state.id,
          state.configuration,
          newState,
          state.startDate,
          state.endDate,
          state.pks,
          state.results,
          state.resultsUpdated,
          state.real
      )
  }
  
  def setPublicKeys[W <: Nat : ToInt](combined: Election[W, Combined]) {
    
    val jsPk : JsValue = 
      Json.arr(Json.obj( 
          "q" -> combined.state.cSettings.group.getOrder().toString(),
          "p" -> combined.state.cSettings.group.getModulus().toString(),
          "y" -> combined.state.publicKey,
          "g" -> combined.state.cSettings.generator.getValue().toString()
      ))
      
    state = 
      ElectionDTO(
          state.id,
          state.configuration,
          state.state,
          state.startDate,
          state.endDate,
          Some(jsPk.toString()),
          state.results,
          state.resultsUpdated,
          state.real
      )
  }
}

class ElectionStateMaintainer[W <: Nat : ToInt](val uid : String)
  extends ElectionJsonFormatter
  with GetType
  with ErrorProcessing
{
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  println("GG ElectionStateMaintainer::constructor")
  private val subscriber = new ElectionSubscriber[W](uid)
  private val dto = new ElectionDTOData(uid.toLong, toInt[W])
  
  
  def getElectionInfo() : ElectionDTO = {
    dto()
  }
  
  def startShares(in: Election[W, Created]) : Election[W, Shares[_0]] = {
      println("GG ElectionStateMaintainer::startShares")
      new Election[W, Shares[_0]](Shares[_0](List[(String, String)]().sized(0).get, in.state))
  }
  
  def addShare[T <: Nat : ToInt](in: Election[W, Shares[T]], proverId: String, keyShare: String) : Election[W, Shares[Succ[T]]] = {
    println(s"GG ElectionStateMaintainer::addShare")
    new Election[W, Shares[Succ[T]]](Shares[Succ[T]](in.state.shares :+ (proverId, keyShare), in.state))
  }
  
  def combineShares(in: Election[W, Shares[W]], publicKey: String) : Election[W, Combined] = {
    println(s"GG ElectionStateMaintainer::combineShares")
    new Election[W, Combined](Combined(publicKey, in.state))
  }
  
  def startVotes(in: Election[W, Combined]) : Election[W, Votes] = {
    println(s"GG ElectionStateMaintainer::startVotes")
    new Election[W, Votes](Votes(List[String](), 0, in.state))
  }
  
  def addVotes(in: Election[W, Votes], votes : List[String]) : Election[W, Votes] = {
    println(s"GG ElectionStateMaintainer::addVotes")
    new Election[W, Votes](Votes(votes ::: in.state.votes, in.state.addVoteIndex + 1, in.state))
  }
  
  def stopVotes(in: Election[W, Votes], lastAddVoteIndex: Int, date: com.github.nscala_time.time.Imports.DateTime) : Election[W, VotesStopped] = {
    println(s"GG ElectionStateMaintainer::stopVotes")
    new Election[W, VotesStopped](VotesStopped(lastAddVoteIndex, in.state, date))
  }
  
  def startMixing(in: Election[W, VotesStopped]) : Election[W, Mixing[_0]] = {
    println(s"GG ElectionStateMaintainer::startMixing")
    new Election[W, Mixing[_0]](Mixing[_0](List[ShuffleResultDTO]().sized(0).get, in.state))
  }
  
  def addMix[T <: Nat : ToInt](in: Election[W, Mixing[T]], mix: ShuffleResultDTO) : Election[W, Mixing[Succ[T]]] = {
    println(s"GG ElectionStateMaintainer::addMix")
    new Election[W, Mixing[Succ[T]]](Mixing[Succ[T]](in.state.mixes :+ mix, in.state))
  }
  
  def stopMixing(in: Election[W, Mixing[W]]) : Election[W, Mixed] = {
    println(s"GG ElectionStateMaintainer::stopMixing")
    new Election[W, Mixed](Mixed(in.state))
  }
  
  def startDecryptions(in: Election[W, Mixed]) : Election[W, Decryptions[_0]] = {
    println(s"GG ElectionStateMaintainer::startDecryptions")
    new Election[W, Decryptions[_0]](Decryptions[_0](List[PartialDecryptionDTO]().sized(0).get, in.state))
  }
  
  def addDecryption[T <: Nat : ToInt](in: Election[W, Decryptions[T]], decryption: PartialDecryptionDTO) : Election[W, Decryptions[Succ[T]]] = {
    println(s"GG ElectionStateMaintainer::addDecryption")
    new Election[W, Decryptions[Succ[T]]](Decryptions[Succ[T]](in.state.decryptions :+ decryption, in.state))
  }
  
  def combineDecryptions(in: Election[W, Decryptions[W]], decrypted: Seq[String]) : Election[W, Decrypted] = {
    println(s"GG ElectionStateMaintainer::combineDecryptions")
    new Election[W, Decrypted](Decrypted(decrypted, in.state))
  }
  
  def pushDecrypted(jsDecrypted: JsDecrypted) = {
    println("GG ElectionStateMaintainer::pushDecrypted")
    val futureDecryption = subscriber.addDecryption[W]()
    futureDecryption onComplete {
      case Success(decryption) =>
        val election = combineDecryptions(decryption, jsDecrypted.decrypted)
        subscriber.push(election, getElectionTypeDecrypted(election))
      case Failure(err) =>
        println(s"Future error: ${getMessageFromThrowable(err)}")
    }
  }
  
  def pushDecryptions(jsDecryptions: JsDecryptions) = {
    println("GG ElectionStateMaintainer::pushDecryptions")
    if(jsDecryptions.level < 0 || jsDecryptions.level > 9) {
      println(s"Error, mismatched level (should be 1 to 9): ${jsDecryptions.level}")
    } else {
      jsDecryptions.level match {
        case 1 =>
          val futureDecryption = subscriber.addDecryption[_0]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 2 =>
          val futureDecryption = subscriber.addDecryption[_1]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 3 =>
          val futureDecryption = subscriber.addDecryption[_2]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 4 =>
          val futureDecryption = subscriber.addDecryption[_3]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 5 =>
          val futureDecryption = subscriber.addDecryption[_4]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 6 =>
          val futureDecryption = subscriber.addDecryption[_5]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 7 =>
          val futureDecryption = subscriber.addDecryption[_6]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 8 =>
          val futureDecryption = subscriber.addDecryption[_7]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case _ =>
          val futureDecryption = subscriber.addDecryption[_8]()
          futureDecryption onComplete {
            case Success(decryption) =>
              val election = addDecryption(decryption, jsDecryptions.decryption)
              subscriber.push(election, getElectionTypeDecryptions(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
      }
    }
  }
  
  def pushStartDecryptions() {
    println("GG ElectionStateMaintainer::pushStartDecryptions")
    val futureStopMixing = subscriber.stopMixing()
    futureStopMixing onComplete {
      case Success(stop) =>
        val election = startDecryptions(stop)
        subscriber.push(election, getElectionTypeDecryptions(election))
      case Failure(err) =>
        println(s"Future error: ${getMessageFromThrowable(err)}")
    }
  }
  
  def pushStopMixing() {
    println("GG ElectionStateMaintainer::pushStopMixing")
    val futureMix = subscriber.addMix[W]()
    futureMix onComplete {
      case Success(mix) =>
        val election = stopMixing(mix)
        subscriber.push(election, getElectionTypeMixed(election))
      case Failure(err) =>
        println(s"Future error: ${getMessageFromThrowable(err)}")
    }
  }
  
  def pushMixing(jsMixing: JsMixing) {
    println("GG ElectionStateMaintainer::pushMixing")
    if(jsMixing.level < 1 || jsMixing.level > 9) {
      println(s"Error, mismatched level (should be 1 to 9): ${jsMixing.level}")
    } else {
      jsMixing.level match {
        case 1 =>
          val futureMix = subscriber.addMix[_0]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 2 =>
          val futureMix = subscriber.addMix[_1]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 3 =>
          val futureMix = subscriber.addMix[_2]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 4 =>
          val futureMix = subscriber.addMix[_3]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 5 =>
          val futureMix = subscriber.addMix[_4]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 6 =>
          val futureMix = subscriber.addMix[_5]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 7 =>
          val futureMix = subscriber.addMix[_6]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case 8 =>
          val futureMix = subscriber.addMix[_7]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case _ =>
          val futureMix = subscriber.addMix[_8]()
          futureMix onComplete {
            case Success(mix) => 
              val election = addMix(mix, jsMixing.mixes)
              subscriber.push(election, getElectionTypeMixing(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
      }
    }
  }
  
  def pushStartMixing() {
    println("GG ElectionStateMaintainer::pushStartMixing")
    val futureStopVotes = subscriber.stopVotes()
    futureStopVotes onComplete {
      case Success(stopped) => 
        val election = startMixing(stopped)
        subscriber.push(election, getElectionTypeMixing(election))
      case Failure(err) =>
        println(s"Future error: ${getMessageFromThrowable(err)}")
    }
  }
  
  def pushVotesStopped(jsVotesStopped: JsVotesStopped) {
    println("GG ElectionStateMaintainer::pushVotesStopped")
    if(jsVotesStopped.lastAddVoteIndex < 0) {
      println(s"ERROR on pushVotesStopped: lastAddVoteIndex is negative but it must be non-negative: ${jsVotesStopped.lastAddVoteIndex}")
    } else {
      Try {
        com.github.nscala_time.time.Imports.DateTime.parse(jsVotesStopped.date)
      } match {
        case Success(date) =>
          val futureVotes = subscriber.addVotes(jsVotesStopped.lastAddVoteIndex)
          futureVotes onComplete {
            case Success(votes) => 
              val election = stopVotes(votes, jsVotesStopped.lastAddVoteIndex, date)
              subscriber.push(election, getElectionTypeVotesStopped(election))
            case Failure(err) =>
              println(s"Future error: ${getMessageFromThrowable(err)}")
          }
        case Failure(err) =>
          println(s"Try Date ${jsVotesStopped.date.toString} parse error: ${getMessageFromThrowable(err)}")
      }
    }
  }
  
  def pushVotes(jsVotes: JsVotes) {
    println("GG ElectionStateMaintainer::pushVotes")
    if(0 == jsVotes.addVoteIndex) {
      val futureCombined = subscriber.combineShares()
      futureCombined onComplete {
        case Success(combined) => 
          val election = startVotes(combined)
          subscriber.push(election, getElectionTypeVotes(election))
        case Failure(err) =>
          println(s"Future error: ${getMessageFromThrowable(err)}")
      }
    } else if(0 < jsVotes.addVoteIndex) {
      val futureVotes = subscriber.addVotes(jsVotes.addVoteIndex - 1)
      futureVotes onComplete {
        case Success(votes) => 
          val election = addVotes(votes, jsVotes.votes)
          subscriber.push(election, getElectionTypeVotes(election))
        case Failure(err) =>
          println(s"Future error: ${getMessageFromThrowable(err)}")
      }
    } else if(0 > jsVotes.addVoteIndex) {
      println(s"ERROR on pushVotes: addVoteIndex is negative but it must be non-negative: ${jsVotes.addVoteIndex}")
    }
  }
  
  def pushCombined(jsCombined: JsCombined) {
    println("GG ElectionStateMaintainer::pushCombined")
    val futureShare = subscriber.addShare[W]()
    futureShare onComplete {
      case Success(share) => 
        val election = combineShares(share, jsCombined.publicKey)
        subscriber.push(election, getElectionTypeCombined(election))
        dto.setPublicKeys(election)
      case Failure(err) =>
        println(s"Future error: ${getMessageFromThrowable(err)}")
    }
  }
  
  def pushShares(jsShares: JsShares) {
    println("GG ElectionStateMaintainer:pushShares")
    val maxLevel = ToInt[W].apply()
    if(jsShares.level == 0) {
      val futureCreate = subscriber.create()
      futureCreate onComplete {
        case Success(cc) =>
          val election = startShares(cc)
          subscriber.push(election, getElectionTypeShares(election))
        case Failure(e) =>
          println(s"Future error: ${getMessageFromThrowable(e)}")
      }
    } else if (jsShares.level > 0 && jsShares.level <= maxLevel) {
      jsShares.level match {
        case 1 => 
          val futureShare = subscriber.startShares()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 2 => 
          val futureShare = subscriber.addShare[_1]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 3 => 
          val futureShare = subscriber.addShare[_2]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 4 => 
          val futureShare = subscriber.addShare[_3]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 5 => 
          val futureShare = subscriber.addShare[_4]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 6 => 
          val futureShare = subscriber.addShare[_5]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 7 => 
          val futureShare = subscriber.addShare[_6]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case 8 => 
          val futureShare = subscriber.addShare[_7]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
        case _ => 
          val futureShare = subscriber.addShare[_8]()
          futureShare onComplete { 
            case Success(share) =>
              val election = addShare(share, jsShares.shares._1, jsShares.shares._2)
              subscriber.push(election, getElectionTypeShares(election))
            case Failure(e) => 
              println(s"Future error: ${getMessageFromThrowable(e)}")
          }
      }
    } else {
      println("Error, mismatched levels")
    }
  }
  
  def pushCreate(jsElection: JsElection, uid: String) {
    val group = GStarModSafePrime.getInstance(new BigInteger(jsElection.state.cSettings.group))
    val cSettings = CryptoSettings(group, group.getDefaultGenerator())
    if (jsElection.level == ToInt[W].apply()) {
      val election = 
        new Election[W, Created](Created(jsElection.state.id, cSettings, uid))
      subscriber.push(election, getElectionTypeCreated(election))
    } else {
      println("Error, mismatched levels")
    }
  }
  
  def push(post: Post) {
    println("GG ElectionStateMaintainer:push")
    val jsMsg = Json.parse(post.message)
    if(post.user_attributes.section == "election" &&
         post.user_attributes.group == "create") {
        jsMsg.validate[JsElection] match {
          case jSeqPost: JsSuccess[JsElection] =>
            pushCreate(jSeqPost.get, post.board_attributes.index)
            dto.setState(ElectionDTOData.REGISTERED)
          case e: JsError => 
            println(s"\ElectionStateMaintainer JsError error: ${e} message ${post.message}")
        }
      } else if (post.user_attributes.section == "election" &&
         post.user_attributes.group == uid) {
        jsMsg.validate[JsMessage] match {
          case a: JsSuccess[JsMessage] =>
            val jsMessage = a.get
            jsMessage.messageType match {
              case "Shares" =>
                jsMessage.message.validate[JsShares] match {
                  case b: JsSuccess[JsShares] =>
                    pushShares(b.get)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "Combined" =>
                jsMessage.message.validate[JsCombined] match {
                  case b: JsSuccess[JsCombined] =>
                    val combined = b.get
                    pushCombined(combined)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "Votes" =>
                jsMessage.message.validate[JsVotes] match {
                  case b: JsSuccess[JsVotes] =>
                    pushVotes(b.get)
                    dto.setState(ElectionDTOData.STARTED)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "VotesStopped" =>
                jsMessage.message.validate[JsVotesStopped] match {
                  case b: JsSuccess[JsVotesStopped] =>
                    pushVotesStopped(b.get)
                    dto.setState(ElectionDTOData.STOPPED)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "StartMixing" =>
                if(JsNull == jsMessage.message) {
                  pushStartMixing()
                    dto.setState(ElectionDTOData.DOING_TALLY)
                } else {
                  println(s"Error: StartMixing : message is not null: message ${post.message}")
                }
              case "Mixing" =>
                jsMessage.message.validate[JsMixing] match {
                  case b: JsSuccess[JsMixing] =>
                    pushMixing(b.get)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "Mixed" =>
                if(JsNull == jsMessage.message) {
                  pushStopMixing()
                } else {
                  println(s"Error: StopMixing : message is not null: message ${post.message}")
                }
              case "StartDecryptions" =>
                if(JsNull == jsMessage.message) {
                  pushStartDecryptions()
                } else {
                  println(s"Error: StartDecryptions : message is not null: message ${post.message}")
                }
              case "Decryptions" =>
                jsMessage.message.validate[JsDecryptions] match {
                  case b: JsSuccess[JsDecryptions] =>
                    pushDecryptions(b.get)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case "Decrypted" =>
                jsMessage.message.validate[JsDecrypted] match {
                  case b: JsSuccess[JsDecrypted] =>
                    pushDecrypted(b.get)
                    dto.setState(ElectionDTOData.RESULTS_OK)
                  case e: JsError =>
                    println(s"JsError error: ${e} message ${post.message}")
                }
              case _ => ;
                println(s"ElectionStateMaintainer JsMessage type error: ${jsMessage.messageType}")
            }
          case e: JsError => 
            println(s"ElectionStateMaintainer error: ${e} message ${post.message}")
        }
      } else {
            println("ElectionStateMaintainer else")
      }
  }
  
  def getSubscriber() : ElectionSubscriber[W] = {
    subscriber
  }
}

class MaintainerWrapper(level: Int, uid: String) {
  val maintainer =  if(1 == level) {
    new ElectionStateMaintainer[_1](uid)
  } else if(2 == level) {
    new ElectionStateMaintainer[_2](uid)
  } else if(3 == level) {
    new ElectionStateMaintainer[_3](uid)
  } else if(4 == level) {
    new ElectionStateMaintainer[_4](uid)
  } else if(5 == level) {
    new ElectionStateMaintainer[_5](uid)
  } else if(6 == level) {
    new ElectionStateMaintainer[_6](uid)
  } else if(7 == level) {
    new ElectionStateMaintainer[_7](uid)
  } else if(8 == level) {
    new ElectionStateMaintainer[_8](uid)
  } else if(9 == level) {
    new ElectionStateMaintainer[_9](uid)
  } else {
    throw new Error(s"level is $level and should be limited to [1-9]")
  }
  
  def push(post: Post) {
    maintainer.push(post)
  }
  
  def getSubscriber() = {
    maintainer.getSubscriber()
  }
  
  def getElectionInfo() = {
    maintainer.getElectionInfo()
  }
}

trait PostOffice extends ElectionJsonFormatter with Response
{  
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  // post index counter
  private var index : Long = 0
  private var queue = Queue[Option[Post]]()
  // the first parameter is the uid
  private var electionMap = Map[Long, MaintainerWrapper]()
  // list of callbacks to be called when a new election is created
  private var callbackQueue = Queue[String => Unit]()
    
  def getElectionInfo(electionId: Long) : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    Future {
      electionMap.get(electionId) match {
        case Some(electionWrapper) =>
           promise.success(HttpResponse(status = 200, entity = Json.stringify(response( electionWrapper.getElectionInfo() )) ))
         
        case None =>
          promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Election $electionId not found", ErrorCodes.EO_ERROR)) ))
      }
    }
    promise.future
  }
  
  def add(post: Post) {
    queue.synchronized {
      println("GG PostOffice:add")
      Try {
        post.board_attributes.index.toLong
      } map { postIndex =>
        if(postIndex < index) {
          println("Error: old post")
        } else if(postIndex >= index) {
          if(postIndex < index + queue.size) {
            queue.get((postIndex - index).toInt) map { x =>
              x match {
                case Some(p) =>
                  println("Error: duplicated post")
                case None =>
                  queue.update((postIndex - index).toInt, Some(post))
              }
            }
          } else {
            queue ++= List.fill((postIndex - (index + (queue.size).toLong)).toInt)(None)
            queue += Some(post)
          }
        }
      }
      remove()
    }
  }
  
  private def send(post: Post) {
    if("election" == post.user_attributes.section) {
      val group : String = post.user_attributes.group
      val electionIdStr = post.board_attributes.index
      if("create" == group) {
        Try { electionIdStr.toLong } match {
          case Success(electionId) =>
            electionMap.get(electionId) match {
              case Some(electionWrapper) =>
                println(s"Error: duplicated Election Id: ${electionId}")
              case None =>
                val jsMsg = Json.parse(post.message)
                jsMsg.validate[JsElection] match {
                  case jSeqPost: JsSuccess[JsElection] =>
                    val maintainer = new MaintainerWrapper(jSeqPost.get.level, electionIdStr)
                    maintainer.push(post)
                    electionMap += (electionId -> maintainer)
                    callbackQueue.synchronized {
                      callbackQueue foreach { func =>
                        Future { func(electionIdStr) }
                      }
                    }
                  case e: JsError => 
                    println("Error: JsCreate format error")
                }
            }
          case Failure(e) =>
            println(s"Error: Election Id is not a number (but It should be): ${electionIdStr}")
        }
      } else {
        Try { group.toLong } match {
          case Success(electionId) => 
            electionMap.get(electionId) match {
              case Some(electionWrapper) => 
                electionWrapper.push(post)
              case None =>
                println(s"Error: Election Id not found in db: ${electionId}")
            }
          case Failure(e) => 
            println(s"Error: group is not a number : ${group}")
        }
      }
    } else {
      println("Error: post is not an election")
    }
  }
  
  private def remove() {
    println("GG PostOffice::remove")
    var head : Option[Post] = None
    queue.synchronized {
      if(queue.size > 0) {
        queue.head match {
          case Some(post) =>
            // TODO: here we should check the post hash and signature
            head = queue.dequeue
            index = index + 1
          case None => ;
        }
      }
    }
    head match {
      case Some(post) =>
        send(post)
      case None => ;
    }
  }
  
  def getSubscriber(uid : String) = {
    Try { uid.toLong } match {
      case Success(electionId) =>
        electionMap.get(electionId) match {
          case Some(electionWrapper) => 
            electionWrapper.getSubscriber()
          case None =>
            throw new scala.Error(s"Error subscribing: Election Id not found in db: ${electionId}")
        }
      case Failure(e) =>
        throw new scala.Error(s"Error subscribing: Election id is not a number: {uid}")
    }
  }
  
  def addElectionCreationListener(callback: (String) => Unit) {
    callbackQueue.synchronized {
      callbackQueue += callback
    }
  }
}


object BoardReader
  extends ElectionJsonFormatter
  with PostOffice
  with FiwareJSONFormatter
  with BoardJSONFormatter
  with ErrorProcessing
{

  var subscriptionId = ""
  private val futureSubscriptionId = getSubscriptionId(BoardPoster.getWSClient )
  
  futureSubscriptionId onComplete {
    case Success(id) =>
      subscriptionId = id
    case Failure(err) => 
      throw err
  }
  
  
  def init() {
  }
  
  private def unsubscribe(id: String, ws: WSClient) = {
    println("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF unsubscribe")
    Router.getPort() map { port =>
      val unsubsReq = UnsubscribeRequest(id, s"http://localhost:${port}/accumulate")
      println(Json.stringify(Json.toJson(unsubsReq)))
      val futureResponse: Future[WSResponse] = 
      ws.url(s"${BoardConfig.agoraboard.url}/bulletin_unsubscribe")
          .withHeaders(
            "Content-Type" -> "application/json",
            "Accept" -> "application/json")
          .post(Json.toJson(unsubsReq))
      futureResponse onComplete {
        case Success(noErr) =>
          println("Unsubscribe SUCCESS " + noErr)
        case Failure(err) =>
          println("Unsubscribe ERROR " + getMessageFromThrowable(err))
      }
    }
  }
  
  def getSubscriptionId(ws: WSClient) : Future[String] =  {
    val promise = Promise[String]
    Future {
      println("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF getSubscriptionId")
      Router.getPort() onComplete { 
        case Success(port) =>
          val acc = SubscribeRequest("election", "#", s"http://localhost:${port}/accumulate")
          val futureResponse: Future[WSResponse] = 
          ws.url(s"${BoardConfig.agoraboard.url}/bulletin_subscribe")
          .withHeaders(
            "Content-Type" -> "application/json",
            "Accept" -> "application/json")
          .post(Json.toJson(acc))
          
          futureResponse onComplete {
            case Success(response) =>
              promise.success(response.body)
              println(s"ElectionCreateSubscriber Success: ${response.body}")
            case Failure(err) =>
              println(s"ElectionCreateSubscriber Failure: ${getMessageFromThrowable(err)}")
              promise.failure(err)
          }
        case Failure(err) =>
          promise.failure(err)
      }
    }
    promise.future
  }
  
  def accumulate(bodyStr: String) : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    Future {
      val json = Json.parse(bodyStr)
      json.validate[AccumulateRequest] match {
        case sr: JsSuccess[AccumulateRequest] =>
          val accRequest = sr.get
          if (accRequest.subscriptionId == subscriptionId) {
            var jsonError: Option[String] = None
            val postSeq = accRequest.contextResponses flatMap {  x => 
              x.contextElement.attributes flatMap { y =>
                y.value.validate[Post] match {
                  case post: JsSuccess[Post] =>
                    Some(post.get)
                  case e: JsError =>
                    val str = "Accumulate has a None: this is not " +
                            s"a valid Post: ${y.value}! error: $json"
                    println(str)
                    jsonError = Some(str)
                    None
                  }
                }
             }
             jsonError match {
               case Some(e) =>
                 promise.success(HttpResponse(400, entity = e))
               case None => 
                 push(postSeq)
                 promise.success(HttpResponse(200, entity = s"{}"))
             }
          } else {
            if(futureSubscriptionId.isCompleted) {
              // Error, we are receiving subscription messages from a wrong subscription id
              promise.success(HttpResponse(400))
              // Remove subscription for that subscription id
              unsubscribe(accRequest.subscriptionId, BoardPoster.getWSClient)
            } else {
              futureSubscriptionId onComplete {
                case Success(id) =>
                  promise.completeWith(accumulate(bodyStr))
                case Failure(err) => 
                  promise.failure(err)
              }
            }
          }
       case e: JsError =>
         val errorText = s"Bad request: invalid AccumulateRequest json: $bodyStr\nerror: ${e}\n"
           println(errorText)
           promise.success(HttpResponse(400, entity = errorText))
      }
    }
    promise.future
  }
  
  def push(seqPost: Seq[Post]) = {
    seqPost foreach { post => 
      add(post)
    }
  } 
  
}