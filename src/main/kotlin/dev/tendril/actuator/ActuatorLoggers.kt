package dev.tendril.actuator

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.tendril.endpoints.EndpointRequests
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The `/loggers` endpoint of a running application: read and change the level of one logger. Blocking calls. */
object ActuatorLoggers {
    val LEVELS = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

    private val ADDRESS = Key.create<String>("tendril.actuator.address")

    /** The Actuator address last loaded in the Spring Actuator window. */
    fun remember(project: Project, base: String) = project.putUserData(ADDRESS, base)

    /** The address the Spring Actuator window uses, else the one the config files give. Needs a read action for the latter. */
    fun address(project: Project): String =
        project.getUserData(ADDRESS) ?: EndpointRequests.actuatorUrl(project)

    /** The configured level of [logger] (else its effective level), or null when the application does not answer. */
    fun level(base: String, logger: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI.create("$base/loggers/$logger"))
            .timeout(Duration.ofSeconds(2))
            .header("Accept", "application/json")
            .GET().build()
        val response = client(500).send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val json = JsonParser.parseString(response.body()).asJsonObject
        json.get("configuredLevel")?.takeIf { it.isJsonPrimitive }?.asString
            ?: json.get("effectiveLevel")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    /** POST /loggers/{name} {"configuredLevel": level}; a null level resets the logger to its parent's level. */
    fun setLevel(base: String, logger: String, level: String?): Result<Unit> = runCatching {
        val body = if (level == null) """{"configuredLevel":null}""" else """{"configuredLevel":"$level"}"""
        val request = HttpRequest.newBuilder(URI.create("$base/loggers/$logger"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val status = client(3000).send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        if (status !in 200..299) throw IllegalStateException("HTTP $status")
    }

    private fun client(connectMillis: Long) =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMillis)).build()
}
