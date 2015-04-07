package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO

import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._
import com.ligadata.olep.metadata._

import scala.util.{ Success, Failure }

import com.ligadata.MetadataAPI._

object AddContainerService {
  case class Process(containerJson:String)
}

class AddContainerService(requestContext: RequestContext, userid:Option[String], password:Option[String], cert:Option[String]) extends Actor {

  import AddContainerService._
  
  implicit val system = context.system
  import system.dispatcher
  val log = Logging(system, getClass)
  val APIName = "AddContainerService"
  
  def receive = {
    case Process(containerJson) =>
      process(containerJson)
      context.stop(self)
  }
  
  def process(containerJson:String) = {
    log.info("Requesting AddContainer {}",containerJson)

    val objectName = containerJson.substring(0,100)
    
    if (!MetadataAPIImpl.checkAuth(userid,password,cert, MetadataAPIImpl.getPrivilegeName("insert","container"))) {
      MetadataAPIImpl.logAuditRec(userid,Some(AuditConstants.WRITE),AuditConstants.INSERTOBJECT,AuditConstants.CONTAINER,AuditConstants.FAIL,"",objectName.substring(0,20))
      requestContext.complete(new ApiResult(-1, APIName, null, "Error:UPDATE not allowed for this user").toString )
    }    
    
    val apiResult = MetadataAPIImpl.AddContainer(containerJson,"JSON")
    MetadataAPIImpl.logAuditRec(userid,Some(AuditConstants.WRITE),AuditConstants.INSERTOBJECT,AuditConstants.CONTAINER,AuditConstants.SUCCESS,"",objectName.substring(0,20))
    requestContext.complete(apiResult)
  }
}
