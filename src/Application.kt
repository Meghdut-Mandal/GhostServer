package com.meghdut

import com.meghdut.data.ChatClient
import io.ktor.application.Application
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.defaultResource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.locations.Locations
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.sessions.*
import io.ktor.util.generateNonce
import io.ktor.websocket.webSocket
import kotlinx.coroutines.channels.consumeEach
import java.io.File
import java.time.Duration


fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
private val server = CommandServer()

data class UserSession(val id: String)
data class MeetingResponce(
    val isAvailable: Boolean,
    val client: ChatClient?
)

object AutomationServer {
    val clientList = arrayListOf<ChatClient>()

    fun getInactiveClient(): ChatClient? {
        return clientList.find { !it.status }
    }

    fun activate(id: String, status: Boolean) {
        clientList.find { it.id == id }?.let {
            it.status = status
        }
    }

    fun add(chatClient: ChatClient) {
        chatClient.status = false
        clientList.add(chatClient)
    }

}


@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {

    val configFile = File("config")
    if (configFile.exists()) {
        server.mainServerUrl = configFile.readText().trim()
        println("com.meghdut>>module  Mainserver  url ${server.mainServerUrl}")
    }

    install(Locations)
    install(CallLogging)
    install(DefaultHeaders)

    install(CORS) {
        header("Access-Control-Allow-Origin: *");
        header("Access-Control-Allow-Credentials: true");
        header("Access-Control-Allow-Methods: GET, HEAD, OPTIONS, POST, PUT, DELETE");
        header("Access-Control-Allow-Headers: Authorization, Origin, X-Requested-With, Content-Type, Accept");
        header("Access-Control-Expose-Headers: Access-Control-Allow-Headers, Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");
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

        route("admin") {

            get("/instances/list") {
                call.respond(AutomationServer.clientList)
            }

            get("/new_meeting") {
                val newClient = AutomationServer.getInactiveClient()
                newClient?.let {
                    it.status = true
                }
                call.respond(MeetingResponce((newClient != null), newClient))
            }

            get("/intances/done") {
                val id = call.request.queryParameters["id"]
                AutomationServer.clientList.filter { it.id == id }.forEach {
                    it.status = false
                }
                call.respond(HttpStatusCode.OK, "Done!")
            }

            post("/instances/add") {
                val receive = call.receive<ChatClient>()
                AutomationServer.add(receive)
                call.respond(HttpStatusCode.OK, "Done")
            }
        }


        route("client") {
            get("/start") {
                val id = call.request.queryParameters["id"]
                server.clientID = id ?: return@get call.respond(HttpStatusCode.BadRequest, "ID not found")
                call.respondRedirect("/static/index.html")
            }
        }

        val port = System.getenv("PORT")
        println("com.meghdut>>module  running at http://localhost:$port/ ")

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
// java -jar build/libs/ghost-0.0.1-all.jar