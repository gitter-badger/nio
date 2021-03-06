package filters

import javax.inject.Inject

import configuration._
import akka.stream.Materializer
import auth.AuthInfo
import com.auth0.jwt._
import com.auth0.jwt.algorithms.Algorithm

import scala.concurrent.{ExecutionContext, Future}
import scala.util._
import play.api.libs.json.Json
import play.api.Logger
import play.api.libs.typedmap.TypedKey
import play.api.mvc._

object OtoroshiFilter {
  val Email: TypedKey[String] = TypedKey("email")
  val AuthInfo: TypedKey[AuthInfo] = TypedKey("authInfo")
}

class OtoroshiFilter @Inject()(env: Env)(implicit ec: ExecutionContext,
                                         val mat: Materializer)
    extends Filter {

  val config: OtoroshiFilterConfig = env.config.filter.otoroshi

  private val logger = Logger("filter")

  override def apply(nextFilter: RequestHeader => Future[Result])(
      requestHeader: RequestHeader): Future[Result] = {
    val startTime = System.currentTimeMillis
    val maybeReqId = requestHeader.headers.get(config.headerRequestId)
    val maybeState = requestHeader.headers.get(config.headerGatewayState)
    val maybeClaim: Option[String] =
      requestHeader.headers.get(config.headerClaim)

    val t = Try(env.env match {
      case devOrTest if devOrTest == "dev" || devOrTest == "test" =>
        nextFilter(
          requestHeader
            .addAttr(OtoroshiFilter.Email, "test@test.com")
            .addAttr(OtoroshiFilter.AuthInfo, AuthInfo("test@test.com", isAdmin = true)))
          .map {
            result =>
              val requestTime = System.currentTimeMillis - startTime
              logger.debug(
                s"Request => ${requestHeader.method} ${requestHeader.uri} took ${requestTime}ms and returned ${result.header.status}"
              )
              result.withHeaders(
                config.headerGatewayStateResp -> maybeState.getOrElse("--")
              )
          }

      case "prod" if maybeClaim.isEmpty && maybeState.isEmpty =>
        Future.successful(
          Results.Unauthorized(
            Json.obj("error" -> "Bad request !!!")
          )
        )
      case "prod" if maybeClaim.isEmpty =>
        Future.successful(
          Results
            .Unauthorized(
              Json.obj("error" -> "Bad claim !!!")
            )
            .withHeaders(
              config.headerGatewayStateResp -> maybeState.getOrElse("--")
            )
        )
      case "prod" =>
        val tryDecode = Try {

          val algorithm = Algorithm.HMAC512(config.sharedKey)
          val verifier = JWT
            .require(algorithm)
            .withIssuer(config.issuer)
            .acceptLeeway(5000)
            .build()
          val decoded = verifier.verify(maybeClaim.get)

          import scala.collection.JavaConverters._
          val claims = decoded.getClaims.asScala
          val requestWithAuthInfo = for {
            sub <- claims.get("sub").map(_.asString)
            name = claims.get("name").map(_.asString)
            email = claims.get("email").map(_.asString)
            isAdmin = claims
              .get("nio_admin")
              .map(_.asString)
              .flatMap(str => Try(str.toBoolean).toOption)
              .getOrElse(false)
          } yield {
            logger.info(s"Request from sub: $sub, name:$name, isAdmin:$isAdmin")
            email
              .map { email =>
                requestHeader.addAttr(OtoroshiFilter.Email, email)
              }
              .getOrElse(requestHeader)
              .addAttr(OtoroshiFilter.AuthInfo, AuthInfo(sub, isAdmin))
          }

          nextFilter(requestWithAuthInfo.getOrElse(requestHeader)).map {
            result =>
              val requestTime = System.currentTimeMillis - startTime
              maybeReqId.foreach {
                id =>
                  logger.debug(
                    s"Request from Gateway with id : $id => ${requestHeader.method} ${requestHeader.uri} with request headers ${requestHeader.headers.headers
                      .map(h => s"""   "${h._1}": "${h._2}"\n""")
                      .mkString(",")} took ${requestTime}ms and returned ${result.header.status} hasBody ${requestHeader.hasBody}"
                  )
              }
              result.withHeaders(
                config.headerGatewayStateResp -> maybeState.getOrElse("--")
              )
          }
        } recoverWith {
          case e =>
            Success(
              Future.successful(
                Results
                  .InternalServerError(
                    Json.obj("error" -> "what !!!", "m" -> e.getMessage)
                  )
                  .withHeaders(
                    config.headerGatewayStateResp -> maybeState.getOrElse("--")
                  )
              )
            )
        }
        tryDecode.get

      case _ =>
        Future.successful(
          Results
            .InternalServerError(
              Json.obj("error" -> "Bad env !!!")
            )
            .withHeaders(
              config.headerGatewayStateResp -> maybeState.getOrElse("--")
            )
        )
    }) recoverWith {
      case e =>
        Success(
          Future.successful(
            Results
              .InternalServerError(
                Json.obj("error" -> e.getMessage)
              )
              .withHeaders(
                config.headerGatewayStateResp -> maybeState.getOrElse("--")
              )
          )
        )
    }
    val result: Future[Result] = t.get
    result.onComplete {
      case Success(resp) =>
        logger.debug(
          s" ${requestHeader.method} ${requestHeader.uri} resp : $resp")
      case Failure(e) =>
        logger.error(
          s"Error for request ${requestHeader.method} ${requestHeader.uri}",
          e)
        logger.error(
          s"Error for request ${requestHeader.method} ${requestHeader.uri}",
          e.getCause)
    }
    result
  }
}
