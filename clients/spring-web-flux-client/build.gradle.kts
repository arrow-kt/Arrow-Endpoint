@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  id(libs.plugins.kotlin.jvm.get().pluginId)
  alias(libs.plugins.arrowGradleConfig.kotlin)
  alias(libs.plugins.arrowGradleConfig.publish)
  alias(libs.plugins.arrowGradleConfig.versioning)
}

dependencies {
  api(projects.arrowEndpointCore)
  api(libs.coroutines.reactive)
  api(libs.coroutines.reactor)
  api(libs.spring.boot.starter.webflux)
  api(libs.netty.transport.native.kqueue)

  testImplementation(projects.arrowEndpointTest)
  testImplementation(libs.coroutines.core)
  testImplementation(libs.kotest.assertionsCore)
  testImplementation(libs.kotest.property)
  testImplementation(libs.kotest.runnerJUnit5)
}
