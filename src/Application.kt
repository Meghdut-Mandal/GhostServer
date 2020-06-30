package com.meghdut
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.html.respondHtml
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.content.*
import io.ktor.locations.*
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.util.generateNonce
import io.ktor.websocket.webSocket
import kotlinx.coroutines.channels.*
import kotlinx.css.a
import java.time.*
import java.util.*


fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
private val server = CommandServer()

data class UserSession(val id: String)

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Locations)
    install(CallLogging)
    install(DefaultHeaders)

    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Put)
        method(HttpMethod.Delete)
        method(HttpMethod.Patch)
        header(HttpHeaders.Authorization)
        header("MyCustomHeader")
        allowCredentials = true
        anyHost()
    }

    // This installs the websockets feature to be able to establish a bidirectional configuration
    // between the server and the client
    install(io.ktor.websocket.WebSockets) {
        pingPeriod = Duration.ofMinutes(1)
    }

    install(ContentNegotiation) {
        gson {}
    }

    // This enables the use of sessions to keep information between requests/refreshes of the browser.
    install(Sessions) {
        cookie<UserSession>("USERSESSION")
    }
    // This adds an interceptor that will create a specific session in each request if no session is available already.
    intercept(ApplicationCallPipeline.Features) {
        if (call.sessions.get<UserSession>() == null) {
            call.sessions.set(UserSession(generateNonce()))
        }
    }


    routing {


        get("/") {
            call.respondRedirect("/static/index.html")
        }
        // Static feature. Try to access `/static/ktor_logo.svg`
        static("/static") {
            resources("static")
            // This marks index.html from the 'web' folder in resources as the default file to serve.
            defaultResource("index.html", "static")
            // This serves files from the 'web' folder in the application resources.
        }

        install(StatusPages) {
            exception<AuthenticationException> { cause ->
                call.respond(HttpStatusCode.Unauthorized)
            }
            exception<AuthorizationException> { cause ->
                call.respond(HttpStatusCode.Forbidden)
            }

        }




        webSocket("/ws") { // this: WebSocketSession ->

            // First of all we get the session.
            val session = call.sessions.get<UserSession>()

            // We check that we actually have a session. We should always have one,
            // since we have defined an interceptor before to set one.
            if (session == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session"))
                return@webSocket
            }

            // We notify that a member joined by calling the server handler [memberJoin]
            // This allows to associate the session id to a specific WebSocket connection.
            server.memberJoin(session.id, this)

            try {
                // We starts receiving messages (frames).
                // Since this is a coroutine. This coroutine is suspended until receiving frames.
                // Once the connection is closed, this consumeEach will finish and the code will continue.
                incoming.consumeEach { frame ->
                    // Frames can be [Text], [Binary], [Ping], [Pong], [Close].
                    // We are only interested in textual messages, so we filter it.
                    if (frame is Frame.Text) {
                        // Now it is time to process the text sent from the user.
                        // At this point we have context about this connection, the session, the text and the server.
                        // So we have everything we need.
                        receivedMessage(session.id, frame.readText())
                    }
                }
            } finally {
                // Either if there was an error, of it the connection was closed gracefully.
                // We notify the server that the member left.
                server.memberLeft(session.id, this)
            }
        }


    }
}


/**
 * We received a message. Let's process it.
 */
private suspend fun receivedMessage(id: String, command: String) {
    // We are going to handle commands (text starting with '/') and normal messages
    when {
        // The command `who` responds the user about all the member names connected to the user.
        command.startsWith("/who") -> server.who(id)
        // The command `user` allows the user to set its name.
        command.startsWith("/user") -> {
            // We strip the command part to get the rest of the parameters.
            // In this case the only parameter is the user's newName.
            val newName = command.removePrefix("/user").trim()
            // We verify that it is a valid name (in terms of length) to prevent abusing
            when {
                newName.isEmpty() -> server.sendTo(id, "server::help", "/user [newName]")
                newName.length > 50 -> server.sendTo(
                    id,
                    "server::help",
                    "new name is too long: 50 characters limit"
                )
                else -> server.memberRenamed(id, newName)
            }
        }
        // The command 'help' allows users to get a list of available commands.
        command.startsWith("/help") -> server.help(id)
        // If no commands matched at this point, we notify about it.
        command.startsWith("/") -> server.sendTo(
            id,
            "server::help",
            "Unknown command ${command.takeWhile { !it.isWhitespace() }}"
        )
        // Handle a normal message.
        else -> server.message(id, command)
    }
}

class AuthenticationException : RuntimeException()
class AuthorizationException : RuntimeException()
