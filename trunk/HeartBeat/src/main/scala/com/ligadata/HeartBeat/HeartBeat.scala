package com.ligadata.HeartBeat

import org.apache.log4j.Logger
import com.ligadata.ZooKeeper._
import org.apache.curator.framework._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import scala.actors.threadpool.{ Executors, ExecutorService }

class HeartBeatUtil {
  private[this] val LOG = Logger.getLogger(getClass);
  class MainInfo {
    var name: String = null
    var uniqueId: Long = 0
    var lastSeen: String = null
    var startTime: String = null
  }

  class ComponentInfo {
    var typ: String = null
    var name: String = null
    var uniqueId: Long = 0
    var lastSeen: String = null
    var startTime: String = null
  }

  class MetricInfo {
    var lastSeen: String = null
  }

  private[this] val _setDataLockObj = new Object()
  private[this] var _exec: ExecutorService = null
  private[this] var _zkcForSetData: CuratorFramework = null
  private[this] var _nodeId: String = null
  private[this] var _zkConnectString: String = null
  private[this] var _zkNodePath: String = null
  private[this] var _zkSessionTimeoutMs: Int = 0
  private[this] var _zkConnectionTimeoutMs: Int = 0
  private[this] var _refreshTimeInMs: Int = 0
  private[this] var _cntr: Long = 1
  private[this] val _mainInfo = new MainInfo
  private[this] val _components = collection.mutable.Map[(String, String), ComponentInfo]()
  private[this] val _metrics = collection.mutable.Map[(String, String), MetricInfo]()

  LOG.warn("Instantiated HeartBeat")
  
  def Init(nodeId: String, zkConnectString: String, zkNodePath: String, zkSessionTimeoutMs: Int, zkConnectionTimeoutMs: Int, refreshTimeInMs: Int): Unit = {
    LOG.warn("Called HeartBeat Init")
    _nodeId = nodeId
    _zkConnectString = zkConnectString
    _zkNodePath = zkNodePath
    _zkSessionTimeoutMs = zkSessionTimeoutMs
    _zkConnectionTimeoutMs = zkConnectionTimeoutMs
    _refreshTimeInMs = refreshTimeInMs

    if (_zkcForSetData != null && _exec != null)
      Shutdown

    CreateClient.CreateNodeIfNotExists(zkConnectString, zkNodePath) // Creating the path if missing
      
    _zkcForSetData = CreateClient.createSimple(zkConnectString, zkSessionTimeoutMs, zkConnectionTimeoutMs)
    _exec = Executors.newFixedThreadPool(1)

    _exec.execute(new Runnable() {
      override def run() = {
        var startTime = System.currentTimeMillis

        while (_exec.isShutdown == false) {
          Thread.sleep(250) // Waiting for 250 milli secs
          val curTime = System.currentTimeMillis
          val diffTm = curTime - startTime
          if (_exec.isShutdown == false && diffTm >= _refreshTimeInMs) {
            startTime = curTime
            // Sent the stuff in ZK
            SetNewDataInZk
          }
        }
      }
    })
  }

  def SetComponentData(sType: String, sName: String): Unit = {
    LOG.warn("Called HeartBeat SetComponentData")
    _setDataLockObj.synchronized {
      val key = (sType.toLowerCase, sName.toLowerCase)
      val oldComp = _components.getOrElse(key, null)
      val compNewData = if (oldComp == null) new ComponentInfo else oldComp

      compNewData.uniqueId = _cntr
      _cntr = _cntr + 1
      compNewData.lastSeen = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(System.currentTimeMillis))
      if (compNewData.startTime == null)
        compNewData.startTime = compNewData.lastSeen
      compNewData.typ = sType
      compNewData.name = sName
      _components(key) = compNewData
    }
  }

  def SetMainData(sName: String): Unit = {
    LOG.warn("Called HeartBeat SetMainData")
    _setDataLockObj.synchronized {
      _mainInfo.name = sName
      _mainInfo.uniqueId = _cntr
      _cntr = _cntr + 1
      _mainInfo.lastSeen = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(System.currentTimeMillis))
      if (_mainInfo.startTime == null)
        _mainInfo.startTime = _mainInfo.lastSeen
    }
  }

  def Shutdown: Unit = {
    LOG.warn("Called HeartBeat Shutdown")
    _setDataLockObj.synchronized {
      if (_exec != null)
        _exec.shutdown
      _exec = null
      if (_zkcForSetData != null)
        _zkcForSetData.close
      _zkcForSetData = null
      _mainInfo.startTime = null
      _mainInfo.lastSeen = null
      _components.clear
      _metrics.clear
    }
  }

  private def SetNewDataInZk: Unit = {
    LOG.warn("Called HeartBeat SetNewDataInZk. Setting data @" + _zkNodePath)
    _setDataLockObj.synchronized {
      if (_zkcForSetData != null && _mainInfo != null && _mainInfo.name != null) {
        val dataJson =
          ("Name" -> _mainInfo.name) ~
            ("UniqueId" -> _mainInfo.uniqueId) ~
            ("LastSeen" -> _mainInfo.lastSeen) ~
            ("StartTime" -> _mainInfo.startTime) ~
            ("Components" -> _components.toList.map(kv =>
              ("Type" -> kv._2.typ) ~
                ("Name" -> kv._2.name) ~
                ("UniqueId" -> kv._2.uniqueId) ~
                ("LastSeen" -> kv._2.lastSeen) ~
                ("StartTime" -> kv._2.startTime))) ~
            ("Metrics" -> _metrics.toList.map(kv =>
              ("LastSeen" -> kv._2.lastSeen)))

        val data = compact(render(dataJson)).getBytes
        _zkcForSetData.setData().forPath(_zkNodePath, data)
      }
    }
  }
}

