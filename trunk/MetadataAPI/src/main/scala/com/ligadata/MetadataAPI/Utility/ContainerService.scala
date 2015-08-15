package main.scala.com.ligadata.MetadataAPI.Utility

import java.io.File

import com.ligadata.MetadataAPI.MetadataAPIImpl

import scala.io.Source

import org.apache.log4j._
/**
 * Created by dhaval on 8/7/15.
 */
object ContainerService {
  private val userid: Option[String] = Some("metadataapi")
  val loggerName = this.getClass.getName
  lazy val logger = Logger.getLogger(loggerName)

  def addContainer(input: String): String ={
    var response = ""
    var containerFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      containerFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("CONTAINER_FILES_DIR")
      if (containerFileDir == null) {
        response = "CONTAINER_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(containerFileDir) match {
          case true => {
            //get all files with json extension
            val containers: Array[File] = new java.io.File(containerFileDir).listFiles.filter(_.getName.endsWith(".json"))
            containers.length match {
              case 0 => {
                response="Container not found at " + containerFileDir
              }
              case option => {
                val containerDefs = getUserInputFromMainMenu(containers)
                for (containerDef <- containerDefs) {
                  response += MetadataAPIImpl.AddContainer(containerDef.toString, "JSON", userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var container = new File(input.toString)
      val containerDef = Source.fromFile(container).mkString
      response = MetadataAPIImpl.AddContainer(containerDef, "JSON", userid)
    }
    //Got the container.
    response
  }

  def updateContainer(input: String): String ={
    var response = ""
    var containerFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      containerFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("CONTAINER_FILES_DIR")
      if (containerFileDir == null) {
        response = "CONTAINER_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(containerFileDir) match {
          case true => {
            //get all files with json extension
            val containers: Array[File] = new java.io.File(containerFileDir).listFiles.filter(_.getName.endsWith(".json"))
            containers.length match {
              case 0 => {
                response="Container not found at " + containerFileDir
              }
              case option => {
                val containerDefs = getUserInputFromMainMenu(containers)
                for (containerDef <- containerDefs) {
                  response += MetadataAPIImpl.UpdateContainer(containerDef.toString, "JSON", userid)
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Message directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var container = new File(input.toString)
      val containerDef = Source.fromFile(container).mkString
      response = MetadataAPIImpl.AddContainer(containerDef, "JSON", userid)
    }
    //Got the container.
    response
  }

  def getContainer: String ={
    var response=""
      val containerKeys = MetadataAPIImpl.GetAllContainersFromCache(true, None)

      if (containerKeys.length == 0) {
        response="Sorry, No containers available in the Metadata"
      }else{
        println("\nPick the container from the following list: ")
        var srNo = 0
        for(containerKey <- containerKeys){
          srNo+=1
          println("["+srNo+"] "+containerKey)
        }
        print("\nEnter your choice: ")
        val choice: Int = readInt()

        if (choice < 1 || choice > containerKeys.length) {
          response="Invalid choice " + choice + ",start with main menu..."
        }else{
          val containerKey = containerKeys(choice - 1)
          val contKeyTokens = containerKey.split("\\.")
          val contNameSpace = contKeyTokens(0)
          val contName = contKeyTokens(1)
          val contVersion = contKeyTokens(2)
          response=MetadataAPIImpl.GetContainerDefFromCache(contNameSpace, contName, "JSON", contVersion, userid)
        }
      }
      response
  }

  def getAllContainers: String ={
    var response = ""
    try {
      val containerKeys: Array[String] = MetadataAPIImpl GetAllContainersFromCache(true, userid)
      if (containerKeys.length == 0) {
        response = "Sorry, No containers are available in the Metadata"
      } else {
        var srno = 0
        //println("List of messages:")
        for (containerKey <- containerKeys) {
          //srno += 1
          //println("[" + srno + "] " + containerKey)
          response += containerKey + "\n"
        }
      }
    } catch {
      case e: Exception => {
        response = e.getStackTrace.toString
      }
    }
    response
  }

  def removeContainer: String ={
    var response = ""
    try{
    val contKeys = MetadataAPIImpl.GetAllContainersFromCache(true, None)

    if (contKeys.length == 0) {
      response=("Sorry, No containers available in the Metadata")
    }else{
      println("\nPick the container to be deleted from the following list: ")
      var seq = 0
      contKeys.foreach(key => { seq += 1; println("[" + seq + "] " + key) })

      print("\nEnter your choice: ")
      val choice: Int = readInt()

      if (choice < 1 || choice > contKeys.length) {
        response=("Invalid choice " + choice + ",start with main menu...")
      }else{
        val contKey = contKeys(choice - 1)
        val contKeyTokens = contKey.split("\\.")
        val contNameSpace = contKeyTokens(0)
        val contName = contKeyTokens(1)
        val contVersion = contKeyTokens(2)
        response = MetadataAPIImpl.RemoveContainer(contNameSpace, contName, contVersion.toLong, userid)
      }
    }
  } catch {
    case e: NumberFormatException => {
      response=("\n Entry not in desired format. Please enter only one choice correctly")
    }
    case e: Exception => {
      response=(e.toString)
    }
  }
    response
  }

  //utilities
  def IsValidDir(dirName: String): Boolean = {
    val iFile = new File(dirName)
    if (!iFile.exists) {
      println("The File Path (" + dirName + ") is not found: ")
      false
    } else if (!iFile.isDirectory) {
      println("The File Path (" + dirName + ") is not a directory: ")
      false
    } else
      true
  }

  def   getUserInputFromMainMenu(containers: Array[File]): Array[String] = {
    var listOfContainerDef: Array[String] = Array[String]()
    var srNo = 0
    println("\nPick a Container Definition file(s) from below choices\n")
    for (container <- containers) {
      srNo += 1
      println("[" + srNo + "]" + container)
    }
    print("\nEnter your choice(If more than 1 choice, please use commas to seperate them): \n")
    val userOptions: List[Int] = Console.readLine().filter(_ != '\n').split(',').filter(ch => (ch != null && ch != "")).map(_.trim.toInt).toList
    //check if user input valid. If not exit
    for (userOption <- userOptions) {
      userOption match {
        case userOption if (1 to srNo).contains(userOption) => {
          //find the file location corresponding to the message
          var container = containers(userOption - 1)
          //process message
          val containerDef = Source.fromFile(container).mkString
          listOfContainerDef = listOfContainerDef :+ containerDef
        }
        case _ => {
          println("Unknown option: ")
        }
      }
    }
    listOfContainerDef
  }
}