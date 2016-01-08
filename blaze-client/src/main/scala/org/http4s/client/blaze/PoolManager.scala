package org.http4s
package client
package blaze

import org.log4s.getLogger

import scala.collection.mutable
import scalaz.{-\/, \/-, \/}
import scalaz.syntax.either._
import scalaz.concurrent.Task

private object PoolManager {
  type Callback[A] = Throwable \/ A => Unit
  case class Waiting(key: RequestKey, callback: Callback[BlazeClientStage])
}
import PoolManager._

private final class PoolManager(builder: ConnectionBuilder,
                                maxTotal: Int)
  extends ConnectionManager {

  private[this] val logger = getLogger
  private var isClosed = false
  private var allocated = 0
  private val idleQueue = new mutable.Queue[BlazeClientStage]
  private val waitQueue = new mutable.Queue[Waiting]

  private def stats =
    s"allocated=${allocated} idleQueue.size=${idleQueue.size} waitQueue.size=${waitQueue.size}"

  private def createConnection(key: RequestKey): Unit = {
    if (allocated < maxTotal) {
      allocated += 1
      logger.debug(s"Creating connection: ${stats}")
      Task.fork(builder(key)).runAsync {
        case \/-(stage) =>
          logger.debug(s"Submitting fresh connection to pool: ${stats}")
          returnConnection(key, stage)
        case -\/(t) =>
          logger.error(t)("Error establishing client connection")
          disposeConnection(key, None)
      }
    }
    else {
      logger.debug(s"Too many connections open.  Can't create a connection: ${stats}")
    }
  }

  def getClient(key: RequestKey): Task[BlazeClientStage] = Task.async { callback =>
    logger.debug(s"Requesting connection: ${stats}")
    synchronized {
      if (!isClosed) {
        def go(): Unit = {
          idleQueue.dequeueFirst(_.requestKey == key) match {
            case Some(stage) if !stage.isClosed =>
              logger.debug(s"Recycling connection: ${stats}")
              callback(stage.right)
            case Some(closedStage) =>
              logger.debug(s"Evicting closed connection: ${stats}")
              allocated -= 1
              go()
            case None if idleQueue.nonEmpty =>
              logger.debug(s"No connections available for the desired key.  Evicting and creating a connection: ${stats}")
              allocated -= 1
              idleQueue.dequeue().shutdown()
              createConnection(key)
              waitQueue.enqueue(Waiting(key, callback))
            case None =>
              logger.debug(s"No connections available.  Waiting on new connection: ${stats}")
              createConnection(key)
              waitQueue.enqueue(Waiting(key, callback))
          }
        }
        go()
      }
      else
        callback(new IllegalStateException("Connection pool is closed").left)
    }
  }

  private def returnConnection(key: RequestKey, stage: BlazeClientStage) =
    synchronized {
      logger.debug(s"Reallocating connection: ${stats}")
      if (!isClosed) {
        if (!stage.isClosed) {
          waitQueue.dequeueFirst(_.key == key) match {
            case Some(Waiting(_, callback)) =>
              logger.debug(s"Fulfilling waiting connection request: ${stats}")
              callback(stage.right)
            case None =>
              logger.debug(s"Returning idle connection to pool: ${stats}")
              idleQueue.enqueue(stage)
          }
        }
        else if (waitQueue.nonEmpty) {
          logger.debug(s"Replacing closed connection: ${stats}")
          allocated -= 1
          createConnection(key)
        }
        else {
          logger.debug(s"Connection was closed, but nothing to do. Shrinking pool: ${stats}")
          allocated -= 1
        }
      }
      else if (!stage.isClosed) {
        logger.debug(s"Shutting down connection after pool closure: ${stats}")
        stage.shutdown()
        allocated -= 1
      }
    }

  private def disposeConnection(key: RequestKey, stage: Option[BlazeClientStage]) = {
    logger.debug(s"Disposing of connection: ${stats}")
    synchronized {
      allocated -= 1
      stage.foreach { s => if (!s.isClosed()) s.shutdown() }
      if (!isClosed && waitQueue.nonEmpty) {
        logger.debug(s"Replacing failed connection: ${stats}")
        createConnection(key)
      }
    }
  }

  def shutdown() = Task.delay {
    logger.info(s"Shutting down connection pool: ${stats}")
    synchronized {
      if (!isClosed) {
        isClosed = true
        idleQueue.foreach(_.shutdown())
        allocated = 0
      }
    }
  }

  override def releaseClient(requestKey: RequestKey, stage: BlazeClientStage, keepAlive: Boolean): Unit = {
    if (keepAlive)
      returnConnection(requestKey, stage)
    else
      disposeConnection(requestKey, Some(stage))
  }
}
