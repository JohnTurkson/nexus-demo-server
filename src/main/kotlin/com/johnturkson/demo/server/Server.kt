package com.johnturkson.demo.server

import com.johnturkson.demo.database.LinkinbioDatabase
import com.johnturkson.demo.model.*
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.readText
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.delete
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.serialization.json
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

fun main() {
    Server.listen()
}

object Server {
    private const val databaseUrl = "jdbc:postgresql://localhost:5432/linkinbio"
    private val databaseProperties = Properties().apply {
        setProperty("user", System.getenv("user"))
        setProperty("password", System.getenv("password"))
    }
    private val database = LinkinbioDatabase(databaseUrl, databaseProperties)
    
    private val server = embeddedServer(factory = CIO, port = 8080) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(httpRequestSerializer)
        }
        
        routing {
            val allocatedClientIds = mutableSetOf<String>()
            val sessions = mutableMapOf<String, SessionInformation>()
            
            get("/LinkinbioPost/{id}") {
                val token = call.request.headers["Token"]
                
                require(token != null) {
                    call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    "Missing token"
                }
                
                val user = getUserByToken(token)
                
                require(user != null) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid token")
                    "Invalid token"
                }
                
                val id = call.parameters["id"]!!
                val linkinbioPost = database.getLinkinbioPost(id)
                
                require(linkinbioPost != null) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                require(linkinbioPost.user == user.id) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                call.respond(linkinbioPost)
            }
            
            get("/LinkinbioPosts/{user}") {
                try {
                    val token = call.request.headers["Token"]
                    
                    require(token != null) {
                        call.respond(HttpStatusCode.Unauthorized, "Missing token")
                        "Missing token"
                    }
                    
                    val user = getUserByToken(token)
                    
                    require(user != null) {
                        call.respond(HttpStatusCode.Forbidden, "Invalid token")
                        "Invalid token"
                    }
                    
                    val id = call.parameters["user"]!!
                    
                    require(user.id == id) {
                        call.respond(HttpStatusCode.Forbidden, "Invalid token")
                        "Invalid token"
                    }
                    
                    val linkinbioPosts = database.getLinkinbioPosts(user.id)
                    
                    call.respond(linkinbioPosts)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            
            post("/CreateLinkinbioPost") {
                val token = call.request.headers["Token"]
                
                require(token != null) {
                    call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    "Missing token"
                }
                
                val user = getUserByToken(token)
                
                require(user != null) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid token")
                    "Invalid token"
                }
                
                val request = call.receive<CreateLinkinbioPostRequest>()
                val response = database.createLinkinbioPost(
                    LinkinbioPost(
                        database.generateNewId(),
                        user.id,
                        request.url,
                        request.image
                    )
                )
                
                require(response != null) {
                    call.respond(HttpStatusCode.InternalServerError, "Something went wrong")
                    "Something went wrong"
                }
                
                call.respond(response)
                
                val channel = "/user/${user.id}"
                val event = WebsocketUpdateResponse(
                    "update_id",
                    channel,
                    CreateLinkinbioPostResponseData(LinkinbioPostData(response))
                )
                
                try {
                    println("sessions: $sessions")
                    sessions.values
                        .filter { session -> channel in session.subscriptions }
                        .forEach { session ->
                            println("notify created to ${session.clientId}")
                            
                            session.connection.outgoing.send(
                                Frame.Text(
                                    websocketResponseSerializer.stringify(
                                        WebsocketUpdateResponse.serializer(),
                                        event
                                    )
                                )
                            )
                        }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            
            post("/UpdateLinkinbioPost") {
                val token = call.request.headers["Token"]
                
                require(token != null) {
                    call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    "Missing token"
                }
                
                val user = getUserByToken(token)
                
                require(user != null) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid token")
                    "Invalid token"
                }
                
                val request = call.receive<UpdateLinkinbioPostRequest>()
                val linkinbioPost = database.getLinkinbioPost(request.id)
                
                require(linkinbioPost != null) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                require(linkinbioPost.user == user.id) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                val response = database.updateLinkinbioPost(
                    LinkinbioPost(
                        request.id,
                        user.id,
                        request.url, request.image
                    )
                )
                
                require(response != null) {
                    call.respond(HttpStatusCode.InternalServerError, "Something went wrong")
                    "Something went wrong"
                }
                
                call.respond(response)
                
                val channel = "/user/${user.id}"
                val event = WebsocketUpdateResponse(
                    "update_id",
                    channel,
                    UpdateLinkinbioPostResponseData(LinkinbioPostData(response))
                )
                
                try {
                    println("sessions: $sessions")
                    sessions.values
                        .filter { session -> channel in session.subscriptions }
                        .forEach { session ->
                            println("notify updated to ${session.clientId}")
                            
                            session.connection.outgoing.send(
                                Frame.Text(
                                    websocketResponseSerializer.stringify(
                                        WebsocketUpdateResponse.serializer(),
                                        event
                                    )
                                )
                            )
                        }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
                
            }
            
            delete("/delete/{id}") {
                val token = call.request.headers["Token"]
                
                require(token != null) {
                    call.respond(HttpStatusCode.Unauthorized, "Missing token")
                    "Missing token"
                }
                
                val user = getUserByToken(token)
                
                require(user != null) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid token")
                    "Invalid token"
                }
                
                val id = call.parameters["id"]!!
                
                val linkinbioPost = database.getLinkinbioPost(id)
                
                require(linkinbioPost != null) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                require(linkinbioPost.user == user.id) {
                    call.respond(HttpStatusCode.NotFound, "No matching Linkinbio post found")
                    "No matching Linkinbio post found"
                }
                
                val response = database.deleteLinkinbioPost(id)
                
                require(response != null) {
                    call.respond(HttpStatusCode.InternalServerError, "Something went wrong")
                    "Something went wrong"
                }
                
                call.respond(response)
                
                val channel = "/user/${user.id}"
                val event = WebsocketUpdateResponse(
                    "update_id",
                    channel,
                    DeleteLinkinbioPostResponseData(LinkinbioPostData(response))
                )
                
                try {
                    println("sessions: $sessions")
                    sessions.values
                        .filter { session -> channel in session.subscriptions }
                        .forEach { session ->
                            println("notify deleted to ${session.clientId}")
                            
                            session.connection.outgoing.send(
                                Frame.Text(
                                    websocketResponseSerializer.stringify(
                                        WebsocketUpdateResponse.serializer(),
                                        event
                                    )
                                )
                            )
                        }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
            
            webSocket("/updates") {
                val clientId = generateNewClientId()
                
                suspend fun handshake(request: WebsocketHandshakeRequest) {
                    require("websocket" in request.supportedConnectionTypes) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Unsupported connection type".also { println(it) }
                    }
                    
                    val session = SessionInformation(
                        connection = this,
                        clientId = clientId,
                        registered = false,
                        subscriptions = mutableSetOf()
                    )
                    
                    val response = WebsocketHandshakeResponse(
                        id = request.id,
                        clientId = session.clientId,
                        channel = "/meta/handshake",
                        supportedConnectionTypes = listOf("websocket"),
                        version = "0.0.1",
                        successful = true,
                        error = null,
                        advice = WebsocketConnectionParameters(
                            reconnect = "retry",
                            interval = 0,
                            timeout = 45000
                        )
                    )
                    
                    allocatedClientIds += session.clientId
                    sessions += response.clientId to session
                    
                    val json = websocketResponseSerializer.stringify(
                        WebsocketHandshakeResponse.serializer(),
                        response
                    )
                    
                    session.connection.outgoing.send(Frame.Text(json))
                    println("$clientId connected")
                }
                
                suspend fun connect(request: WebsocketConnectionRequest) {
                    val session = sessions[request.clientId]
                    
                    require(session != null) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Session does not exist".also { println(it) }
                    }
                    
                    require(request.connectionType == "websocket") {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Unsupported connection type".also { println(it) }
                    }
                    
                    require(request.clientId == session.clientId) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid client id".also { println(it) }
                    }
                    
                    require(this == session.connection) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid session connection".also { println(it) }
                    }
                    
                    val response = WebsocketConnectionResponse(
                        id = request.id,
                        clientId = session.clientId,
                        channel = "/meta/connect",
                        successful = true,
                        advice = WebsocketConnectionParameters(
                            reconnect = "retry",
                            interval = 0,
                            timeout = 45000
                        )
                    )
                    
                    val json = websocketResponseSerializer.stringify(
                        WebsocketConnectionResponse.serializer(),
                        response
                    )
                    
                    session.registered = true
                    
                    session.connection.outgoing.send(Frame.Text(json))
                }
                
                suspend fun subscribe(request: WebsocketSubscriptionRequest) {
                    val session = sessions[request.clientId]
                    
                    require(isValidWebsocketToken(request.authentication.websocketToken)) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid websocket token".also { println(it) }
                    }
                    
                    require(session != null) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Session does not exist".also { println(it) }
                    }
                    
                    require(session.clientId == request.clientId) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid client id".also { println(it) }
                    }
                    
                    require(this == session.connection) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid session connection"
                    }
                    
                    require(session.registered) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Session is not registered".also { println(it) }
                    }
                    
                    val user = getUserByToken(request.authentication.userToken)
                    require(user != null) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid user token".also { println(it) }
                    }
                    
                    require(user.id == request.authentication.userId) {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Invalid user token".also { println(it) }
                    }
                    
                    require(request.subscription == "/user/${request.authentication.userId}") {
                        // this.outgoing.send(Frame.Text(TODO()))
                        "Subscription channel does not match provided user id".also { println(it) }
                    }
                    
                    session.subscriptions += request.subscription
                    
                    val response = WebsocketSubscriptionResponse(
                        id = request.id,
                        clientId = session.clientId,
                        channel = "/meta/subscribe",
                        subscription = request.subscription,
                        successful = true,
                        error = null
                    )
                    
                    val json = websocketResponseSerializer.stringify(
                        WebsocketSubscriptionResponse.serializer(),
                        response
                    )
                    
                    session.connection.outgoing.send(Frame.Text(json))
                }
                
                suspend fun unsubscribe(request: WebsocketUnsubscriptionRequest) {
                    // TODO()
                }
                
                suspend fun process(request: WebsocketRequest) {
                    when (request) {
                        is WebsocketHandshakeRequest -> handshake(request)
                        is WebsocketConnectionRequest -> connect(request)
                        is WebsocketSubscriptionRequest -> subscribe(request)
                        is WebsocketUnsubscriptionRequest -> unsubscribe(request)
                    }
                }
                
                try {
                    incoming.consumeAsFlow()
                        .filterIsInstance<Frame.Text>()
                        .map { frame -> frame.readText() }
                        .map { data ->
                            websocketRequestSerializer.parse(
                                WebsocketRequest.serializer(),
                                data
                            )
                        }
                        .onEach { request -> launch { process(request) } }
                        .collect()
                } catch (e: Throwable) {
                    // Remove session on disconnect
                    sessions.filter { (_, session) -> session.clientId == clientId }
                        .onEach { (id, _) -> println("removed session: $id") }
                        .forEach { (id, _) -> sessions.remove(id) }
                }
            }
        }
    }
    
    private fun getUserByToken(token: String): User? {
        return User(token)
    }
    
    private fun isValidWebsocketToken(token: String): Boolean {
        return token == "websocketToken"
    }
    
    private fun generateNewClientId(): String {
        // val length = 16
        // val digits = 1..9
        // val builder = StringBuilder()
        // repeat(length) { builder.append(Random.nextInt(digits)) }
        // return builder.toString()
        
        return System.currentTimeMillis().toString()
    }
    
    fun listen() {
        server.start(wait = true)
    }
    
    data class User(val id: String)
    
    data class SessionInformation(
        var connection: WebSocketSession,
        var clientId: String,
        var registered: Boolean,
        val subscriptions: MutableSet<String>
    )
}
