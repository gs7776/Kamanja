package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO
import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._
import scala.util.{ Success, Failure }
import com.ligadata.MetadataAPI._
import com.ligadata.Serialize._

import scala.util.control._
import org.apache.log4j._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._


object GetLeaderService {
  case class Process(nodeList:Array[String])
}

class GetLeaderService(requestContext: RequestContext, userid:Option[String], password:Option[String], cert:Option[String]) extends Actor {

  import GetLeaderService._
  
  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)
  
  val loggerName = this.getClass.getName
  val logger = Logger.getLogger(loggerName)
  logger.setLevel(Level.TRACE);

  val APIName = "GetLeader"

  def receive = {
    case Process(nodeList) =>
      process(nodeList)
      context.stop(self)
  }

  def process(nodeList: Array[String]) = {
    // This service return a "true" or "false" depending on whether this node is leader or not
    val isLeader = MetadataAPIServiceLeader.IsLeader
    requestContext.complete(isLeader.toString())
  }
}
