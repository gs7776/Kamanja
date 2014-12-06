
package com.ligadata.OnLEPManager

import com.ligadata.olep.metadata.{ BaseElem, MappedMsgTypeDef, BaseAttributeDef, StructTypeDef, EntityType, AttributeDef, ArrayBufTypeDef, MessageDef, ContainerDef, ModelDef }
import com.ligadata.olep.metadata._
import com.ligadata.olep.metadata.MdMgr._

import com.ligadata.olep.metadataload.MetadataLoad
import scala.collection.mutable.TreeSet
import scala.util.control.Breaks._
import com.ligadata.OnLEPBase.{ MdlInfo, MessageContainerObjBase, BaseMsgObj, BaseContainer, ModelBaseObj, TransformMessage, EnvContext }
import scala.collection.mutable.HashMap
import org.apache.log4j.Logger
import scala.collection.mutable.ArrayBuffer
import com.ligadata.Serialize._
import com.ligadata.ZooKeeper._
import com.ligadata.MetadataAPI.MetadataAPIImpl

class TransformMsgFldsMap(var keyflds: Array[Int], var outputFlds: Array[Int]) {
}

// msgobj is null for Containers
class MsgContainerObjAndTransformInfo(var tranformMsgFlds: TransformMsgFldsMap, var msgobj: BaseMsgObj) {
  var parents = new ArrayBuffer[(String, String)] // Immediate parent comes at the end, grand parent last but one, ... Messages/Containers. the format is Message/Container Type name and the variable in that.
  var childs = new ArrayBuffer[(String, String)] // Child Messages/Containers (Name & type). We fill this when we create message and populate parent later from this
}

// This is shared by multiple threads to read (because we are not locking). We create this only once at this moment while starting the manager
class OnLEPMetadata {
  val LOG = Logger.getLogger(getClass);

  // Metadata manager
  val messageObjects = new HashMap[String, MsgContainerObjAndTransformInfo]
  val containerObjects = new HashMap[String, MsgContainerObjAndTransformInfo]
  val modelObjects = new HashMap[String, MdlInfo]

  def LoadMdMgrElems(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror,
    tmpMsgDefs: Option[scala.collection.immutable.Set[MessageDef]], tmpContainerDefs: Option[scala.collection.immutable.Set[ContainerDef]],
    tmpModelDefs: Option[scala.collection.immutable.Set[ModelDef]]): Unit = {
    PrepareMessages(loadedJars, loader, mirror, tmpMsgDefs)
    PrepareContainers(loadedJars, loader, mirror, tmpContainerDefs)
    PrepareModels(loadedJars, loader, mirror, tmpModelDefs)

    LOG.info("Loaded Metadata Messages:" + messageObjects.map(container => container._1).mkString(","))
    LOG.info("Loaded Metadata Containers:" + containerObjects.map(container => container._1).mkString(","))
    LOG.info("Loaded Metadata Models:" + modelObjects.map(container => container._1).mkString(","))
  }

  def PrepareMessage(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, msg: MessageDef, loadJars: Boolean): Unit = {
    if (loadJars)
      LoadJarIfNeeded(msg, loadedJars, loader)
    // else Assuming we are already loaded all the required jars

    var clsName = msg.PhysicalName.trim
    if (clsName.size > 0 && clsName.charAt(clsName.size - 1) != '$') // if no $ at the end we are taking $
      clsName = clsName + "$"

    var isMsg = true

    try {
      // If required we need to enable this test
      // Convert class name into a class
      var curClz = Class.forName(clsName, true, loader)

      isMsg = false

      while (curClz != null && isMsg == false) {
        isMsg = ManagerUtils.isDerivedFrom(curClz, "com.ligadata.OnLEPBase.BaseMsgObj")
        if (isMsg == false)
          curClz = curClz.getSuperclass()
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to get classname :" + clsName)
        e.printStackTrace
      }
    }

    if (isMsg) {
      try {
        val module = mirror.staticModule(clsName)
        val obj = mirror.reflectModule(module)
        val objinst = obj.instance
        if (objinst.isInstanceOf[BaseMsgObj]) {
          val messageobj = objinst.asInstanceOf[BaseMsgObj]
          val msgName = msg.FullName.toLowerCase
          var tranformMsgFlds: TransformMsgFldsMap = null
          if (messageobj.NeedToTransformData) {
            val txfnMsg: TransformMessage = messageobj.TransformDataAttributes

            val inputFieldsMap = txfnMsg.inputFields.map(f => f.trim.toLowerCase).view.zipWithIndex.toMap
            val outputFldIdxs = txfnMsg.outputFields.map(f => {
              val fld = inputFieldsMap.getOrElse(f.trim.toLowerCase, -1)
              if (fld < 0) {
                throw new Exception("Output Field \"" + f + "\" not found in input list of fields")
              }
              fld
            })

            val keyfldsIdxs = txfnMsg.outputKeys.map(f => {
              val fld = inputFieldsMap.getOrElse(f.trim.toLowerCase, -1)
              if (fld < 0)
                throw new Exception("Key Field \"" + f + "\" not found in input list of fields")
              fld
            })
            tranformMsgFlds = new TransformMsgFldsMap(keyfldsIdxs, outputFldIdxs)
          }
          val mgsObj = new MsgContainerObjAndTransformInfo(tranformMsgFlds, messageobj)
          GetChildsFromEntity(msg.containerType, mgsObj.childs)
          messageObjects(msgName) = mgsObj

          LOG.info("Created Message:" + msgName)
        } else {
          LOG.error("Failed to instantiate message object :" + clsName)
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to instantiate message object:" + clsName+ ". Reason:" + e.getCause + ". Message:" + e.getMessage())
        }
      }
    } else {
      LOG.error("Failed to instantiate message object :" + clsName)
    }
  }

  def PrepareContainer(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, container: ContainerDef, loadJars: Boolean): Unit = {
    if (loadJars)
      LoadJarIfNeeded(container, loadedJars, loader)
    // else Assuming we are already loaded all the required jars

    val clsName = container.PhysicalName
    val containerName = container.FullName.toLowerCase

    var isContainer = true

    /*
		// If required we need to enable this test
		// Convert class name into a class
		var curClz = Class.forName(clsName, true, loader)
		
		isContainer = false
		
		while (curClz != null && isContainer == false) {
		isContainer = isDerivedFrom(curClz, "com.ligadata.OnLEPBase.BaseContainerObj")
		if (isContainer == false)
		curClz = curClz.getSuperclass()
		}
		*/
    /*
      if (isContainer) {
        try {
          val module = mirror.staticModule(clsName)
          val obj = mirror.reflectModule(module)

          val objinst = obj.instance
          if (objinst.isInstanceOf[BaseContainer]) {
            val containerobj = objinst.asInstanceOf[BaseContainer]
            val containerName = (container.NameSpace.trim + "." + container.Name.trim).toLowerCase
            containerObjects(containerName) = containerobj
          } else
            LOG.error("Failed to instantiate container object :" + clsName)
        } catch {
          case e: Exception => LOG.error("Failed to instantiate container object:" + clsName+ ". Reason:" + e.getCause + ". Message:" + e.getMessage())
        }
      }
*/
    val containerObj = new MsgContainerObjAndTransformInfo(null, null)
    GetChildsFromEntity(container.containerType, containerObj.childs)
    containerObjects(containerName) = containerObj
  }

  def PrepareModel(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, mdl: ModelDef, loadJars: Boolean): Unit = {
    if (loadJars)
      LoadJarIfNeeded(mdl, loadedJars, loader)
    // else Assuming we are already loaded all the required jars

    var clsName = mdl.PhysicalName.trim
    if (clsName.size > 0 && clsName.charAt(clsName.size - 1) != '$') // if no $ at the end we are taking $
      clsName = clsName + "$"

    var isModel = true

    try {
      // If required we need to enable this test
      // Convert class name into a class
      var curClz = Class.forName(clsName, true, loader)

      isModel = false

      while (curClz != null && isModel == false) {
        isModel = ManagerUtils.isDerivedFrom(curClz, "com.ligadata.OnLEPBase.ModelBaseObj")
        if (isModel == false)
          curClz = curClz.getSuperclass()
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to get classname :" + clsName)
        e.printStackTrace
      }
    }

    // LOG.info("Loading Model:" + mdl.FullName + ". ClassName: " + clsName + ". IsModel:" + isModel)

    if (isModel) {
      try {
        val module = mirror.staticModule(clsName)
        val obj = mirror.reflectModule(module)

        val objinst = obj.instance
        // val objinst = obj.instance
        if (objinst.isInstanceOf[ModelBaseObj]) {
          val modelobj = objinst.asInstanceOf[ModelBaseObj]
          val mdlName = (mdl.NameSpace.trim + "." + mdl.Name.trim).toLowerCase
          modelObjects(mdlName) = new MdlInfo(modelobj, mdl.jarName, mdl.dependencyJarNames, "Ligadata")
          LOG.info("Created Model:" + mdlName)
        } else {
          LOG.error("Failed to instantiate model object :" + clsName)
          LOG.info("Failed to instantiate model object :" + clsName + ". ObjType0:" + objinst.getClass.getSimpleName + ". ObjType1:" + objinst.getClass.getCanonicalName)
        }
      } catch {
        case e: Exception => LOG.error("Failed to instantiate model object:" + clsName+ ". Reason:" + e.getCause + ". Message:" + e.getMessage)
      }
    } else {
      LOG.error("Failed to instantiate model object :" + clsName)
    }
  }

  private def LoadJarIfNeeded(elem: BaseElem, loadedJars: TreeSet[String], loader: OnLEPClassLoader): Boolean = {
    var retVal: Boolean = true
    var allJars: Array[String] = null

    val jarname = if (elem.JarName == null) "" else elem.JarName.trim

    if (elem.DependencyJarNames != null && elem.DependencyJarNames.size > 0 && jarname.size > 0) {
      allJars = elem.DependencyJarNames :+ jarname
    } else if (elem.DependencyJarNames != null && elem.DependencyJarNames.size > 0) {
      allJars = elem.DependencyJarNames
    } else if (jarname.size > 0) {
      allJars = Array(jarname)
    } else {
      return retVal
    }

    return ManagerUtils.LoadJars(allJars.map(j => OnLEPConfiguration.GetValidJarFile(OnLEPConfiguration.jarPaths, j)), loadedJars, loader)
  }

  private def GetChildsFromEntity(entity: EntityType, childs: ArrayBuffer[(String, String)]): Unit = {
    // mgsObj.childs +=
    if (entity.isInstanceOf[MappedMsgTypeDef]) {
      var attrMap = entity.asInstanceOf[MappedMsgTypeDef].attrMap
      //BUGBUG:: Checking for only one level at this moment
      if (attrMap != null) {
        childs ++= attrMap.filter(a => (a._2.isInstanceOf[AttributeDef] && (a._2.asInstanceOf[AttributeDef].aType.isInstanceOf[MappedMsgTypeDef] || a._2.asInstanceOf[AttributeDef].aType.isInstanceOf[StructTypeDef]))).map(a => (a._2.Name, a._2.asInstanceOf[AttributeDef].aType.FullName))
        // If the attribute is an arraybuffer (not yet handling others)
        childs ++= attrMap.filter(a => (a._2.isInstanceOf[AttributeDef] && a._2.asInstanceOf[AttributeDef].aType.isInstanceOf[ArrayBufTypeDef] && (a._2.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.isInstanceOf[MappedMsgTypeDef] || a._2.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.isInstanceOf[StructTypeDef]))).map(a => (a._2.Name, a._2.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.FullName))
      }
    } else if (entity.isInstanceOf[StructTypeDef]) {
      var memberDefs = entity.asInstanceOf[StructTypeDef].memberDefs
      //BUGBUG:: Checking for only one level at this moment
      if (memberDefs != null) {
        childs ++= memberDefs.filter(a => (a.isInstanceOf[AttributeDef] && (a.asInstanceOf[AttributeDef].aType.isInstanceOf[MappedMsgTypeDef] || a.asInstanceOf[AttributeDef].aType.isInstanceOf[StructTypeDef]))).map(a => (a.Name, a.asInstanceOf[AttributeDef].aType.FullName))
        // If the attribute is an arraybuffer (not yet handling others)
        childs ++= memberDefs.filter(a => (a.isInstanceOf[AttributeDef] && a.asInstanceOf[AttributeDef].aType.isInstanceOf[ArrayBufTypeDef] && (a.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.isInstanceOf[MappedMsgTypeDef] || a.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.isInstanceOf[StructTypeDef]))).map(a => (a.Name, a.asInstanceOf[AttributeDef].aType.asInstanceOf[ArrayBufTypeDef].elemDef.FullName))
      }
    } else {
      // Nothing to do at this moment
    }
  }

  private def PrepareMessages(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, tmpMsgDefs: Option[scala.collection.immutable.Set[MessageDef]]): Unit = {
    if (tmpMsgDefs == None) // Not found any messages
      return

    val msgDefs = tmpMsgDefs.get

    // Load all jars first
    msgDefs.foreach(msg => {
      // LOG.info("Loading msg:" + msg.FullName)
      LoadJarIfNeeded(msg, loadedJars, loader)
    })

    msgDefs.foreach(msg => {
      PrepareMessage(loadedJars, loader, mirror, msg, false) // Already Loaded required dependency jars before calling this
    })
  }

  private def PrepareContainers(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, tmpContainerDefs: Option[scala.collection.immutable.Set[ContainerDef]]): Unit = {
    if (tmpContainerDefs == None) // Not found any containers
      return

    val containerDefs = tmpContainerDefs.get

    // Load all jars first
    containerDefs.foreach(container => {
      LoadJarIfNeeded(container, loadedJars, loader)
    })

    containerDefs.foreach(container => {
      PrepareContainer(loadedJars, loader, mirror, container, false) // Already Loaded required dependency jars before calling this
    })

  }

  private def PrepareModels(loadedJars: TreeSet[String], loader: OnLEPClassLoader, mirror: reflect.runtime.universe.Mirror, tmpModelDefs: Option[scala.collection.immutable.Set[ModelDef]]): Unit = {
    if (tmpModelDefs == None) // Not found any models
      return

    val modelDefs = tmpModelDefs.get

    // Load all jars first
    modelDefs.foreach(mdl => {
      LoadJarIfNeeded(mdl, loadedJars, loader)
    })

    modelDefs.foreach(mdl => {
      PrepareModel(loadedJars, loader, mirror, mdl, false) // Already Loaded required dependency jars before calling this
    })
  }
}

object OnLEPMetadata {
  var envCtxt: EnvContext = null // Engine will set it once EnvContext is initialized
  private[this] val LOG = Logger.getLogger(getClass);
  private[this] val mdMgr = GetMdMgr
  private[this] var loadedJars: TreeSet[String] = _
  private[this] var loader: OnLEPClassLoader = _
  private[this] var mirror: reflect.runtime.universe.Mirror = _

  private[this] var messageContainerObjects = new HashMap[String, MsgContainerObjAndTransformInfo]
  private[this] var modelObjects = new HashMap[String, MdlInfo]
  private[this] var zkListener: ZooKeeperListener = _

  private[this] val lock = new Object()

  private def UpdateOnLepMdObjects(msgObjects: HashMap[String, MsgContainerObjAndTransformInfo], contObjects: HashMap[String, MsgContainerObjAndTransformInfo],
    mdlObjects: HashMap[String, MdlInfo], removedModels: ArrayBuffer[(String, String, Int)], removedMessages: ArrayBuffer[(String, String, Int)],
    removedContainers: ArrayBuffer[(String, String, Int)]): Unit = lock.synchronized {
    //BUGBUG:: Assuming there is no issues if we remove the objects first and then add the new objects. We are not adding the object in the same order as it added in the transaction. 

    // First removing the objects
    // Removing Models
    if (removedModels != null && removedModels.size > 0) {
      removedModels.foreach(mdl => {
        val elemName = (mdl._1.trim + "." + mdl._2.trim).toLowerCase
        modelObjects -= elemName
      })
    }

    // Removing Messages
    if (removedMessages != null && removedMessages.size > 0) {
      removedMessages.foreach(msg => {
        val elemName = (msg._1.trim + "." + msg._2.trim).toLowerCase
        messageContainerObjects -= elemName //BUGBUG:: It has both Messages & Containers. Are we sure it only removes Messages here?
      })
    }

    // Removing Containers
    if (removedContainers != null && removedContainers.size > 0) {
      removedContainers.foreach(cnt => {
        val elemName = (cnt._1.trim + "." + cnt._2.trim).toLowerCase
        messageContainerObjects -= elemName //BUGBUG:: It has both Messages & Containers. Are we sure it only removes Containers here?
      })
    }

    // Adding new objects now
    // Adding container
    if (contObjects != null && contObjects.size > 0) {
      messageContainerObjects ++= contObjects
      if (envCtxt != null) {
        val containerNames = contObjects.map(container => container._1.toLowerCase).toList.sorted.toArray // Sort topics by names
        envCtxt.AddNewMessageOrContainers(OnLEPMetadata.getMdMgr, OnLEPConfiguration.dataStoreType, OnLEPConfiguration.dataLocation, OnLEPConfiguration.dataSchemaName, containerNames, true) // Containers
      }
    }

    // Adding Messages
    if (msgObjects != null && msgObjects.size > 0) {
      messageContainerObjects ++= msgObjects
      if (envCtxt != null) {
        val topMessageNames = msgObjects.filter(msg => msg._2.parents.size == 0).map(msg => msg._1.toLowerCase).toList.sorted.toArray // Sort topics by names
        envCtxt.AddNewMessageOrContainers(OnLEPMetadata.getMdMgr, OnLEPConfiguration.dataStoreType, OnLEPConfiguration.dataLocation, OnLEPConfiguration.dataSchemaName, topMessageNames, false) // Messages
      }
    }

    // Adding Models
    if (mdlObjects != null && mdlObjects.size > 0)
      modelObjects ++= mdlObjects

    // If messages/Containers removed or added, jsut change the parents chain
    if ((removedMessages != null && removedMessages.size > 0) ||
      (removedContainers != null && removedContainers.size > 0) ||
      (contObjects != null && contObjects.size > 0) ||
      (msgObjects != null && msgObjects.size > 0)) {

      // Prepare Parents for each message now
      val childToParentMap = scala.collection.mutable.Map[String, (String, String)]() // ChildType, (ParentType, ChildAttrName) 

      // Clear previous parents
      messageContainerObjects.foreach(c => {
        c._2.parents.clear
      })

      // 1. First prepare one level of parents
      messageContainerObjects.foreach(m => {
        m._2.childs.foreach(c => {
          // Checking whether we already have in childToParentMap or not before we replace. So that way we can check same child under multiple parents.
          val childMsgNm = c._2.toLowerCase
          val fnd = childToParentMap.getOrElse(childMsgNm, null)
          if (fnd != null) {
            LOG.error(s"$childMsgNm is used as child under $c and $fnd._1. First detected $fnd._1, so using as child of $fnd._1 as it is.")
          } else {
            childToParentMap(childMsgNm) = (m._1.toLowerCase, c._1)
          }
        })
      })

      // 2. Now prepare Full Parent Hierarchy
      messageContainerObjects.foreach(m => {
        var curParent = childToParentMap.getOrElse(m._1.toLowerCase, null)
        while (curParent != null) {
          m._2.parents += curParent
          curParent = childToParentMap.getOrElse(curParent._1.toLowerCase, null)
        }
      })

      // 3. Order Parent Hierarchy properly
      messageContainerObjects.foreach(m => {
        m._2.parents.reverse
      })
    }
  }

  def InitMdMgr(tmpLoadedJars: TreeSet[String], tmpLoader: OnLEPClassLoader, tmpMirror: reflect.runtime.universe.Mirror, zkConnectString: String, znodePath: String, zkSessionTimeoutMs: Int, zkConnectionTimeoutMs: Int): Unit = {

    if (OnLEPConfiguration.metadataStoreType.compareToIgnoreCase("cassandra") == 0)
      MetadataAPIImpl.InitMdMgr(mdMgr, OnLEPConfiguration.metadataStoreType, OnLEPConfiguration.metadataLocation, OnLEPConfiguration.metadataSchemaName, "")
    else if ((OnLEPConfiguration.metadataStoreType.compareToIgnoreCase("treemap") == 0) || (OnLEPConfiguration.metadataStoreType.compareToIgnoreCase("hashmap") == 0))
      MetadataAPIImpl.InitMdMgr(mdMgr, OnLEPConfiguration.metadataStoreType, "", OnLEPConfiguration.metadataSchemaName, OnLEPConfiguration.metadataLocation)

    loadedJars = tmpLoadedJars
    loader = tmpLoader
    mirror = tmpMirror

    val tmpMsgDefs = mdMgr.Messages(true, true)
    val tmpContainerDefs = mdMgr.Containers(true, true)
    val tmpModelDefs = mdMgr.Models(true, true)

    val obj = new OnLEPMetadata

    try {
      obj.LoadMdMgrElems(loadedJars, loader, mirror, tmpMsgDefs, tmpContainerDefs, tmpModelDefs)
      // Lock the global object here and update the global objects
      UpdateOnLepMdObjects(obj.messageObjects, obj.containerObjects, obj.modelObjects, null, null, null)
    } catch {
      case e: Exception => {
        LOG.error("Failed to load messages, containers & models from metadata manager. Reason:%s Message:%s".format(e.getCause, e.getMessage))
        throw e
      }
    }

    if (zkConnectString != null && zkConnectString.isEmpty() == false && znodePath != null && znodePath.isEmpty() == false) {
      try {
        CreateClient.CreateNodeIfNotExists(zkConnectString, znodePath)
        zkListener = new ZooKeeperListener
        zkListener.CreateListener(zkConnectString, znodePath, UpdateMetadata, zkSessionTimeoutMs, zkConnectionTimeoutMs)
      } catch {
        case e: Exception => {
          LOG.error("Failed to initialize ZooKeeper Connection. Reason:%s Message:%s".format(e.getCause, e.getMessage))
          throw e
        }
      }
    }
  }

  // Assuming mdMgr is locked at this moment for not to update while doing this operation
  def UpdateMetadata(receivedJsonStr: String): Unit = {
    if (receivedJsonStr == null || receivedJsonStr.size == 0) {
      // nothing to do
      return
    }

    val zkTransaction = JsonSerializer.parseZkTransaction(receivedJsonStr, "JSON")
    MetadataAPIImpl.UpdateMdMgr(zkTransaction)

    if (zkTransaction == null || zkTransaction.Notifications.size == 0) {
      // nothing to do
      return
    }

    if (mdMgr == null) {
      LOG.error("Metadata Manager should not be NULL while updaing metadta in OnLEP manager.")
      return
    }

    val obj = new OnLEPMetadata

    // BUGBUG:: Not expecting added element & Removed element will happen in same transaction at this moment
    // First we are adding what ever we need to add, then we are removing. So, we are locking before we append to global array and remove what ever is gone.
    val removedModels = new ArrayBuffer[(String, String, Int)]
    val removedMessages = new ArrayBuffer[(String, String, Int)]
    val removedContainers = new ArrayBuffer[(String, String, Int)]

    zkTransaction.Notifications.foreach(zkMessage => {
      val key = zkMessage.NameSpace + "." + zkMessage.Name + "." + zkMessage.Version
      zkMessage.ObjectType match {
        case "ModelDef" => {
          zkMessage.Operation match {
            case "Add" => {
              try {
                val mdl = mdMgr.Model(zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt, true)
                if (mdl != None) {
                  obj.PrepareModel(loadedJars, loader, mirror, mdl.get, true)
                } else {
                  LOG.error("Failed to find Model:" + key)
                }
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Add Model:" + key)
                }
              }
            }
            case "Remove" => {
              try {
                removedModels += ((zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt))
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Remove Model:" + key)
                }
              }
            }
            case _ => {
              logger.error("Unknown Operation " + zkMessage.Operation + " in zookeeper notification, notification is not processed ..")
            }
          }
        }
        case "MessageDef" => {
          zkMessage.Operation match {
            case "Add" => {
              try {
                val msg = mdMgr.Message(zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt, true)
                if (msg != None) {
                  obj.PrepareMessage(loadedJars, loader, mirror, msg.get, true)
                } else {
                  LOG.error("Failed to find Message:" + key)
                }
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Add Message:" + key)
                }
              }
            }
            case "Remove" => {
              try {
                removedMessages += ((zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt))
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Remove Message:" + key)
                }
              }
            }
            case _ => {
              logger.error("Unknown Operation " + zkMessage.Operation + " in zookeeper notification, notification is not processed ..")
            }
          }
        }
        case "ContainerDef" => {
          zkMessage.Operation match {
            case "Add" => {
              try {
                val container = mdMgr.Container(zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt, true)
                if (container != None) {
                  obj.PrepareContainer(loadedJars, loader, mirror, container.get, true)
                } else {
                  LOG.error("Failed to find Container:" + key)
                }
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Add Container:" + key)
                }
              }
            }
            case "Remove" => {
              try {
                removedContainers += ((zkMessage.NameSpace, zkMessage.Name, zkMessage.Version.toInt))
              } catch {
                case e: Exception => {
                  LOG.error("Failed to Remove Container:" + key)
                }
              }
            }
            case _ => {
              logger.error("Unknown Operation " + zkMessage.Operation + " in zookeeper notification, notification is not processed ..")
            }
          }
        }
        case _ => {
          logger.error("Unknown objectType " + zkMessage.ObjectType + " in zookeeper notification, notification is not processed ..")
        }
      }
    })

    // Lock the global object here and update the global objects
    UpdateOnLepMdObjects(obj.messageObjects, obj.containerObjects, obj.modelObjects, removedModels, removedMessages, removedContainers)
  }

  def getMessgeInfo(msgType: String): MsgContainerObjAndTransformInfo = lock.synchronized {
    if (messageContainerObjects == null) return null
    val v = messageContainerObjects.getOrElse(msgType.toLowerCase, null)
    if (v == null || v.msgobj == null) return null
    v
  }

  def getModel(mdlName: String): MdlInfo = lock.synchronized {
    if (modelObjects == null) return null
    modelObjects.getOrElse(mdlName.toLowerCase, null)
  }

  def getContainer(containerName: String): MsgContainerObjAndTransformInfo = lock.synchronized {
    if (messageContainerObjects == null) return null
    val v = messageContainerObjects.getOrElse(containerName.toLowerCase, null)
    if (v == null || v.msgobj != null) return null
    v
  }

  def getAllMessges: Map[String, MsgContainerObjAndTransformInfo] = lock.synchronized {
    if (messageContainerObjects == null) return null
    messageContainerObjects.filter(o => o._2.msgobj != null).toMap
  }

  def getAllModels: Map[String, MdlInfo] = lock.synchronized {
    if (modelObjects == null) return null
    modelObjects.toMap
  }

  def getAllContainers: Map[String, MsgContainerObjAndTransformInfo] = lock.synchronized {
    if (messageContainerObjects == null) return null
    messageContainerObjects.filter(o => o._2.msgobj == null).toMap
  }

  def getMdMgr: MdMgr = mdMgr

  def Shutdown: Unit = {
    if (zkListener != null)
      zkListener.Shutdown
    zkListener = null
  }
}
