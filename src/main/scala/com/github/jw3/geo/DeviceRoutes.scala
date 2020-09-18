package com.github.jw3.geo

import akka.actor.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.github.jw3.geo.Api.Commands
import com.github.jw3.geo.Api.Commands.AddDevice
import com.github.jw3.geo.Api.Events.DeviceAdded
import com.github.jw3.geo.Api.Responses.DeviceExists
import com.github.jw3.geo.DeviceRoutes.HookCall
import geotrellis.vector.Point
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

object DeviceRoutes {
  final case class HookCall(pos: String)
  object HookCall extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[HookCall] = jsonFormat1(HookCall.apply)
  }
}

trait DeviceRoutes {
  import akka.http.scaladsl.server.Directives._

  def deviceRoutes(devices: ActorRef)(implicit to: Timeout): Route =
    extractLog { logger ⇒
      extractExecutionContext { implicit ec ⇒
        pathPrefix("api") {
          path("device" / Segment) { id ⇒
            post {
              val res = (devices ? AddDevice(id)).map {
                case DeviceAdded(_) ⇒ StatusCodes.Created
                case DeviceExists(_) ⇒ StatusCodes.OK
                case _ ⇒ StatusCodes.InternalServerError
              }
              complete(res)
            }
          } ~
            path("device" / Segment / "move") { id ⇒
              post {
                entity(as[HookCall]) { e ⇒
                  val Array(lat, lon) = e.pos.split(":")
                  devices ! Commands.MoveDevice(id, Point(lon.toDouble, lat.toDouble))
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
