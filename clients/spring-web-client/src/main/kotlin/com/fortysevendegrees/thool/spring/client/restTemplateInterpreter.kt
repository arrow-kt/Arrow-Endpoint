@file:Suppress("UNCHECKED_CAST")

package com.fortysevendegrees.thool.spring.client

import arrow.core.Either
import com.fortysevendegrees.thool.CombineParams
import com.fortysevendegrees.thool.DecodeResult
import com.fortysevendegrees.thool.Endpoint
import com.fortysevendegrees.thool.EndpointIO
import com.fortysevendegrees.thool.EndpointOutput
import com.fortysevendegrees.thool.Mapping
import com.fortysevendegrees.thool.Params
import com.fortysevendegrees.thool.client.requestInfo
import com.fortysevendegrees.thool.model.StatusCode
import org.springframework.http.HttpHeaders
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.nio.ByteBuffer

public fun <I, E, O> Endpoint<I, E, O>.toRequestAndParseRestTemplate(
  baseUrl: String
): RestTemplate.(I) -> Pair<ClientHttpRequest, DecodeResult<Either<E, O>>> =
  { input: I ->
    val request = toRequest(requestFactory, baseUrl, input)
    Pair(request, parseResponse(request))
  }

public operator fun <I, E, O> RestTemplate.invoke(
  endpoint: Endpoint<I, E, O>,
  baseUrl: String,
  input: I
): DecodeResult<Either<E, O>> {
  val request: ClientHttpRequest = endpoint.toRequest(requestFactory, baseUrl, input)
  return endpoint.parseResponse(request)
}

private fun <I, E, O> Endpoint<I, E, O>.toRequest(
  requestFactory: ClientHttpRequestFactory,
  baseUrl: String,
  i: I
): ClientHttpRequest {
  val info = input.requestInfo(i, baseUrl)
  val httpMethod = info.method.method()
  requireNotNull(httpMethod) { "Method not defined!" }
  return requestFactory.createRequest(URI.create(info.fullUrl), httpMethod).apply {
    info.cookies.forEach { (name, value) ->
      // TOOD what about name??
      headers.add(HttpHeaders.COOKIE, value)
    }
    info.headers.forEach { (name, value) ->
      headers.add(name, value)
    }
    info.body?.toByteArray()?.let(body::write)
  }
}

// Functionality on how to go from Spring Response to our domain
private fun <I, E, O> Endpoint<I, E, O>.parseResponse(
  request: ClientHttpRequest,
): DecodeResult<Either<E, O>> {
  val response = request.execute()
  val code = StatusCode(response.rawStatusCode)
  val output = if (code.isSuccess()) output else errorOutput

  val params =
    output.getOutputParams(response, response.headers, code, response.statusCode.reasonPhrase)

  val result = params.map { it.asAny }
    .map { p -> if (code.isSuccess()) Either.Right(p as O) else Either.Left(p as E) }

  return when (result) {
    is DecodeResult.Failure.Error ->
      DecodeResult.Failure.Error(
        result.original,
        IllegalArgumentException(
          "Cannot decode from ${result.original} of request ${request.method} ${request.uri}",
          result.error
        )
      )
    else -> result
  }
}

private fun EndpointOutput<*>.getOutputParams(
  response: ClientHttpResponse,
  headers: Map<String, List<String>>,
  code: StatusCode,
  statusText: String
): DecodeResult<Params> =
  when (val output = this) {
    is EndpointOutput.Single<*> -> when (val single = (output as EndpointOutput.Single<Any?>)) {
      is EndpointIO.ByteArrayBody -> single.codec.decode(response.body.readBytes())
      is EndpointIO.ByteBufferBody -> single.codec.decode(ByteBuffer.wrap(response.body.readBytes()))
      is EndpointIO.InputStreamBody -> single.codec.decode(response.body.readBytes().inputStream())
      is EndpointIO.StringBody -> single.codec.decode(String(response.body.readBytes()))
      is EndpointIO.StreamBody -> TODO() // (output.codec::decode as (Any?) -> DecodeResult<Params>).invoke(body())
      is EndpointIO.Empty -> single.codec.decode(Unit)
      is EndpointOutput.FixedStatusCode -> single.codec.decode(Unit)
      is EndpointOutput.StatusCode -> single.codec.decode(code)
      is EndpointIO.Header -> single.codec.decode(headers[single.name].orEmpty())

      is EndpointIO.MappedPair<*, *, *, *> ->
        single.wrapped.getOutputParams(response, headers, code, statusText).flatMap { p ->
          (single.mapping as Mapping<Any?, Any?>).decode(p.asAny)
        }
      is EndpointOutput.MappedPair<*, *, *, *> ->
        single.output.getOutputParams(response, headers, code, statusText).flatMap { p ->
          (single.mapping as Mapping<Any?, Any?>).decode(p.asAny)
        }
    }.map { Params.ParamsAsAny(it) }

    is EndpointIO.Pair<*, *, *> -> handleOutputPair(
      output.first,
      output.second,
      output.combine,
      response,
      headers,
      code,
      statusText
    )
    is EndpointOutput.Pair<*, *, *> -> handleOutputPair(
      output.first,
      output.second,
      output.combine,
      response,
      headers,
      code,
      statusText
    )
    is EndpointOutput.Void -> DecodeResult.Failure.Error(
      "",
      IllegalArgumentException("Cannot convert a void output to a value!")
    )
  }

private fun handleOutputPair(
  left: EndpointOutput<*>,
  right: EndpointOutput<*>,
  combine: CombineParams,
  response: ClientHttpResponse,
  headers: Map<String, List<String>>,
  code: StatusCode,
  statusText: String
): DecodeResult<Params> {
  val l = left.getOutputParams(response, headers, code, statusText)
  val r = right.getOutputParams(response, headers, code, statusText)
  return l.flatMap { leftParams -> r.map { rightParams -> combine(leftParams, rightParams) } }
}
