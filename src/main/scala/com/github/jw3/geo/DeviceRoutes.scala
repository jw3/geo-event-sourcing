package com.github.jw3.geo

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.github.jw3.geo.Api.Commands.AddDevice
import com.github.jw3.geo.Api.Events.DeviceAdded
import com.github.jw3.geo.Api.Responses.DeviceExists
import com.github.jw3.geo.Api.{Commands, Queries}
import geotrellis.vector.Point
import spray.json._

object DeviceRoutes {
  sealed trait IncomingEvent

  final case class HookCall(id: String, event: String, data: String, when: String)
  object HookCall extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[HookCall] = jsonFormat4(HookCall.apply)
  }

  case class DeprecatedMoveEvent(id: String, x: String, y: String) extends IncomingEvent
  object DeprecatedMoveEvent extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[DeprecatedMoveEvent] = jsonFormat3(DeprecatedMoveEvent.apply)
  }

  case class MoveEvent(id: String, x: Double, y: Double) extends IncomingEvent
  object MoveEvent extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[MoveEvent] = jsonFormat3(MoveEvent.apply)
  }

  case class ReadyEvent(id: String, version: String) extends IncomingEvent
  object ReadyEvent extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[ReadyEvent] = jsonFormat2(ReadyEvent.apply)
  }

  case class PingEvent(id: String) extends IncomingEvent
  object PingEvent extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[PingEvent] = jsonFormat1(PingEvent.apply)
  }

  case class DisconnectEvent(id: String) extends IncomingEvent
  object DisconnectEvent extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[DisconnectEvent] = jsonFormat1(DisconnectEvent.apply)
  }

  case class MalformedEvent(payload: String) extends IncomingEvent
  case class UnsupportedEvent(payload: String) extends IncomingEvent
}

trait DeviceRoutes {
  import akka.http.scaladsl.server.Directives._

  def deviceRoutes(devices: ActorRef)(implicit to: Timeout): Route =
    extractLog { logger ⇒
      extractExecutionContext { implicit ec ⇒
        pathPrefix("api") {
          pathPrefix("device") {
            path("ready") {
              post {
                entity(as[DeviceRoutes.ReadyEvent]) { e ⇒
                  devices ! Commands.AddDevice(e.id, Some(e.version))
                  complete(StatusCodes.OK)
                }
              }
            } ~
            path("ping") {
              post {
                entity(as[DeviceRoutes.PingEvent]) { e ⇒
                  devices ! Commands.HeartBeat(e.id)
                  complete(StatusCodes.OK)
                }
              }
            } ~
            path("move") {
              post {
                entity(as[DeviceRoutes.DeprecatedMoveEvent]) { e ⇒
                  devices ! Commands.MoveDevice(e.id, Point(e.x.toDouble, e.y.toDouble))
                  complete(StatusCodes.Accepted)
                } ~
                  entity(as[DeviceRoutes.MoveEvent]) { e ⇒
                    devices ! Commands.MoveDevice(e.id, Point(e.x, e.y))
                    complete(StatusCodes.Accepted)
                  } ~
                  entity(as[DeviceRoutes.HookCall]) { e ⇒
                    val Array(x, y) = e.data.split(":")
                    devices ! Commands.MoveDevice(e.id, Point(x.toDouble, y.toDouble))
                    complete(StatusCodes.Accepted)
                  } ~
                  extractRequest { r ⇒
                    complete(StatusCodes.Forbidden)
                  }
              }
            } ~
              path(Segment) { id ⇒
                get {
                  complete {
                    (devices ? Queries.GetDevicePosition(id)).map { _ ⇒
                      StatusCodes.OK
                    }
                  }
                } ~
                  post {
                    val res = (devices ? AddDevice(id, None)).map {
                      case DeviceAdded(_) ⇒ StatusCodes.Created
                      case DeviceExists(_) ⇒ StatusCodes.OK
                      case _ ⇒ StatusCodes.InternalServerError
                    }
                    complete(res)
                  }
              } ~
              path(Segment / "move") { id ⇒
                post {
                  entity(as[DeviceRoutes.HookCall]) { e ⇒
                    val Array(x, y) = e.data.split(":")
                    devices ! Commands.MoveDevice(id, Point(x.toDouble, y.toDouble))
                    complete(StatusCodes.Accepted)
                  }
                }
              } ~
              path("device" / Segment / "track" / Segment) { (id, op) ⇒
                post {
                  op match {
                    case "start" ⇒
                      devices ! Commands.StartTracking(id)
                      complete(StatusCodes.OK)
                    case "stop" ⇒
                      devices ! Commands.StopTracking(id)
                      complete(StatusCodes.OK)
                    case _ ⇒ complete(StatusCodes.NotFound)
                  }
                }
              }
          } ~
            path("health") {
              get {
                complete(StatusCodes.OK)
              }
            }
        }
      }
    }
}
