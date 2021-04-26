package com.fortysevendegrees.thool.server.interpreter

import com.fortysevendegrees.thool.EndpointIO
import kotlinx.coroutines.flow.Flow

public interface RequestBody {
  suspend fun <R> toRaw(bodyType: EndpointIO.Body<R, *>): R
  fun toFlow(): Flow<Byte>
}