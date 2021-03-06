/**
 * This file is part of agora-mixnet.
 * Copyright (C) 2015-2016  Agora Voting SL <agora@agoravoting.com>

 * agora-mixnet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.

 * agora-mixnet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with agora-mixnet.  If not, see <http://www.gnu.org/licenses/>.
**/

package director

import app._
import scala.util.{Try, Success, Failure}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import scala.concurrent.{blocking, Future, Promise}
import akka.http.scaladsl.server._
import akka.http.scaladsl.model._
import play.api.libs.json._
import shapeless._
import ops.nat._
import controllers._
import mpservice._
import utils._
import accumulator.BoardReader
import election._
import models._

class ElectionDirector[N <: Nat : ToInt](val totalVotes: Int)  
extends AbstractElectionDirector 
with Response
with HttpEntityToString
with ErrorProcessing
with EncryptionFormatter
{
  implicit val system = ActorSystem()
  implicit val executor = system.dispatchers.lookup("my-other-dispatcher")
  implicit val materializer = ActorMaterializer()
  
  // a map from the uid to a future which is completed when an election with such uid is created
  private val creationNotificationsMap = scala.collection.mutable.Map[String, Promise[Unit]]()
  
  private val votingElectionsMap = scala.collection.mutable.Map[String, Election[N, Votes]]()
  private val finishedElectionsMap = scala.collection.mutable.Map[String, Election[N, Decrypted]]()
  
  BoardReader.addElectionCreationListener { uid =>
    println("director listening to uid " + uid)
   val subscriberCreatePromise = blocking { getOrAddCreateNotification(uid, Promise[Unit]()) }
   subscriberCreatePromise.success({})
   processStartElection(uid)
  }
  
  Router.setElectionDirector { this }

  private def getOrAddCreateNotification(key: String, promise: Promise[Unit]) : Promise[Unit] = {
    creationNotificationsMap.synchronized {
      creationNotificationsMap.get(key) match {
        case Some(value) =>
          value
        case None =>
          creationNotificationsMap += (key -> promise)
          promise 
      }
    }
  }
  
  def addVote(ctx: RequestContext, electionId : Long, voterId : String) : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    Future {
      val checkHMAC = HMACAuthCheck(voterId, "AuthEvent", electionId, "vote")
      checkHMAC.check(ctx.request) map { checkResult =>
        if(checkResult) {
          println("addVote HMac check : " + checkResult)
        } else {
          throw new java.lang.Error("HMac check failed")
        }
      } map { aUnit =>
        val electionOpt = blocking {
          votingElectionsMap.synchronized {
            votingElectionsMap.get(electionId.toString)
          }
        }
        electionOpt match {
          case None =>
            promise.success(HttpResponse(status = 400, entity = Json.stringify(response(s"Invalid election id $electionId")) ))
          case Some(election) =>
            getString(ctx.request.entity) map { strRequest =>
            val jsInput = Json.parse(strRequest)
            jsInput.validate[VoteDTO] match {
              case JsError(errors) =>
                promise.success(HttpResponse(status = 400, entity = Json.stringify(response(s"Invalid vote json $errors")) ))
              case JsSuccess(voteDTO, jsPath) =>
                val pks = PublicKey(
                            q = BigInt( election.state.cSettings.group.getOrder().toString ),
                            p = BigInt( election.state.cSettings.group.getModulus().toString ),
                            y = BigInt( election.state.publicKey ),
                            g = BigInt( election.state.cSettings.generator.getValue().toString )
                          )
                val vote = voteDTO.validate(pks, true, electionId, voterId)
                Election.addVote(election, vote) map { voted =>
                  blocking {
                    votingElectionsMap.synchronized {
                      votingElectionsMap += (electionId.toString -> voted)
                    }
                  }
                  promise.success(HttpResponse(status = 200, entity = Json.stringify(response(voted.state.addVoteIndex)) ))
                }  recover { case err =>
                  promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
                }
              }
            } recover { case err =>
              promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
            }
        }
      } recover { case err =>
        promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
      }      
    } recover { case err =>
      promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
    }
    promise.future
  }
  
  def createElection(ctx: RequestContext, electionId : Long) : Future[HttpResponse] =
  {
    val promise = Promise[HttpResponse]()
    Future {
      creationNotificationsMap.synchronized {
        creationNotificationsMap.get(electionId.toString) match {
          case Some(election) =>
            throw new java.lang.Error("Error, election already exists")
          case None =>
            ; // continue
        }
      }
      val body = getString(ctx.request.entity) map { strRequest =>
            var body = Json.parse(strRequest)
            if (!body.as[JsObject].keys.contains("real")) {
                body = body.as[JsObject] + ("real" -> Json.toJson(false))
            }
            if (!body.as[JsObject].keys.contains("extra_data")) {
                body = body.as[JsObject] + ("extra_data" -> Json.toJson("{}"))
            }
            body
      }
      val electionConfig = body map { jsBody =>
        jsBody.validate[ElectionConfig]
      }
      electionConfig map { electionConfig => 
        electionConfig match {
          case JsError(errors) =>
            println(s"Invalid config json, $errors")
            promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Invalid config json " + JsError(errors))) ))
          
          case JsSuccess(config, path) =>
            val start = Election.create[N](electionId.toString, 2048, Some(config))
            start onComplete {
              case Success(election) =>
                val subscriberCreatePromise = blocking { getOrAddCreateNotification(election.state.uid, Promise[Unit]()) }
                subscriberCreatePromise.future onComplete {
                  case Success(d) =>
                    promise.success(HttpResponse(status = 200, entity = Json.stringify(response(election.state.uid)) ))
                  case Failure(err) =>
                    promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Error creating election " + getMessageFromThrowable(err))) ))
                }
              case Failure(err) =>
                promise.success(HttpResponse(status = 400, entity = Json.stringify(error(s"Error creating election " + getMessageFromThrowable(err))) ))
            }
        }
      } recover { case err =>
        promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
      }
    } recover { case err =>
      promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
    }
    promise.future
  }
  
  def stopElection(ctx: RequestContext, electionId : Long) : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    Future {
      processStopElection(electionId.toString) onComplete {
        case Success(u) =>
          promise.success(HttpResponse(status = 200, entity = Json.stringify(response("")) ))
        case Failure(err) =>
          promise.success(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
      }
    } recover { case err =>
      promise.trySuccess(HttpResponse(status = 400, entity = Json.stringify(response(getMessageFromThrowable(err))) ))
    }
    promise.future
  }
  
  def stopElection(uid: String) = processStopElection(uid)
  
  private def processStartElection(uid: String) : Future[Unit] =  {
    val promise = Promise[Unit]()
    Future {
      val subscriber = BoardReader.getSubscriber(uid)
      val create = subscriber.create()
      val startShares = create map { start =>
        Election.startShares(start.asInstanceOf[Election[N, Created]])
      }
      
      val combined = startShares flatMap { r => subscriber.addShare[N]() } flatMap { nShares =>
        Election.combineShares(nShares.asInstanceOf[Election[N, Shares[N]]])
      }
      
      val startVotes = combined flatMap { combined => 
        Election.startVotes(combined) 
      }
      
      startVotes map { started =>
        votingElectionsMap.synchronized {
          votingElectionsMap.get(started.state.uid) match {
            case Some(election) =>
              throw new java.lang.Error("Error, election already started")
            case None =>
              votingElectionsMap += (started.state.uid -> started)
          }
        }
        promise.success({})
      } recover { case err =>
        println("== Election Director error " + getMessageFromThrowable(err) )
        promise.tryFailure(err)
      }
    } recover { case err =>
      promise.tryFailure(err)
    }
    promise.future
  }
  
  private def processStopElection(uid: String) : Future[Unit] = {
    val promise = Promise[Unit]()
    Future {
      val electionGettingVotes = votingElectionsMap.synchronized {
        votingElectionsMap.get(uid) match {
          case Some(election) => 
            votingElectionsMap -= uid
            election
          case None =>
            throw new java.lang.Error("Stop Election Error: Election uid not found: " + uid)
        }
      }
      val subscriber = BoardReader.getSubscriber(uid)
      // we are only timing the mixing phase
      val mixingStart = System.currentTimeMillis()
      var mixingEnd = mixingStart
      // FIXME remove this
      MPBridge.total = 0;
      // stop the voting period
      val stopVotes = Election.stopVotes(electionGettingVotes)
      
      val startMixing = stopVotes flatMap { stopVotes =>
        Election.startMixing(stopVotes)
      }
      
      val lastMix = startMixing  flatMap { r => subscriber.addMix[N]() }
      
      val stopMix = lastMix flatMap { lastMix =>
        Election.stopMixing(lastMix.asInstanceOf[Election[N, Mixing[N]]])
      }
      
      stopMix flatMap { stopMix =>
        Election.startDecryptions(stopMix)
      }
      
      val electionDone = stopMix flatMap { r => subscriber.addDecryption[N]() } flatMap { partialN =>
        Election.combineDecryptions(partialN.asInstanceOf[Election[N, Decryptions[N]]])
      }
      
      electionDone map { electionDone => 
        var str : String = s"Decrypted size ${electionDone.state.decrypted.length}\n"
        electionDone.state.decrypted foreach { a =>
          str += a + "\n"
        }
        // lets check that everything went well
        // println(s"Plaintexts $plaintexts")
        println(str)
        // println("ok: " + (plaintexts.sorted == electionDone.state.decrypted.map(_.toInt).sorted))
  
        val mixTime = (mixingEnd - mixingStart) / 1000.0
        val totalTime = (System.currentTimeMillis() - mixingStart) / 1000.0
  
        println("*************************************************************")
        println(s"finished run with votes = $totalVotes")
        println(s"mixTime: $mixTime")
        println(s"totalTime: $totalTime")
        println(s"sec / vote (mix): ${mixTime / totalVotes}")
        println(s"sec / vote: ${totalTime / totalVotes}")
        println(s"total modExps: ${MPBridge.total}")
        println(s"found modExps: ${MPBridge.found}")
        println(s"found modExps %: ${MPBridge.found/MPBridge.total.toDouble}")
        println(s"extracted modExps: ${MPBridge.getExtracted}")
        println(s"extracted modExps %: ${MPBridge.getExtracted/MPBridge.total.toDouble}")
        println(s"modExps / vote: ${MPBridge.total.toFloat / totalVotes}")
        println("*************************************************************")
  
        MPBridgeS.shutdown
        finishedElectionsMap.synchronized {
          finishedElectionsMap.get(uid) match {
            case Some(election) =>
              throw new java.lang.Error("Error: election already finished")
            case None =>
              finishedElectionsMap += (uid -> electionDone) 
              promise.success({})
          }
        }
      } recover { case err =>
        promise.tryFailure(err)
      }
    } recover { case err =>
      promise.tryFailure(err)
    }
    promise.future
  }  
}