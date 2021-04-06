package com.fortysevendegrees.tapir.ktor

import io.ktor.application.*
import io.ktor.response.*
import com.fortysevendegrees.tapir.server.ServerEndpoint
import com.fortysevendegrees.tapir.server.intrepreter.ServerInterpreter

fun <I, E, O> Application.install(ses: ServerEndpoint<I, E, O>): Unit =
  install(listOf(ses))

fun <I, E, O> Application.install(ses: List<ServerEndpoint<I, E, O>>): Unit =
  intercept(ApplicationCallPipeline.Call) {
    val serverRequest = KtorServerRequest(call)
    val interpreter = ServerInterpreter(
      serverRequest,
      KtorRequestBody(call),
      KtorToResponseBody(),
      emptyList()
    )

    interpreter.invoke(ses)?.let {
      call.respond(it.body!!)
    }

  }
