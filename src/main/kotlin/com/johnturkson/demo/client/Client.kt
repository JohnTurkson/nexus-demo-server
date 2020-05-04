package com.johnturkson.demo.client

import com.johnturkson.demo.client.Client.State.*
import com.johnturkson.demo.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

suspend fun main() {
    val user = "1"
    
    while (true) {
        for (i in 1..10) {
            val created = Client.createLinkinbioPost(user, "post $i", "example.com/image/$i")
            println("created: $created")
            delay(50)
        }
        
        val linkinbioPosts = Client.getLinkinbioPosts(user).toMutableList()
        println("initial posts: $linkinbioPosts")
        
        delay(2000)
        
        linkinbioPosts.map { post ->
            val updated = Client.updateLinkinbioPost(post.id, "updated ${post.url}", post.image)
            println("updated: $updated")
            delay(50)
        }
        
        delay(2000)
        
        linkinbioPosts
            .map { post ->
                val deleted = Client.deleteLinkinbioPost(post.id)
                println("deleted: $deleted")
                delay(50)
                post
            }
            .forEach { deleted -> linkinbioPosts.removeIf { post -> post.id == deleted.id } }
        
        println("final posts: $linkinbioPosts")
        
        delay(2000)
    }
    
    Client.disconnect()
}

object Client {
    // val user = "1"
    lateinit var clientId: String
    val token = "1"
    val host = "localhost:8080"
    val websocketToken = "websocketToken"
    val requests: Channel<WebsocketRequest> = Channel(BUFFERED)
    val responses: BroadcastChannel<Any> = BroadcastChannel(BUFFERED)
    private val state: ConflatedBroadcastChannel<State> = ConflatedBroadcastChannel(NOT_CONNECTED)
    var lastId = 0
    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = KotlinxSerializer(httpRequestSerializer)
        }
    }
    
    enum class State {
        NOT_CONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISCONNECTED
    }
    
    suspend fun getLinkinbioPost(id: String): LinkinbioPost {
        return client.get("http://localhost:8080/LinkinbioPost/$id") {
            header("Token", token)
            header("Content-Type", "application/json")
        }
    }
    
    suspend fun getLinkinbioPosts(user: String): List<LinkinbioPost> {
        return client.get("http://localhost:8080/LinkinbioPosts/$user") {
            header("Token", token)
            header("Content-Type", "application/json")
        }
    }
    
    suspend fun createLinkinbioPost(user: String, url: String, image: String): LinkinbioPost {
        return client.post("http://localhost:8080/CreateLinkinbioPost") {
            header("Token", token)
            header("Content-Type", "application/json")
            body = CreateLinkinbioPostRequest(url, image)
        }
    }
    
    suspend fun updateLinkinbioPost(id: String, url: String, image: String): LinkinbioPost {
        return client.post("http://localhost:8080/UpdateLinkinbioPost") {
            header("Token", token)
            header("Content-Type", "application/json")
            body = UpdateLinkinbioPostRequest(id, url, image)
        }
    }
    
    suspend fun deleteLinkinbioPost(id: String): LinkinbioPost {
        return client.delete("http://localhost:8080/delete/$id") {
            header("Token", token)
            header("Content-Type", "application/json")
        }
    }
    
    suspend fun subscribe(id: String) {
        println("waiting for connected")
        state.openSubscription().consumeAsFlow()
            .filter { it == CONNECTED }
            .first()
        println("waited for connected")
        
        val token = id
        val subscriptionRequest = WebsocketSubscriptionRequest(
            id = generateNewRequestId(),
            clientId = clientId,
            subscription = "/user/$id",
            authentication = WebsocketAuthenticationParameters(id, token, websocketToken)
        )
        
        val response = GlobalScope.launch {
            // wait until response
            responses.openSubscription().consumeAsFlow()
                .filterIsInstance<WebsocketSubscriptionResponse>()
                .filter { response -> response.id == subscriptionRequest.id }
                .first()
            println("received subscription")
        }
        
        requests.send(subscriptionRequest)
        
        response.join()
    }
    
    suspend fun connect() {
        suspend fun listen(incoming: ReceiveChannel<Frame>) {
            GlobalScope.launch {
                // listen to future responses
                incoming.consumeAsFlow()
                    .filterIsInstance<Frame.Text>()
                    .map { response -> response.readText() }
                    .onEach { json -> process(json) }
                    .launchIn(GlobalScope)
            }
        }
        
        suspend fun handle(outgoing: SendChannel<Frame>) {
            GlobalScope.launch {
                // handle client requests
                requests.consumeAsFlow()
                    .map { request ->
                        websocketRequestSerializer.stringify(
                            WebsocketRequest.serializer(),
                            request
                        )
                    }
                    .map { json -> Frame.Text(json) }
                    .onEach { request -> outgoing.send(request) }
                    .collect()
            }
        }
        
        GlobalScope.launch {
            client.ws(
                method = HttpMethod.Get,
                host = "localhost",
                port = 8080,
                path = "/updates"
            ) {
                state.send(CONNECTING)
                
                listen(incoming)
                handle(outgoing)
                
                val handshakeRequest = WebsocketHandshakeRequest(
                    id = generateNewRequestId(),
                    version = "1.0",
                    supportedConnectionTypes = listOf("websocket")
                )
                
                val handshakeResponse = GlobalScope.launch {
                    // wait until response
                    responses.openSubscription().consumeAsFlow()
                        .filterIsInstance<WebsocketHandshakeResponse>()
                        .filter { response -> response.id == handshakeRequest.id }
                        .first()
                }
                
                requests.send(handshakeRequest)
                
                handshakeResponse.join()
                
                val connectionRequest = WebsocketConnectionRequest(
                    id = generateNewRequestId(),
                    clientId = clientId,
                    connectionType = "websocket"
                )
                
                val connectionResponse = GlobalScope.launch {
                    // wait until response
                    responses.openSubscription().consumeAsFlow()
                        .filterIsInstance<WebsocketConnectionResponse>()
                        .filter { response -> response.id == connectionRequest.id }
                        .first()
                }
                
                requests.send(connectionRequest)
                
                println("waiting for connection")
                connectionResponse.join()
                println("received connection")
                
                state.send(CONNECTED)
                
                // wait until closed
                state.openSubscription().consumeAsFlow()
                    .filter { it == DISCONNECTING || it == DISCONNECTED }
                    .first()
                println("websocket is closed")
            }
            
            state.send(DISCONNECTED)
        }
    }
    
    suspend fun disconnect() {
        val disconnected = GlobalScope.launch {
            state.openSubscription().consumeAsFlow()
                .takeWhile { state -> state != DISCONNECTED }
                .collect()
        }
        
        state.send(DISCONNECTING)
        
        disconnected.join()
    }
    
    private suspend fun generateNewRequestId(): String {
        return (++lastId).toString()
    }
    
    private suspend fun processHandshakeResponse(response: WebsocketHandshakeResponse) {
        this.clientId = response.clientId
        
    }
    
    private suspend fun processConnectionResponse(response: WebsocketConnectionResponse) {
        
    }
    
    private suspend fun processSubscriptionResponse(response: WebsocketSubscriptionResponse) {
        
    }
    
    private suspend fun processUnsubscriptionResponse(response: WebsocketUnsubscriptionResponse) {
        
    }
    
    private suspend fun processUpdateResponse(response: WebsocketUpdateResponse) {
        when (response.data) {
            is CreateLinkinbioPostResponseData -> println("created: $response")
            is UpdateLinkinbioPostResponseData -> println("updated: $response")
            is DeleteLinkinbioPostResponseData -> println("deleted: $response")
        }
    }
    
    private suspend fun process(json: String) {
        println("response: $json")
        
        val response = websocketResponseSerializer.parse(
            WebsocketResponse.serializer(),
            json
        )
        // workaround due to no type information being passed back in response
        val deserializer = when {
            response.successful == false -> TODO()
            response.channel == null -> TODO()
            response.channel == "/meta/handshake" -> WebsocketHandshakeResponse.serializer()
            response.channel == "/meta/connect" -> WebsocketConnectionResponse.serializer()
            response.channel == "/meta/subscribe" -> WebsocketSubscriptionResponse.serializer()
            response.channel == "/meta/unsubscribe" -> WebsocketUnsubscriptionResponse.serializer()
            response.channel.startsWith("/user/") -> WebsocketUpdateResponse.serializer()
            else -> throw Exception("invalid response type")
        }
        
        val parsed = websocketResponseSerializer.parse(deserializer, json)
        when (parsed) {
            is WebsocketHandshakeResponse -> processHandshakeResponse(parsed)
            is WebsocketConnectionResponse -> processConnectionResponse(parsed)
            is WebsocketSubscriptionResponse -> processSubscriptionResponse(parsed)
            is WebsocketUnsubscriptionResponse -> processUnsubscriptionResponse(parsed)
            is WebsocketUpdateResponse -> processUpdateResponse(parsed)
            else -> throw Exception("invalid response type")
        }
        
        responses.send(parsed)
    }
}
