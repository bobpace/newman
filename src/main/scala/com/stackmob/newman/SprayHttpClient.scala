/**
 * Copyright 2012-2013 StackMob
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stackmob.newman

import akka.actor._
import spray.http.{Uri,
  HttpHeaders => SprayHttpHeaders,
  HttpRequest => SprayHttpRequest,
  HttpResponse => SprayHttpResponse,
  HttpMethod => SprayHttpMethod,
  HttpMethods => SprayHttpMethods,
  HttpBody => SprayHttpBody,
  ContentTypes => SprayContentTypes,
  ContentType => SprayContentType,
  HttpEntity => SprayHttpEntity,
  EmptyEntity => SprayEmptyEntity}
import spray.http.HttpHeaders.RawHeader
import spray.http.parser.HttpParser
import java.net.URL
import com.stackmob.newman.request._
import com.stackmob.newman.response._
import scala.concurrent.{ExecutionContext, Future}
import scalaz.Scalaz._
import com.stackmob.newman.response.HttpResponse
import scalaz.NonEmptyList
import akka.io.IO
import akka.pattern.ask
import spray.can.Http
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import com.stackmob.newman.Exceptions.InternalException

class SprayHttpClient(actorSystem: ActorSystem = SprayHttpClient.DefaultActorSystem,
                      defaultContentType: SprayContentType = SprayContentTypes.`application/json`,
                      timeout: Timeout = Timeout(5, TimeUnit.SECONDS)) extends HttpClient {

  import SprayHttpClient._

  private implicit val clientActorSystem: ActorSystem = actorSystem
  private implicit val clientTimeout: Timeout = timeout

  private def perform(method: SprayHttpMethod,
                      url: URL,
                      headers: Headers,
                      rawBody: RawBody = RawBody.empty): Future[HttpResponse] = {
    import com.stackmob.newman.concurrent.SequentialExecutionContext
    IO(Http)
      .ask(request(method, url, headers, rawBody))
      .mapTo[SprayHttpResponse]
      .toNewman(defaultContentType)
      .transform(identity[HttpResponse], {
        case c: ClassCastException => InternalException("Unexpected return type", c.some)
        case t: Throwable => t
      })
  }

  private def request(method: SprayHttpMethod,
                      url: URL,
                      headers: Headers,
                      rawBody: RawBody): SprayHttpRequest = {
    val headerList = headers.map { headerNel =>
      val lst = headerNel.list
      lst.filterNot(isSprayProtectedHeader(_)).map { hdr =>
        RawHeader(hdr._1, hdr._2)
      }
    } | Nil

    val entity: SprayHttpEntity = {
      if (rawBody.length === 0) {
        SprayEmptyEntity
      } else {
        val contentType = headers.getContentType(defaultContentType)
        SprayHttpEntity(contentType, rawBody)
      }
    }

    SprayHttpRequest(method, Uri(url.toString), headerList, entity)
  }

  /*
  Spray designates a subset of its headers to be "protected" in creation, such that attempting to create one (even via
  a RawHeader) will raise a warning at runtime. These headers should not be created directly, but rather should be
  handled via the respective pathways that set them (e.g. setting the Content-Type of an HttpEntity, which sets the
  Content-Type header) or ignored altogether.

  See https://github.com/spray/spray/blob/master/spray-http/src/main/scala/spray/http/HttpHeader.scala for details.
   */
  private lazy val sprayProtectedHeaders = List(
    SprayHttpHeaders.Connection,  // this is not explicitly defined as a protected header, but still raises warns
    SprayHttpHeaders.`Content-Length`,
    SprayHttpHeaders.`Content-Type`,
    SprayHttpHeaders.Date,
    SprayHttpHeaders.Server,
    SprayHttpHeaders.`Transfer-Encoding`,
    SprayHttpHeaders.`User-Agent`
  ).map(_.lowercaseName)
  private def isSprayProtectedHeader(h: Header): Boolean = sprayProtectedHeaders.contains(h._1.toLowerCase)

  override def get(url: URL, headers: Headers): GetRequest = {
    GetRequest(url, headers) {
      perform(SprayHttpMethods.GET, url, headers)
    }
  }

  override def post(url: URL, headers: Headers, body: RawBody): PostRequest = {
    PostRequest(url, headers, body) {
      perform(SprayHttpMethods.POST, url, headers, body)
    }
  }

  override def put(url: URL, headers: Headers, body: RawBody): PutRequest = {
    PutRequest(url, headers, body) {
      perform(SprayHttpMethods.PUT, url, headers, body)
    }
  }

  override def delete(url: URL, headers: Headers): DeleteRequest = {
    DeleteRequest(url, headers) {
      perform(SprayHttpMethods.DELETE, url, headers)
    }
  }

  override def head(url: URL, headers: Headers): HeadRequest = {
    HeadRequest(url, headers) {
      perform(SprayHttpMethods.HEAD, url, headers)
    }
  }

}

object SprayHttpClient {

  private[SprayHttpClient] lazy val DefaultActorSystem = ActorSystem("spray-http-client")

  implicit class RichHeaders(headers: Headers) {
    def getContentType(defaultContentType: SprayContentType): SprayContentType = {
      headers.flatMap { lst: HeaderList =>
        val results = lst.list.map(x => HttpParser.parseHeader(RawHeader(x._1, x._2))).collect({ case Right(c) => c })
        results.collect({ case c @ SprayHttpHeaders.`Content-Type`(_) => c }).map(_.contentType).headOption
      } | defaultContentType
    }
  }

  private[SprayHttpClient] implicit class RichSprayHttpResponse(resp: SprayHttpResponse) {
    def toNewmanHttpResponse(defaultContentType: SprayContentType): Option[HttpResponse] = {
      for {
        code <- HttpResponseCode.fromInt(resp.status.intValue)
        rawHeaders <- Option(resp.headers)
        headers <- Option {
          rawHeaders.map { hdr =>
            hdr.name -> hdr.value
          }.toNel
        }
        entity <- Option(resp.entity)
        body <- Option(entity.buffer)
      } yield {
        val contentType = entity.some.collect {
          case SprayHttpBody(cType, _) => "Content-Type" -> cType.value
        } | {
          "Content-Type" -> defaultContentType.value
        }

        val headersPlusContentType = headers.map { hdrNel =>
          hdrNel.<::(contentType)
        } | {
          NonEmptyList(contentType)
        }

        HttpResponse(code, headersPlusContentType.some, body)
      }
    }
  }

  private[SprayHttpClient] implicit class RichPipeline(pipeline: Future[SprayHttpResponse]) {
    def toNewman(defaultContentType: SprayContentType)(implicit ctx: ExecutionContext): Future[HttpResponse] = {
      pipeline.flatMap { res =>
        res.toNewmanHttpResponse(defaultContentType).map { newmanResp =>
          Future.successful(newmanResp)
        } | {
          Future.failed(new InvalidSprayResponse(res.status.intValue))
        }
      }
    }
  }

  private[SprayHttpClient] class InvalidSprayResponse(code: Int) extends Exception(s"Invalid spray HTTP response with code $code")

}
