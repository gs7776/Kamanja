package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO

import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._

import scala.util.{ Success, Failure }

import com.ligadata.MetadataAPI._

object UpdateTypeService {
  case class Process(containerJson:String)
}

class UpdateTypeService(requestContext: RequestContext) extends Actor {

  import AddContainerService._
  
  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)
  
  def receive = {
    case Process(containerJson) =>
      process(containerJson)
      context.stop(self)
  }
  
  def process(containerJson:String) = {
    log.info("Requesting AddContainer {}",containerJson)
    val apiResult = MetadataAPIImpl.UpdateType(containerJson,"JSON")
    requestContext.complete(apiResult)
  }
}