package com.ligadata.metadataapiservice

import akka.actor._
import akka.event.Logging
import com.ligadata.metadataapiservice._
import spray.routing._
import spray.http._
import MediaTypes._
import org.apache.log4j._
import com.ligadata.MetadataAPI._
import com.ligadata.Serialize._

object ModelType extends Enumeration {
  val PMML = Value("pmml") toString
  val JAVA = Value("java") toString
  val SCALA = Value("scala") toString
}

class MetadataAPIServiceActor extends Actor with MetadataAPIService {

  def actorRefFactory = context

  def receive = runRoute(metadataAPIRoute)
}


trait MetadataAPIService extends HttpService {

  APIInit.Init
  val KEY_TOKN = "keys"
  val AUDIT_LOG_TOKN = "audit_log"
  val LEADER_TOKN = "leader"
  val APIName = "MetadataAPIService"

  val loggerName = this.getClass.getName
  val logger = Logger.getLogger(loggerName)
  // logger.setLevel(Level.TRACE);

  val metadataAPIRoute = {
    optionalHeaderValueByName("userid") { userId => {
      optionalHeaderValueByName("password") { password => {
        optionalHeaderValueByName("role") { role =>
          var user: Option[String] = None

          // Make sure that the Audit knows the difference between No User specified and an None (request originates within the engine)
          if (userId == None) user = Some("")
          logger.debug("userid => " + user.get + ",password => xxxxx" + ",role => " + role)
          get {
              path("api" / Rest) { str => {
              val toknRoute = str.split("/")
              logger.debug("GET reqeust : api/" + str)
              if (toknRoute.size == 1) {
                if (toknRoute(0).equalsIgnoreCase(AUDIT_LOG_TOKN)) {
                  requestContext => processGetAuditLogRequest(null, requestContext, user, password, role)
                }
                else if (toknRoute(0).equalsIgnoreCase(LEADER_TOKN)) {
                  requestContext => processGetLeaderRequest(null, requestContext, user, password, role)
                }
                else {
                  requestContext => processGetObjectRequest(toknRoute(0), "", requestContext, user, password, role)
                }
              }
              else if (toknRoute(0).equalsIgnoreCase(KEY_TOKN)) {
                requestContext => processGetKeysRequest(toknRoute(1).toLowerCase, requestContext, user, password, role)
              }
              else if (toknRoute(0).equalsIgnoreCase(AUDIT_LOG_TOKN)) {
                // strip the first token and send the rest
                val filterParameters = toknRoute.slice(1, toknRoute.size)
                requestContext => processGetAuditLogRequest(filterParameters, requestContext, user, password, role)
              }
              else {
                requestContext => processGetObjectRequest(toknRoute(0).toLowerCase, toknRoute(1).toLowerCase, requestContext, user, password, role)
              }
            }
            }
          } ~
            put {
              path("api" / Rest) { str => {
                logger.debug("PUT reqeust : api/" + str)
                val toknRoute = str.split("/")
                if (toknRoute(0).equalsIgnoreCase("UploadJars")) {
                  entity(as[Array[Byte]]) {
                    reqBody => {
                      parameters('name) { jarName => {
                        logger.debug("Uploading jar " + jarName)
                        requestContext =>
                          val uploadJarService = actorRefFactory.actorOf(Props(new UploadJarService(requestContext, user, password, role)))
                          uploadJarService ! UploadJarService.Process(jarName, reqBody)
                      }
                      }
                    }
                  }
                } else if (toknRoute(0).equalsIgnoreCase("Activate") || toknRoute(0).equalsIgnoreCase("Deactivate")) {
                  entity(as[String]) { reqBody => requestContext => processPutRequest(toknRoute(0), toknRoute(1).toLowerCase, toknRoute(2), requestContext, user, password, role)}
                } else {
                  entity(as[String]) { reqBody => {
                    if (toknRoute.size == 1) { requestContext => processPutRequest(toknRoute(0), reqBody, requestContext, user, password, role)}
                    else { requestContext => requestContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown PUT route")).toString)}
                  }
                  }
                }
              }
              }
            } ~
            post {
              entity(as[String]) { reqBody =>
                path("api" / Rest) { str => {
                  val toknRoute = str.split("/")
                  logger.debug("POST reqeust : api/" + str)
                  if (toknRoute.size == 1) {
                    entity(as[String]) { reqBody => { requestContext => processPostRequest(toknRoute(0), reqBody, requestContext, user, password, role)}}
                  } else if (toknRoute.size == 2 && toknRoute(0) == "model") {
                    toknRoute(1).toString match {
                      case ModelType.PMML => {
                        val objectType=toknRoute(0)+toknRoute(1)
                        entity(as[String]) { reqBody => { requestContext => processPostRequest(objectType, reqBody, requestContext, user, password, role)}}

                      }
                      case ModelType.JAVA => {
                        val objectType=toknRoute(0)+toknRoute(1)
                        entity(as[String]) { reqBody => { requestContext => processPostRequest(objectType, reqBody, requestContext, user, password, role)}}
                      }

                      case ModelType.SCALA => {
                        val objectType=toknRoute(0)+toknRoute(1)
                        entity(as[String]) { reqBody => { requestContext => processPostRequest(objectType, reqBody, requestContext, user, password, role)}}
                      }
                    }

                  }

                  else { requestContext => requestContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown POST route")).toString)}
                }
                }
              }
            } ~
            delete {
              entity(as[String]) { reqBody =>
                path("api" / Rest) { str => {
                  val toknRoute = str.split("/")
                  logger.debug("DELETE reqeust : api/" + str)
                  if (toknRoute.size == 2) { requestContext => processDeleteRequest(toknRoute(0).toLowerCase, toknRoute(1).toLowerCase, requestContext, user, password, role)}
                  else { requestContext => requestContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown DELETE route")).toString)}
                }
                }
              }
            }
        }
      }
      }
    }
    } // Close all the Header parens
  }

  /**
   * Modify Existing objects in the Metadata 
   */
  private def processPutRequest(objtype: String, body: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val action = "Update" + objtype
    val notes = "Invoked " + action + " API "
    if (objtype.equalsIgnoreCase("Container")) {
      val updateContainerDefsService = actorRefFactory.actorOf(Props(new UpdateContainerService(rContext, userid, password, role)))
      updateContainerDefsService ! UpdateContainerService.Process(body)
    } else if (objtype.equalsIgnoreCase("Model")) {
      val updateModelService: ActorRef = actorRefFactory.actorOf(Props(new UpdateModelService(rContext, userid, password, role)))
      updateModelService ! UpdateModelService.Process(body)
    } else if (objtype.equalsIgnoreCase("Message")) {
      val updateMessageDefsService = actorRefFactory.actorOf(Props(new UpdateMessageService(rContext, userid, password, role)))
      updateMessageDefsService ! UpdateMessageService.Process(body, "JSON")
    } else if (objtype.equalsIgnoreCase("Type")) {
      val updateTypeDefsService = actorRefFactory.actorOf(Props(new UpdateTypeService(rContext, userid, password, role)))
      updateTypeDefsService ! UpdateTypeService.Process(body, "JSON")
    } else if (objtype.equalsIgnoreCase("Concept")) {
      val updateConceptDefsService = actorRefFactory.actorOf(Props(new UpdateConceptService(rContext, userid, password, role)))
      updateConceptDefsService ! UpdateConceptService.Process(body)
    } else if (objtype.equalsIgnoreCase("Function")) {
      val updateFunctionDefsService = actorRefFactory.actorOf(Props(new UpdateFunctionService(rContext, userid, password, role)))
      updateFunctionDefsService ! UpdateFunctionService.Process(body)
    } else if (objtype.equalsIgnoreCase("RemoveConfig")) {
      val removeConfigService = actorRefFactory.actorOf(Props(new RemoveEngineConfigService(rContext, userid, password, role)))
      removeConfigService ! RemoveEngineConfigService.Process(body)
    } else if (objtype.equalsIgnoreCase("UploadConfig")) {
      val uploadConfigService = actorRefFactory.actorOf(Props(new UploadEngineConfigService(rContext, userid, password, role)))
      uploadConfigService ! UploadEngineConfigService.Process(body)
    } else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown PUT route")).toString)
    }
  }

  /**
   * Modify Existing objects in the Metadata 
   */
  private def processPutRequest(action: String, objtype: String, objKey: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    var argParm: String = verifyInput(objKey, objtype, rContext)
    if (argParm == null) return

    if (action.equalsIgnoreCase("Activate")) {
      val activateObjectsService = actorRefFactory.actorOf(Props(new ActivateObjectsService(rContext, userid, password, role)))
      activateObjectsService ! ActivateObjectsService.Process(argParm)
    } else if (action.equalsIgnoreCase("Deactivate")) {
      val deactivateObjectsService = actorRefFactory.actorOf(Props(new DeactivateObjectsService(rContext, userid, password, role)))
      deactivateObjectsService ! DeactivateObjectsService.Process(argParm)
    } else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown PUT route")).toString)
    }
  }

  /**
   * Create new Objects in the Metadata
   */
  private def processPostRequest(objtype: String, body: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val action = "Add" + objtype
    val notes = "Invoked " + action + " API "
    if (objtype.equalsIgnoreCase("Container")) {
      val addContainerDefsService = actorRefFactory.actorOf(Props(new AddContainerService(rContext, userid, password, role)))
      addContainerDefsService ! AddContainerService.Process(body)
    } else if (objtype.equalsIgnoreCase("Model")) {
      val addModelService: ActorRef = actorRefFactory.actorOf(Props(new AddModelService(rContext, userid, password, role)))
      addModelService ! AddModelService.Process(body)
    } else if (objtype.equalsIgnoreCase("Message")) {
      val addMessageDefsService = actorRefFactory.actorOf(Props(new AddMessageService(rContext, userid, password, role)))
      addMessageDefsService ! AddMessageService.Process(body)
    } else if (objtype.equalsIgnoreCase("Type")) {
      val addTypeDefsService = actorRefFactory.actorOf(Props(new AddTypeService(rContext, userid, password, role)))
      addTypeDefsService ! AddTypeService.Process(body, "JSON")
    } else if (objtype.equalsIgnoreCase("Concept")) {
      val addConceptDefsService = actorRefFactory.actorOf(Props(new AddConceptService(rContext, userid, password, role)))
      addConceptDefsService ! AddConceptService.Process(body, "JSON")
    } else if (objtype.equalsIgnoreCase("Function")) {
      val addFunctionDefsService = actorRefFactory.actorOf(Props(new AddFunctionService(rContext, userid, password, role)))
      addFunctionDefsService ! AddFunctionService.Process(body, "JSON")
    } else if (objtype.equalsIgnoreCase("UploadModelConfig")) {
      //TODO
      //call the UploadModelConfig in the MetadataAPIImpl
      //UploadModelsConfig (cfgStr: String,userid:Option[String], objectList: String): String = {
      logger.debug("In post request process of UploadModelConfig")
      val addModelDefsService = actorRefFactory.actorOf(Props(new UploadModelConfigService(rContext, userid, password, role)))
      addModelDefsService ! UploadModelConfigService.Process(body)
    } else if (objtype.equalsIgnoreCase("modeljava")) {
      //TODO
      logger.debug("In post request process of model java")
      val addSourceModelService: ActorRef = actorRefFactory.actorOf(Props(new AddSourceModelService(rContext, userid, password, role)))
      addSourceModelService ! AddSourceModelService.Process(body)
      //rContext.complete(new ApiResult(ErrorCodeConstants.Success, "AddModelFromJavaSource", body.toString, "Upload of java model successful").toString)
    }
    else if (objtype.equalsIgnoreCase("modelscala")) {
      //TODO
      logger.debug("In post request process of model scala")
      rContext.complete(new ApiResult(ErrorCodeConstants.Success, "AddModelFromScalaSource",body.toString, "Upload of java model successful").toString)
    }
    else if (objtype.equalsIgnoreCase("modelpmml")) {
      //TODO
      logger.debug("In post request process of model pmml")
      rContext.complete(new ApiResult(ErrorCodeConstants.Success, "AddModelFromPMMLSource", body.toString, "Upload of java model successful").toString)
    }
    else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown POST route")).toString)
    }
  }

  /**
   *
   */
  private def processGetAuditLogRequest(filterParameters: Array[String], rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val auditLogService = actorRefFactory.actorOf(Props(new GetAuditLogService(rContext, userid, password, role)))
    auditLogService ! GetAuditLogService.Process(filterParameters)
  }

  /**
   *
   */
  private def processGetLeaderRequest(nodeList: Array[String], rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val auditLogService = actorRefFactory.actorOf(Props(new GetLeaderService(rContext, userid, password, role)))
    auditLogService ! GetLeaderService.Process(nodeList)
  }

  /**
   *
   */

  private def processGetKeysRequest(objtype: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    if (objtype.equalsIgnoreCase("Container") || objtype.equalsIgnoreCase("Model") || objtype.equalsIgnoreCase("Message") || objtype.equalsIgnoreCase("Function") ||
      objtype.equalsIgnoreCase("Concept") || objtype.equalsIgnoreCase("Type")) {
      val allObjectKeysService = actorRefFactory.actorOf(Props(new GetAllObjectKeysService(rContext, userid, password, role)))
      allObjectKeysService ! GetAllObjectKeysService.Process(objtype)
    } else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown GET route")).toString)
    }
  }

  /**
   *
   */
  private def processGetObjectRequest(objtype: String, objKey: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val action = "Get" + objtype
    val notes = "Invoked " + action + " API "
    var argParm: String = null
    if (!objtype.equalsIgnoreCase("config")) {
      argParm = verifyInput(objKey, objtype, rContext)
      if (argParm == null) return
    }

    if (objtype.equalsIgnoreCase("Config")) {
      val allObjectsService = actorRefFactory.actorOf(Props(new GetConfigObjectsService(rContext, userid, password, role)))
      allObjectsService ! GetConfigObjectsService.Process(objKey)
    } else if (objtype.equalsIgnoreCase("Container") || objtype.equalsIgnoreCase("Model") || objtype.equalsIgnoreCase("Message") ||
      objtype.equalsIgnoreCase("Function") || objtype.equalsIgnoreCase("Concept") || objtype.equalsIgnoreCase("Type")) {
      val getObjectsService = actorRefFactory.actorOf(Props(new GetObjectsService(rContext, userid, password, role)))
      getObjectsService ! GetObjectsService.Process(argParm)
    } else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown GET route")).toString)
    }
  }

  /**
   *
   */
  private def processDeleteRequest(objtype: String, objKey: String, rContext: RequestContext, userid: Option[String], password: Option[String], role: Option[String]): Unit = {
    val action = "Remove" + objtype
    val notes = "Invoked " + action + " API "
    var argParm: String = verifyInput(objKey, objtype, rContext)
    if (argParm == null) return

    if (objtype.equalsIgnoreCase("Container") || objtype.equalsIgnoreCase("Model") || objtype.equalsIgnoreCase("Message") ||
      objtype.equalsIgnoreCase("Function") || objtype.equalsIgnoreCase("Concept") || objtype.equalsIgnoreCase("Type")) {
      val removeObjectsService = actorRefFactory.actorOf(Props(new RemoveObjectsService(rContext, userid, password, role)))
      removeObjectsService ! RemoveObjectsService.Process(argParm)
    } else if (objtype.equalsIgnoreCase("Config")) {
      val removeConfigService = actorRefFactory.actorOf(Props(new RemoveEngineConfigService(rContext, userid, password, role)))
      removeConfigService ! RemoveEngineConfigService.Process(argParm)
    } else {
      rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Unknown DELETE route")).toString)
    }
  }


  private def verifyInput(objKey: String, objType: String, rContext: RequestContext): String = {
    // Verify that the a 3 part name is the key, an Out Of Bounds exception will be thrown if name is not XXX.XXX.XXX
    try {
      return createGetArg(objKey, objType)
    } catch {
      case aobe: ArrayIndexOutOfBoundsException => {
        logger.debug("METADATASERVICE: Invalid key " + objKey)
        rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Invalid key: " + objKey)).toString)
        return null
      }
      case nfe: java.lang.NumberFormatException => {
        logger.debug("METADATASERVICE: Invalid key " + objKey)
        rContext.complete((new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Invalid key: " + objKey)).toString)
        return null
      }
    }
  }

  /**
   * MakeJsonStrForArgList
   */
  private def createGetArg(objKey: String, objectType: String): String = {
    val keyTokens = objKey.split("\\.")
    val nameSpace = keyTokens(0)
    val name = keyTokens(1)
    val version = keyTokens(2)
    val lVersion = version.toLong
    val mdArg = new MetadataApiArg(objectType, nameSpace, name, version, "JSON")
    val argList = new Array[MetadataApiArg](1)
    argList(0) = mdArg
    val mdArgList = new MetadataApiArgList(argList.toList)
    val apiArgJson = JsonSerializer.SerializeApiArgListToJson(mdArgList)
    apiArgJson
  }
}
