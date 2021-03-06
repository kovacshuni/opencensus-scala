package io.opencensus.scala.http4s

import cats._
import cats.data.{Kleisli, OptionT}
import cats.effect.Effect
import cats.implicits._
import io.opencensus.scala.Tracing
import io.opencensus.scala.http.propagation.Propagation
import io.opencensus.scala.http.{
  ServiceAttributes,
  ServiceData,
  HttpAttributes => BaseHttpAttributes
}
import io.opencensus.scala.http4s.HttpAttributes._
import io.opencensus.scala.http4s.TracingService.{SpanRequest, TracingService}
import io.opencensus.scala.http4s.TracingUtils.recordResponse
import io.opencensus.scala.http4s.propagation.Http4sFormatPropagation
import io.opencensus.trace.{Span, Status}
import org.http4s.{Header, HttpRoutes, Request, Response}

abstract class TracingMiddleware[F[_]: Effect] {
  protected def tracing: Tracing
  protected def propagation: Propagation[Header, Request[F]]

  /**
    * Transforms a `TracingService` to a `HttpService` to be ready to run by a server.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * Adds service data as attribute to the span when given.
    * @return HttpService[F]
    */
  def fromTracingService(tracingService: TracingService[F]): HttpRoutes[F] =
    fromTracingService(tracingService, ServiceData())
  def fromTracingService(
      tracingService: TracingService[F],
      serviceData: ServiceData
  ): HttpRoutes[F] =
    Kleisli { req =>
      for {
        span <- OptionT.liftF(buildSpan(req, serviceData))
        fResponse = tracingService(SpanRequest(span, req))
        response <- recordFailures(fResponse, span)
      } yield recordResponse(span, tracing)(response)
    }

  /**
    * Adds tracing to a `HttpService[F]`, does not pass the `span` to the service itself.
    * Use `TracingMiddleware.apply` for that.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * Adds service data as attribute to the span when given.
    * @return HttpService[F]
    */
  def withoutSpan(service: HttpRoutes[F]): HttpRoutes[F] =
    withoutSpan(service, ServiceData())
  def withoutSpan(
      service: HttpRoutes[F],
      serviceData: ServiceData
  ): HttpRoutes[F] =
    fromTracingService(
      service.local[SpanRequest[F]](spanReq => spanReq.req),
      serviceData
    )

  private def recordFailures(
      result: OptionT[F, Response[F]],
      span: Span
  ): OptionT[F, Response[F]] =
    OptionT(
      result.value
        .onError {
          case _ => recordException(span)
        }
        .flatMap {
          case None =>
            reordNotFound(span).map(_ => None: Option[Response[F]])
          case some => Effect[F].pure(some)
        }
    )

  private def buildSpan(req: Request[F], serviceData: ServiceData) =
    Effect[F].delay {
      val name = req.uri.path.toString
      val span = propagation
        .extractContext(req)
        .fold(
          _ => tracing.startSpan(name),
          tracing.startSpanWithRemoteParent(name, _)
        )
      ServiceAttributes.setAttributesForService(span, serviceData)
      BaseHttpAttributes.setAttributesForRequest(span, req)
      span
    }

  private def recordException(span: Span) =
    Effect[F].delay(tracing.endSpan(span, Status.INTERNAL))

  private def reordNotFound(span: Span) =
    Effect[F].delay(tracing.endSpan(span, Status.INVALID_ARGUMENT))
}

object TracingMiddleware {

  /**
    * Transforms a `TracingService` to a `HttpService` to be ready to run by a server.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * Adds service data as attribute to the span when given.
    * @return HttpService[F]
    */
  def apply[F[_]: Effect](tracingService: TracingService[F]): HttpRoutes[F] =
    createMiddleware[F].fromTracingService(tracingService)
  def apply[F[_]: Effect](
      tracingService: TracingService[F],
      serviceData: ServiceData
  ): HttpRoutes[F] =
    createMiddleware[F].fromTracingService(tracingService, serviceData)

  /**
    * Adds tracing to a `HttpService[F]`, does not pass the `span` to the service itself.
    * Use `TracingMiddleware.apply` for that.
    * Starts a new span and sets a parent context if the request contains valid headers in the b3 format.
    * The span is ended when the request completes or fails with a status code which is suitable
    * to the http response code.
    * Adds service data as attribute to the span when given.
    * @return HttpService[F]
    */
  def withoutSpan[F[_]: Effect](service: HttpRoutes[F]): HttpRoutes[F] =
    createMiddleware[F].withoutSpan(service)
  def withoutSpan[F[_]: Effect](
      service: HttpRoutes[F],
      serviceData: ServiceData
  ): HttpRoutes[F] =
    createMiddleware[F].withoutSpan(service, serviceData)

  private def createMiddleware[F[_]: Effect] = {
    new TracingMiddleware[F] {
      override protected val tracing: Tracing = Tracing
      override protected val propagation: Propagation[Header, Request[F]] =
        new Http4sFormatPropagation[F] {}
    }
  }
}

object TracingService {
  case class SpanRequest[F[_]](span: Span, req: Request[F])

  type TracingService[F[_]] =
    Kleisli[OptionT[F, ?], SpanRequest[F], Response[F]]

  /**
    * Creates a `TracingService` from a partial function over `SpanRequest[F] => F[Response[F]]`
    * works similar to `org.http4s.HttpService`, but needs to extract the `span` from the
    * `SpanRequest` e.g.:
    * `TracingService[IO] {
    *  case GET -> Root / "path" withSpan span => Ok()
    * }`
    * @return TracingService[F]
    */
  def apply[F[_]](
      pf: PartialFunction[SpanRequest[F], F[Response[F]]]
  )(implicit F: Applicative[F]): TracingService[F] =
    Kleisli(req =>
      pf.andThen(OptionT.liftF(_))
        .applyOrElse(req, (_: SpanRequest[F]) => OptionT.none)
    )

  /**
    * Used to extract the `span` from the `SpanRequest` e.g.:
    * `case GET -> Root / "path" withSpan span => Ok()`
    */
  object withSpan {
    def unapply[F[_], A](sr: SpanRequest[F]): Option[(Request[F], Span)] =
      Some(sr.req -> sr.span)
  }
}
