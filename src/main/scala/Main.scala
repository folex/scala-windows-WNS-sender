import java.net.{InetSocketAddress, URLEncoder}
import java.nio.charset.Charset
import java.util.UUID

import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.{Http, RequestBuilder}
import com.twitter.util.Await
import com.twitter.util.TimeConversions._
import org.jboss.netty.buffer.ChannelBuffers._
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.parsing.json.JSON

object Main extends App {
  val url = Try(args(0)).getOrElse {
    println("Usage: program url [message]\nurl is required"); System.exit(1); ""
  }

  val message =  Try {
    val fname = args(1)
    io.Source.fromFile(fname).mkString("")
  }.getOrElse(throw new Exception(s"Filename isn't specified or incorrect"))

  val wnsType = Try(args(2)).getOrElse("wns/toast")

  println(s"Message is \n $message")

  WNS.send(url, message, wnsType)
}

object WNS {
  def send(urlArg: String, message: String, wnsType: String) = {
    val host = "db3.notify.windows.com"
    val subscriptionUrl = urlArg //Windows phone app will give you that url
    println(s"Channel url is $subscriptionUrl")
    val accessTokenHost = "login.live.com"
    val accessTokenUrl = "https://login.live.com/accesstoken.srf"
    val rawSecret: String = "" //Looks like Op+g1dr/NDcGne4kWwizMrz2qBnmlsFHCb
    val clientSecret = URLEncoder.encode(rawSecret, "UTF8")
    val rawSid: String = "" //Looks like ms-app://s-2-16-5-278192332-5453453411-55767567657-2321312-43434243-4343432-434343443
    val sid = URLEncoder.encode(rawSid, "UTF8")

    def clientService: Service[HttpRequest, HttpResponse] = {
      ClientBuilder()
        .hosts(new InetSocketAddress(host, 443))
        .codec(Http())
        .tcpConnectTimeout(3.seconds)
        .hostConnectionLimit(3)
        .tls(host)
        .build()
    }

    def accessTokeService: Service[HttpRequest, HttpResponse] = {
      ClientBuilder()
        .hosts(new InetSocketAddress(accessTokenHost, 443))
        .codec(Http())
        .tcpConnectTimeout(3.seconds)
        .hostConnectionLimit(3)
        .tls(accessTokenHost)
        .build()
    }

    val tokenMsg = ("grant_type=client_credentials&" +
      "scope=notify.windows.com&" +
      s"client_secret=$clientSecret&" +
      s"client_id=$sid").getBytes(Charset.defaultCharset())

    val accessTokenReq = RequestBuilder().url(accessTokenUrl).addHeader("Content-Type", "application/x-www-form-urlencoded")
      .buildPost(wrappedBuffer(tokenMsg))

    println(s"\n\nFormed accessTokenReq\n$accessTokenReq\n\n")

    val tokenP = Promise[String]()
    accessTokeService(accessTokenReq).onSuccess { resp =>

      val tokenJson = resp.getContent.array().map(_.toChar).mkString
      println(s"\n\ntokenJson is \n$tokenJson\n\n")
      val token = JSON.parseFull(tokenJson).get.asInstanceOf[Map[String, String]].get("access_token").fold(
        throw new RuntimeException("Token wasn't found in " + tokenJson)
      )(identity)
      tokenP.success(token)
    }.onFailure { t =>
      println("Failure " + t.getMessage)
    }
    val token = scala.concurrent.Await.result(tokenP.future, FiniteDuration(10, scala.concurrent.duration.SECONDS))
    println(s"\n\nGot token \n$token\n\n")

    val msg = message.getBytes(Charset.defaultCharset())

    val req = RequestBuilder().url(subscriptionUrl)
      .setHeader("Content-Type", "text/xml")
      .setHeader("X-MessageID", UUID.randomUUID().toString)
      .setHeader("X-NotificationClass", "2")
      .setHeader("X-WNS-Type", wnsType)
      .setHeader("Content-Length", msg.length.toString)
      .setHeader("Authorization", "Bearer " + token)
      .buildPost(wrappedBuffer(msg))

    Await.result(clientService(req).onSuccess { resp =>
      println(s"Success! $resp")
      //    println("\n\n Headers are " + resp.headers().entries().asScala.toList.mkString("\n"))
    }.onFailure { t =>
      println("Failure " + t.getMessage)
    }, 20.seconds)

    System.exit(0)
  }
}
