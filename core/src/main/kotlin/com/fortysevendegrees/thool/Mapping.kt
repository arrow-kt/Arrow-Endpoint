package com.fortysevendegrees.thool

import arrow.core.Option
import arrow.core.andThen
import arrow.core.getOrElse
import arrow.core.nonFatalOrThrow

/**
 * A bi-directional mapping between values of type `L` and values of type `H`.
 *
 * Low-level values of type `L` can be **decoded** to a higher-level value of type `H`. The decoding can fail;
 * this is represented by a result of type [[DecodeResult.Failure]]. Failures might occur due to format errors, wrong
 * arity, exceptions, or validation errors. Validators can be added through the `validate` com.fortysevendegrees.thool.method.
 *
 * High-level values of type `H` can be **encoded** as a low-level value of type `L`.
 *
 * Mappings can be chained using one of the `map` functions.
 *
 * @param L The type of the low-level value.
 * @param H The type of the high-level value.
 */
interface Mapping<L, H> {

  fun rawDecode(l: L): DecodeResult<H>

  fun encode(h: H): L

  /**
   * - calls `rawDecode`
   * - catches any exceptions that might occur, converting them to decode failures
   * - validates the result
   */
  fun decode(l: L): DecodeResult<H> = validate(tryRawDecode(l))

  private fun tryRawDecode(l: L): DecodeResult<H> =
    try {
      rawDecode(l)
    } catch (e: Throwable) {
      val error = e.nonFatalOrThrow()
      DecodeResult.Failure.Error(l.toString(), error)
    }

  private fun validate(r: DecodeResult<H>): DecodeResult<H> =
    when (r) {
      is DecodeResult.Value -> {
        val validationErrors = validator().validate(r.value)
        if (validationErrors.isEmpty()) DecodeResult.Value(r.value)
        else DecodeResult.Failure.InvalidValue(validationErrors)
      }
      else -> r
    }

  fun validator(): Validator<H>

  fun <HH> map(codec: Mapping<H, HH>): Mapping<L, HH> =
    object : Mapping<L, HH> {
      override fun rawDecode(l: L): DecodeResult<HH> =
        this@Mapping.rawDecode(l).flatMap(codec::rawDecode)

      override fun encode(h: HH): L =
        this@Mapping.encode(codec.encode(h))

      override fun validator(): Validator<HH> =
        this@Mapping.validator()
          .contramap(codec::encode)
          .and(codec.validator())
    }

  fun validate(v: Validator<H>): Mapping<L, H> =
    object : Mapping<L, H> {
      override fun rawDecode(l: L): DecodeResult<H> =
        this@Mapping.decode(l)

      override fun encode(h: H): L =
        this@Mapping.encode(h)

      override fun validator(): Validator<H> =
        addEncodeToEnumValidator(v).and(this@Mapping.validator())
    }

  fun addEncodeToEnumValidator(v: Validator<H>): Validator<H> =
    if (v is Validator.Single.Primitive.Enum) v.encode(this@Mapping::encode)
    else v

  companion object {
    fun <L> id(): Mapping<L, L> =
      object : Mapping<L, L> {
        override fun rawDecode(l: L): DecodeResult<L> = DecodeResult.Value(l)
        override fun encode(h: L): L = h
        override fun validator(): Validator<L> = Validator.pass()
      }

    fun <L, H> fromDecode(f: (L) -> DecodeResult<H>, g: (H) -> L): Mapping<L, H> =
      object : Mapping<L, H> {
        override fun rawDecode(l: L): DecodeResult<H> = f(l)
        override fun encode(h: H): L = g(h)
        override fun validator(): Validator<H> = Validator.pass()
      }

    fun <L, H> from(f: (L) -> H, g: (H) -> L): Mapping<L, H> =
      fromDecode(f.andThen { DecodeResult.Value(it) }, g)

    /**
     * A mapping which, during encoding, adds the given `prefix`.
     * When decoding, the prefix is removed (case insensitive,if present), otherwise an error is reported.
     */
    fun stringPrefixCaseInsensitive(prefix: String): Mapping<String, String> {
      val prefixLength = prefix.length
      val prefixLower = prefix.toLowerCase()

      return fromDecode({ value ->
        if (value.toLowerCase().startsWith(prefixLower)) DecodeResult.Value(value.substring(prefixLength))
        else DecodeResult.Failure.Error(value, IllegalArgumentException("The given value doesn't start with $prefix"))
      }) { v -> "$prefix$v" }
    }
  }
}

sealed class DecodeResult<out A> {
  abstract fun <B> map(transform: (A) -> B): DecodeResult<B>
  abstract fun <B> flatMap(transform: (A) -> DecodeResult<B>): DecodeResult<B>

  data class Value<A>(val value: A) : DecodeResult<A>() {
    override fun <B> map(transform: (A) -> B): DecodeResult<B> = Value(transform(value))
    override fun <B> flatMap(transform: (A) -> DecodeResult<B>): DecodeResult<B> = transform(value)
  }

  sealed class Failure : DecodeResult<Nothing>() {
    override fun <B> map(transform: (Nothing) -> B): DecodeResult<B> = this
    override fun <B> flatMap(transform: (Nothing) -> DecodeResult<B>): DecodeResult<B> = this

    object Missing : Failure()
    data class Multiple<A>(val values: List<A>) : Failure()
    data class Mismatch(val expected: String, val actual: String) : Failure()
    data class InvalidValue(val errors: List<ValidationError<*>>) : Failure()
    data class Error(val original: String, val error: Throwable) : Failure() {
      companion object {
        data class JsonDecodeException(val errors: List<JsonError>, val underlying: Throwable) : Exception(
          if (errors.isEmpty()) underlying.message else errors.joinToString(transform = JsonError::message),
          underlying
        )

        data class JsonError(val msg: String, val path: List<FieldName>) {
          fun message(): String {
            val at = if (path.isNotEmpty()) " at '${path.joinToString(separator = ".") { it.encodedName }}'" else ""
            return msg + at
          }
        }
      }
    }
  }

  fun <A> fromOption(o: Option<A>): DecodeResult<A> =
    o.map { Value(it) }.getOrElse { Failure.Missing }
}

fun <A> List<DecodeResult<A>>.sequence(): DecodeResult<List<A>> =
  foldRight(DecodeResult.Value(emptyList())) { res, acc ->
    when (res) {
      is DecodeResult.Value -> when (acc) {
        is DecodeResult.Value -> DecodeResult.Value(listOf(res.value) + acc.value)
        is DecodeResult.Failure -> acc
      }
      is DecodeResult.Failure -> res
    }
  }
