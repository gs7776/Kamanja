
package com.ligadata.OnLEPManager

import com.ligadata.OnLEPBase._

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
import org.apache.curator.framework._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.apache.curator.utils.ZKPaths

object OnLEPLeader {
  private[this] val LOG = Logger.getLogger(getClass);
  private[this] val lock = new Object()
  private[this] var clusterStatus = ClusterStatus("", false, "", null)
  private[this] var zkLeaderLatch: ZkLeaderLatch = _
  private[this] var nodeId: String = _
  private[this] var zkConnectString: String = _
  private[this] var engineLeaderZkNodePath: String = _
  private[this] var engineDistributionZkNodePath: String = _
  private[this] var adaptersStatusPath: String = _
  private[this] var zkSessionTimeoutMs: Int = _
  private[this] var zkConnectionTimeoutMs: Int = _
  private[this] var zkEngineDistributionNodeListener: ZooKeeperListener = _
  private[this] var zkAdapterStatusNodeListener: ZooKeeperListener = _
  private[this] var zkcForSetData: CuratorFramework = null
  private[this] var distributionMap = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, ArrayBuffer[String]]]() // Nodeid & Unique Keys (adapter unique name & unique key)
  private[this] var adapterMaxPartitions = scala.collection.mutable.Map[String, Int]() // Adapters & Max Partitions
  private[this] var nodesStatus = scala.collection.mutable.Set[String]() // NodeId
  private[this] var expectedNodesAction: String = _
  private[this] var curParticipents = Set[String]() // Derived from clusterStatus.participants
  private[this] var canRedistribute = false
  private[this] var inputAdapters: ArrayBuffer[InputAdapter] = _
  private[this] var outputAdapters: ArrayBuffer[OutputAdapter] = _
  private[this] var statusAdapters: ArrayBuffer[OutputAdapter] = _
  private[this] var envCtxt: EnvContext = _

  private def SetCanRedistribute(redistFlag: Boolean): Unit = lock.synchronized {
    canRedistribute = redistFlag
  }

  private def UpdatePartitionsNodeData(eventType: String, eventPath: String, eventPathData: Array[Byte]): Unit = lock.synchronized {
    try {
      val evntPthData = if (eventPathData != null) (new String(eventPathData)) else "{}"
      val extractedNode = ZKPaths.getNodeFromPath(eventPath)
      LOG.info("UpdatePartitionsNodeData => eventType: %s, eventPath: %s, eventPathData: %s, Extracted Node:%s".format(eventType, eventPath, evntPthData, extractedNode))

      if (eventType.compareToIgnoreCase("CHILD_UPDATED") == 0) {
        if (curParticipents(extractedNode)) { // If this node is one of the participent, then work on this, otherwise ignore
          val json = parse(evntPthData)
          if (json == null || json.values == null) // Not doing any action if not found valid json
            return
          val values = json.values.asInstanceOf[Map[String, Any]]
          val action = values.getOrElse("action", "").toString.toLowerCase

          if (expectedNodesAction.compareToIgnoreCase(action) == 0) {
            nodesStatus += extractedNode
            if (nodesStatus.size == curParticipents.size && expectedNodesAction == "stopped" && (nodesStatus -- curParticipents).isEmpty) {
              nodesStatus.clear
              expectedNodesAction = "distributed"

              // Set DISTRIBUTE Action on engineDistributionZkNodePath
              // Send all Unique keys to corresponding nodes 
              val distribute =
                ("action" -> "distribute") ~
                  ("adaptermaxpartitions" -> adapterMaxPartitions) ~
                  ("distributionmap" -> distributionMap)
              val sendJson = compact(render(distribute))
              zkcForSetData.setData().forPath(engineDistributionZkNodePath, sendJson.getBytes("UTF8"))
            }
          } else {
            val redStr = if (canRedistribute) "canRedistribute is true, Redistributing" else "canRedistribute is false, waiting until next call"
            // Got different action. May be re-distribute. For now any non-expected action we will redistribute
            LOG.info("UpdatePartitionsNodeData => eventType: %s, eventPath: %s, eventPathData: %s, Extracted Node:%s. Expected Action:%s, Recieved Action:%s %s.".format(eventType, eventPath, evntPthData, extractedNode, expectedNodesAction, action, redStr))
            if (canRedistribute)
              UpdatePartitionsIfNeededOnLeader(clusterStatus)
          }
        }
      } else if (eventType.compareToIgnoreCase("CHILD_REMOVED") == 0) {
        // Not expected this. Need to check what is going on
        LOG.error("UpdatePartitionsNodeData => eventType: %s, eventPath: %s, eventPathData: %s".format(eventType, eventPath, evntPthData))
      } else if (eventType.compareToIgnoreCase("CHILD_ADDED") == 0) {
        // Not doing anything here
      }
    } catch {
      case e: Exception => {
        LOG.error("Exception while UpdatePartitionsNodeData, reason %s, message %s".format(e.getCause, e.getMessage))
      }
    }
  }

  private def UpdatePartitionsIfNeededOnLeader(cs: ClusterStatus): Unit = lock.synchronized {
    if (cs.isLeader && cs.leader != nodeId) return // This is not leader, just return from here. This is same as (cs.leader != cs.nodeId)

    // Clear Previous Distribution Map
    distributionMap.clear
    adapterMaxPartitions.clear
    nodesStatus.clear
    expectedNodesAction = ""
    curParticipents = if (clusterStatus.participants != null) clusterStatus.participants.toSet else Set[String]()

    var tmpDistMap = ArrayBuffer[(String, scala.collection.mutable.Map[String, ArrayBuffer[String]])]()

    if (cs.participants != null) {
      // Create ArrayBuffer for each node participating at this moment
      cs.participants.foreach(p => {
        tmpDistMap += ((p, scala.collection.mutable.Map[String, ArrayBuffer[String]]()))
      })

      val allPartitionUniqueRecordKeys = ArrayBuffer[(String, String)]()

      // Get all PartitionUniqueRecordKey for all Input Adapters
      inputAdapters.foreach(ia => {
        val uk = ia.GetAllPartitionUniqueRecordKey
        val name = ia.UniqueName
        val ukCnt = if (uk != null) uk.size else 0
        adapterMaxPartitions(name) = ukCnt
        if (ukCnt > 0) {
          allPartitionUniqueRecordKeys ++= uk.map(k => {
            LOG.info("Unique Key in %s => %s".format(name, k))
            (name, k)
          })
        }
      })

      // Update New partitions for all nodes and Set the text
      var cntr: Int = 0
      val totalParticipents: Int = cs.participants.size
      if (allPartitionUniqueRecordKeys != null && allPartitionUniqueRecordKeys.size > 0) {
        LOG.info("allPartitionUniqueRecordKeys: %d".format(allPartitionUniqueRecordKeys.size))
        allPartitionUniqueRecordKeys.foreach(k => {
          // tmpDistMap(cntr % totalParticipents)._2 += k
          val af = tmpDistMap(cntr % totalParticipents)._2.getOrElse(k._1, null)
          if (af == null) {
            val af1 = new ArrayBuffer[String]
            af1 += k._2
            tmpDistMap(cntr % totalParticipents)._2(k._1) = af1
          } else {
            af += k._2
          }
          cntr += 1
        })
      }

      tmpDistMap.foreach(tup => {
        distributionMap(tup._1) = tup._2
      })
    }

    expectedNodesAction = "stopped"
    // Set STOP Action on engineDistributionZkNodePath
    val act = ("action" -> "stop")
    val sendJson = compact(render(act))
    zkcForSetData.setData().forPath(engineDistributionZkNodePath, sendJson.getBytes("UTF8"))
  }

  // Here Leader can change or Participants can change
  private def EventChangeCallback(cs: ClusterStatus): Unit = {
    clusterStatus = cs

    if (cs.isLeader && cs.leader == cs.nodeId) // Leader node
      UpdatePartitionsIfNeededOnLeader(cs)

    val isLeader = if (cs.isLeader) "true" else "false"
    LOG.info("NodeId:%s, IsLeader:%s, Leader:%s, AllParticipents:{%s}".format(cs.nodeId, isLeader, cs.leader, cs.participants.mkString(",")))
  }

  private def GetUniqueKeyValue(uk: String): String = {
    envCtxt.getAdapterUniqueKeyValue(uk)
  }

  private def StartNodeKeysMap(nodeKeysMap: Map[String, Any], receivedJsonStr: String, adapMaxPartsMap : Map[String, Int]): Boolean = {
    if (nodeKeysMap == null) return false
    inputAdapters.foreach(ia => {
      val name = ia.UniqueName
      try {
        val uniqKeysForAdap = nodeKeysMap.getOrElse(name, null)
        if (uniqKeysForAdap != null) {
          val uAK = uniqKeysForAdap.asInstanceOf[List[String]]
          val uKV = uAK.map(uk => { GetUniqueKeyValue(uk) })
          val maxParts = adapMaxPartsMap.getOrElse(name, 0)
          LOG.info("On Node %s for Adapter %s with Max Partitions %d UniqueKeys %s, UniqueValues %s".format(nodeId, name, maxParts, uAK.mkString(","), uKV.mkString(",")))
          ia.StartProcessing(maxParts, uAK.toArray, uKV.toArray)
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to print final Unique Keys. JsonString:" + receivedJsonStr)
        }
      }
    })

    return true
  }

  private def StartUniqueKeysForNode(uniqueKeysForNode: Any, receivedJsonStr: String, adapMaxPartsMap : Map[String, Int]): Boolean = {
    if (uniqueKeysForNode == null) return false
    try {
      uniqueKeysForNode match {
        case m: Map[_, _] => {
          // LOG.info("StartUniqueKeysForNode => Map: " + uniqueKeysForNode.toString)
          return StartNodeKeysMap(m.asInstanceOf[Map[String, Any]], receivedJsonStr, adapMaxPartsMap)
        }
        case l: List[Any] => {
          // LOG.info("StartUniqueKeysForNode => List: " + uniqueKeysForNode.toString)
          val data = l.asInstanceOf[List[Any]]
          var found = false
          data.foreach(d => {
            d match {
              case m1: Map[_, _] => {
                // LOG.info("StartUniqueKeysForNode => List, Map: " + uniqueKeysForNode.toString)
                val rv = StartNodeKeysMap(m1.asInstanceOf[Map[String, Any]], receivedJsonStr, adapMaxPartsMap)
                if (rv)
                  found = true
              }
              case _ => {
                LOG.error("Not found valid JSON for distribute:" + receivedJsonStr)
                return false
              }
            }
          })
          return found
        }
        case _ => {
          LOG.error("Not found valid JSON for distribute:" + receivedJsonStr)
          return false
        }
      }

      return true
    } catch {
      case e: Exception => {
        LOG.error("distribute action failed with reason %s, message %s".format(e.getCause, e.getMessage))
      }
    }
    return false
  }

  def GetAdaptersMaxPartitioinsMap(adaptermaxpartitions: Any): Map[String, Int] = {
    val adapterMax = scala.collection.mutable.Map[String, Int]() // Adapters & Max Partitions
    if (adaptermaxpartitions != null) {
      try {
        adaptermaxpartitions match {
          case m: Map[_, _] => {
            val mp = m.asInstanceOf[Map[String, Int]]
            mp.foreach(v => {
              adapterMax(v._1) = v._2
            })
          }
          case l: List[Any] => {
            // LOG.info("StartUniqueKeysForNode => List: " + uniqueKeysForNode.toString)
            val data = l.asInstanceOf[List[Any]]
            var found = false
            data.foreach(d => {
              d match {
                case m1: Map[_, _] => {
                  val mp = m1.asInstanceOf[Map[String, Int]]
                  mp.foreach(v => {
                    adapterMax(v._1) = v._2
                  })
                }
                case _ => {
                  LOG.error("Failed to get Max partitions for Adapters")
                }
              }
            })
          }
          case _ => {
            LOG.error("Failed to get Max partitions for Adapters")
          }
        }
      } catch {
        case e: Exception => {
          LOG.error("distribute action failed with reason %s, message %s".format(e.getCause, e.getMessage))
        }
      }
    }

    adapterMax.toMap
  }

  private def ActionOnAdaptersDistribution(receivedJsonStr: String): Unit = lock.synchronized {
    if (receivedJsonStr == null || receivedJsonStr.size == 0 /* || clusterStatus == null || clusterStatus.participants == null || clusterStatus.participants.size == 0 */ ) {
      // nothing to do
      return
    }

    LOG.info("ActionOnAdaptersDistribution => receivedJsonStr: " + receivedJsonStr)

    try {
      // Perform the action here (STOP or DISTRIBUTE for now)
      val json = parse(receivedJsonStr)
      if (json == null || json.values == null) // Not doing any action if not found valid json
        return
      val values = json.values.asInstanceOf[Map[String, Any]]
      val action = values.getOrElse("action", "").toString.toLowerCase

      action match {
        case "stop" => {
          // STOP all Input Adapters on local node
          inputAdapters.foreach(ia => {
            ia.StopProcessing
          })

          // Set STOPPED action in adaptersStatusPath + "/" + nodeId path
          val adaptrStatusPathForNode = adaptersStatusPath + "/" + nodeId
          val act = ("action" -> "stopped")
          val sendJson = compact(render(act))
          zkcForSetData.setData().forPath(adaptrStatusPathForNode, sendJson.getBytes("UTF8"))
        }
        case "distribute" => {
          var distributed = true
          try {
            // get Unique Keys for this nodeId
            // Distribution Map 
            val distributionMap = values.getOrElse("distributionmap", null)
            if (distributionMap != null) {
              val adapMaxPartsMap = GetAdaptersMaxPartitioinsMap(values.getOrElse("adaptermaxpartitions", null))
              distributionMap match {
                case m: Map[_, _] => {
                  val data = m.asInstanceOf[Map[String, Any]]
                  // LOG.info("ActionOnAdaptersDistribution => action => distribute. Map")
                  distributed = StartUniqueKeysForNode(data.getOrElse(nodeId, null), receivedJsonStr, adapMaxPartsMap)
                }
                case l: List[Any] => {
                  val data = l.asInstanceOf[List[Any]]
                  var found = false
                  data.foreach(d => {
                    d match {
                      case m1: Map[_, _] => {
                        val data1 = m1.asInstanceOf[Map[String, Any]]
                        // LOG.info("ActionOnAdaptersDistribution => action => distribute. List, Map. Map => " + data1.mkString(","))
                        val valid = StartUniqueKeysForNode(data1.getOrElse(nodeId, null), receivedJsonStr, adapMaxPartsMap)
                        if (valid)
                          found = true
                      }
                      case _ => {
                        LOG.error("Not found valid JSON for distribute:" + receivedJsonStr)
                        found = false
                      }
                    }
                  })
                  distributed = found
                }
                case _ => {
                  LOG.error("Not found valid JSON for distribute:" + receivedJsonStr)
                  distributed = false
                }
              }
            }

          } catch {
            case e: Exception => {
              LOG.error("distribute action failed with reason %s, message %s".format(e.getCause, e.getMessage))
              distributed = false
            }
          }

          val adaptrStatusPathForNode = adaptersStatusPath + "/" + nodeId
          var sentDistributed = false
          if (distributed) {
            try {
              // Set DISTRIBUTED action in adaptersStatusPath + "/" + nodeId path
              val act = ("action" -> "distributed")
              val sendJson = compact(render(act))
              zkcForSetData.setData().forPath(adaptrStatusPathForNode, sendJson.getBytes("UTF8"))
              sentDistributed = true
            } catch {
              case e: Exception => {
                LOG.error("distribute action failed with reason %s, message %s".format(e.getCause, e.getMessage))
              }
            }
          }

          if (sentDistributed == false) {
            // Set RE-DISTRIBUTED action in adaptersStatusPath + "/" + nodeId path
            val act = ("action" -> "re-distribute")
            val sendJson = compact(render(act))
            zkcForSetData.setData().forPath(adaptrStatusPathForNode, sendJson.getBytes("UTF8"))
          }
        }
        case _ => {
          LOG.info("No action performed, because of invalid action %s in json %s".format(action, receivedJsonStr))
        }
      }

      // 
    } catch {
      case e: Exception => {
        LOG.info("Found invalid JSON: %s".format(receivedJsonStr))
      }
    }

  }

  private def ParticipentsAdaptersStatus(eventType: String, eventPath: String, eventPathData: Array[Byte], childs: Array[(String, Array[Byte])]): Unit = {
    if (clusterStatus.isLeader == false || clusterStatus.leader != clusterStatus.nodeId) // Not Leader node
      return
    UpdatePartitionsNodeData(eventType, eventPath, eventPathData)
  }

  def Init(nodeId1: String, zkConnectString1: String, engineLeaderZkNodePath1: String, engineDistributionZkNodePath1: String, adaptersStatusPath1: String, inputAdap: ArrayBuffer[InputAdapter], outputAdap: ArrayBuffer[OutputAdapter], statusAdap: ArrayBuffer[OutputAdapter], enviCxt: EnvContext, zkSessionTimeoutMs1: Int, zkConnectionTimeoutMs1: Int): Unit = {
    nodeId = nodeId1.toLowerCase
    zkConnectString = zkConnectString1
    engineLeaderZkNodePath = engineLeaderZkNodePath1
    engineDistributionZkNodePath = engineDistributionZkNodePath1
    adaptersStatusPath = adaptersStatusPath1
    zkSessionTimeoutMs = zkSessionTimeoutMs1
    zkConnectionTimeoutMs = zkConnectionTimeoutMs1
    inputAdapters = inputAdap
    outputAdapters = outputAdap
    statusAdapters = statusAdap
    envCtxt = enviCxt

    if (zkConnectString != null && zkConnectString.isEmpty() == false && engineLeaderZkNodePath != null && engineLeaderZkNodePath.isEmpty() == false && engineDistributionZkNodePath != null && engineDistributionZkNodePath.isEmpty() == false) {
      try {
        val adaptrStatusPathForNode = adaptersStatusPath + "/" + nodeId
        LOG.info("ZK Connecting. adaptrStatusPathForNode:%s, zkConnectString:%s, engineLeaderZkNodePath:%s, engineDistributionZkNodePath:%s".format(adaptrStatusPathForNode, zkConnectString, engineLeaderZkNodePath, engineDistributionZkNodePath))
        CreateClient.CreateNodeIfNotExists(zkConnectString, engineDistributionZkNodePath) // Creating 
        CreateClient.CreateNodeIfNotExists(zkConnectString, adaptrStatusPathForNode) // Creating path for Adapter Statues
        zkcForSetData = CreateClient.createSimple(zkConnectString, zkSessionTimeoutMs, zkConnectionTimeoutMs)
        zkAdapterStatusNodeListener = new ZooKeeperListener
        zkAdapterStatusNodeListener.CreatePathChildrenCacheListener(zkConnectString, adaptersStatusPath, false, ParticipentsAdaptersStatus, zkSessionTimeoutMs, zkConnectionTimeoutMs)
        zkEngineDistributionNodeListener = new ZooKeeperListener
        zkEngineDistributionNodeListener.CreateListener(zkConnectString, engineDistributionZkNodePath, ActionOnAdaptersDistribution, zkSessionTimeoutMs, zkConnectionTimeoutMs)
        try {
          Thread.sleep(500)
        } catch {
          case e: Exception => {
            // Not doing anything
          }
        }
        SetCanRedistribute(true)
        zkLeaderLatch = new ZkLeaderLatch(zkConnectString, engineLeaderZkNodePath, nodeId, EventChangeCallback, zkSessionTimeoutMs, zkConnectionTimeoutMs)
        zkLeaderLatch.SelectLeader
        /*
        // Set RE-DISTRIBUTED action in adaptersStatusPath + "/" + nodeId path
        val act = ("action" -> "re-distribute")
        val sendJson = compact(render(act))
        zkcForSetData.setData().forPath(adaptrStatusPathForNode, sendJson.getBytes("UTF8"))
        */
      } catch {
        case e: Exception => {
          LOG.error("Failed to initialize ZooKeeper Connection. Reason:%s Message:%s".format(e.getCause, e.getMessage))
          throw e
        }
      }
    } else {
      LOG.error("Not connected to elect Leader and not distributing data between nodes.")
    }
  }

  def Shutdown: Unit = {
    if (zkLeaderLatch != null)
      zkLeaderLatch.Shutdown
    zkLeaderLatch = null
    if (zkEngineDistributionNodeListener != null)
      zkEngineDistributionNodeListener.Shutdown
    zkEngineDistributionNodeListener = null
    if (zkAdapterStatusNodeListener != null)
      zkAdapterStatusNodeListener.Shutdown
    zkAdapterStatusNodeListener = null
    if (zkcForSetData != null)
      zkcForSetData.close
    zkcForSetData = null
  }
}


