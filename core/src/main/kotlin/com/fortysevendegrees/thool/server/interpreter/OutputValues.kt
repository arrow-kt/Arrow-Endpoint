package com.fortysevendegrees.thool.server.interpreter

import com.fortysevendegrees.thool.EndpointIO
import com.fortysevendegrees.thool.EndpointOutput
import com.fortysevendegrees.thool.Mapping
import com.fortysevendegrees.thool.Params
import com.fortysevendegrees.thool.SplitParams
import com.fortysevendegrees.thool.model.CodecFormat
import com.fortysevendegrees.thool.model.HasHeaders
import com.fortysevendegrees.thool.model.Header
import com.fortysevendegrees.thool.model.HeaderNames
import com.fortysevendegrees.thool.model.MediaType
import com.fortysevendegrees.thool.model.StatusCode
import com.fortysevendegrees.thool.server.intrepreter.Body
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

public data class OutputValues<B>(
  val body: ((HasHeaders) -> B)?,
  val baseHeaders: List<Header>,
  val headerTransformations: List<(List<Header>) -> List<Header>>,
  val statusCode: StatusCode?
) {
  fun withBody(b: (HasHeaders) -> B): OutputValues<B> {
    check(body == null) { "Body is already defined" }
    return copy(body = b)
  }

  fun withHeaderTransformation(t: (List<Header>) -> List<Header>): OutputValues<B> =
    copy(headerTransformations = headerTransformations + t)

  fun withDefaultContentType(format: CodecFormat, charset: Charset?): OutputValues<B> =
    withHeaderTransformation { hs ->
      if (hs.any { it.hasName(HeaderNames.ContentType) }) hs
      else hs + Header(
        HeaderNames.ContentType,
        (charset?.let(format.mediaType::charset) ?: format.mediaType).toString()
      )
    }

  fun withHeader(n: String, v: String): OutputValues<B> =
    copy(baseHeaders = baseHeaders + Header(n, v))

  fun withStatusCode(sc: StatusCode): OutputValues<B> =
    copy(statusCode = sc)

  fun headers(): List<Header> =
    headerTransformations.fold(baseHeaders) { hs, t -> t(hs) }

  public companion object {
    fun <B> empty(): OutputValues<B> =
      OutputValues(null, emptyList(), emptyList(), null)

    fun <B> of(
      rawToResponseBody: ToResponseBody<B>,
      output: EndpointOutput<*>,
      params: Params,
      ov: OutputValues<B>
    ): OutputValues<B> =
      when (output) {
        is EndpointIO.Single<*> -> applySingle(rawToResponseBody, output, params, ov)
        is EndpointOutput.Single<*> -> applySingle(rawToResponseBody, output, params, ov)
        is EndpointOutput.Pair<*, *, *> -> applyPair(
          rawToResponseBody,
          output.first,
          output.second,
          output.split,
          params,
          ov
        )
        is EndpointIO.Pair<*, *, *> -> applyPair(
          rawToResponseBody,
          output.first,
          output.second,
          output.split,
          params,
          ov
        )
        is EndpointOutput.Void -> throw IllegalArgumentException("Cannot encode a void output!")
      }

    private fun <B> OutputValues<B>.withBody(
      body: Body,
      rawToResponseBody: ToResponseBody<B>,
      output: EndpointIO.Body<*, *>
    ): OutputValues<B> =
      withBody { headers -> rawToResponseBody.fromRawValue(body, headers, output.codec.format) }
        .withDefaultContentType(output.codec.format, charset(output.codec.format.mediaType, output))

    private fun <B> applySingle(
      rawToResponseBody: ToResponseBody<B>,
      output: EndpointOutput.Single<*>,
      value: Params,
      ov: OutputValues<B>
    ): OutputValues<B> =
      when (output) {
        is EndpointIO.Empty -> ov
        is EndpointOutput.FixedStatusCode -> ov.withStatusCode(output.statusCode)
        is EndpointIO.StreamBody<*> -> {
          val mapping = output.codec as Mapping<List<String>, Any?>
          ov.withBody { headers ->
            rawToResponseBody.fromStreamValue(
              mapping.encode(value.asAny) as Flow<Byte>,
              headers,
              output.codec.format,
              output.charset
            )
          }
            .withDefaultContentType(output.codec.format, output.charset)
            .withHeaderTransformation { hs ->
              if (hs.any { it.hasName(HeaderNames.ContentLength) }) hs else hs + Header(
                HeaderNames.TransferEncoding,
                "chunked"
              )
            }
        }
        is EndpointIO.Header -> {
          val mapping = output.codec as Mapping<List<String>, Any?>
          mapping.encode(value.asAny).fold(ov) { ovv, headerValue ->
            ovv.withHeader(output.name, headerValue)
          }
        }
        is EndpointIO.ByteArrayBody -> {
          val mapping = output.codec as Mapping<ByteArray, Any?>
          ov.withBody(Body.ByteArray(mapping.encode(value.asAny)), rawToResponseBody, output)
        }
        is EndpointIO.ByteBufferBody -> {
          val mapping = output.codec as Mapping<ByteBuffer, Any?>
          ov.withBody(Body.ByteBuffer(mapping.encode(value.asAny)), rawToResponseBody, output)
        }
        is EndpointIO.InputStreamBody -> {
          val mapping = output.codec as Mapping<InputStream, Any?>
          ov.withBody(Body.InputStream(mapping.encode(value.asAny)), rawToResponseBody, output)
        }
        is EndpointIO.StringBody -> {
          val mapping = output.codec as Mapping<String, Any?>
          ov.withBody(Body.String(output.charset, mapping.encode(value.asAny)), rawToResponseBody, output)
        }
        is EndpointIO.MappedPair<*, *, *, *> -> {
          val mapping = output.mapping as Mapping<Any?, Any?>
          of(rawToResponseBody, output.wrapped, Params.ParamsAsAny(mapping.encode(value.asAny)), ov)
        }
        is EndpointOutput.StatusCode -> {
          val mapping = output.codec as Mapping<StatusCode, Any?>
          ov.withStatusCode(mapping.encode(value.asAny))
        }
        is EndpointOutput.MappedPair<*, *, *, *> -> {
          val mapping = output.mapping as Mapping<Any?, Any?>
          of(rawToResponseBody, output.output, Params.ParamsAsAny(mapping.encode(value.asAny)), ov)
        }
      }

    private fun <B> applyPair(
      rawToResponseBody: ToResponseBody<B>,
      left: EndpointOutput<*>,
      right: EndpointOutput<*>,
      split: SplitParams,
      params: Params,
      ov: OutputValues<B>
    ): OutputValues<B> {
      val (leftParams, rightParams) = split(params)
      return of(rawToResponseBody, right, rightParams, of(rawToResponseBody, left, leftParams, ov))
    }

    private fun charset(mediaType: MediaType, body: EndpointIO.Body<*, *>): Charset? =
      when (body) {
        // TODO: add to MediaType - setting optional charset if text
        is EndpointIO.StringBody -> if (mediaType.mainType.equals(
            "text",
            ignoreCase = true
          )
        ) body.charset else null
        else -> null
      }
  }
}