/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server.io

import lavalink.server.info.AppInfo
import lavalink.server.info.GitRepoState
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary
import com.github.natanbc.lavadsp.DspInfo
import dev.arbjerg.lavalink.api.AudioFilterExtension
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import dev.arbjerg.lavalink.api.WebSocketExtension
import io.undertow.websockets.core.WebSocketCallback
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.jsr.UndertowSession
import lavalink.server.config.ServerConfig
import lavalink.server.player.Player
import moe.kyokobot.koe.KoeClient
import moe.kyokobot.koe.KoeEventAdapter
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.internal.json.JsonObject
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SocketContext(
    val audioPlayerManager: AudioPlayerManager,
    val serverConfig: ServerConfig,
    private var session: WebSocketSession,
    private val socketServer: SocketServer,
    val userId: String,
    val koe: KoeClient,
    eventHandlers: Collection<PluginEventHandler>,
    webSocketExtensions: List<WebSocketExtension>,
    val filterExtensions: List<AudioFilterExtension>

) : ISocketContext {

    companion object {
        private val log = LoggerFactory.getLogger(SocketContext::class.java)
    }

    //guildId <-> Player
    private val players = ConcurrentHashMap<Long, Player>()

    val eventEmitter = EventEmitter(this, eventHandlers)
    val wsHandler = WebSocketHandler(this, webSocketExtensions, filterExtensions)
    val playerHandler = PlayerFunHandlers(this)

    @Volatile
    var sessionPaused = false
    private val resumeEventQueue = ConcurrentLinkedQueue<String>()

    /** Null means disabled. See implementation notes */
    var resumeKey: String? = null
    var resumeTimeout = 60L // Seconds
    private var sessionTimeoutFuture: ScheduledFuture<Unit>? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    val playerUpdateService: ScheduledExecutorService

    val playingPlayers: List<Player>
        get() {
            val newList = LinkedList<Player>()
            players.values.forEach { player -> if (player.isPlaying) newList.add(player) }
            return newList
        }


    init {
        executor.scheduleAtFixedRate(StatsTask(this, socketServer), 0, 1, TimeUnit.MINUTES)

        playerUpdateService = Executors.newScheduledThreadPool(2) { r ->
            val thread = Thread(r)
            thread.name = "player-update"
            thread.isDaemon = true
            thread
        }

        sendMessage(
            JSONObject()
            .put("op", "hello")
            .put("sessionId", session.id)
            .put("info", info())
        )
    }

    fun getPlayer(guildId: String) = getPlayer(guildId.toLong())

    override fun getPlayer(guildId: Long) = players.computeIfAbsent(guildId) {
        val player = Player(this, guildId, audioPlayerManager, serverConfig)
        eventEmitter.onNewPlayer(player)
        player
    }

    override fun getPlayers(): Map<Long, Player> {
        return players.toMap()
    }

    fun getExistingPlayer(guildId: String) = getExistingPlayer(guildId.toLong())

    fun getExistingPlayer(guildId: Long): Player? {
        return players.get(guildId)
    }

    /**
     * Gets or creates a voice connection
     */
    fun getVoiceConnection(player: Player): MediaConnection {
        val guildId = player.guildId
        var conn = koe.getConnection(guildId)
        if (conn == null) {
            conn = koe.createConnection(guildId)
            conn.registerListener(WsEventHandler(player))
        }
        return conn
    }

    fun getExistingVoiceConnection(player: Player): MediaConnection? {
        val guildId = player.guildId
        return koe.getConnection(guildId)
    }

    /**
     * Disposes of a voice connection
     */
    public override fun destroyPlayer(guild: Long) {
        val player = players.remove(guild)
        if (player != null) {
            eventEmitter.onDestroyPlayer(player)
            player.destroy()
        }
        koe.destroyConnection(guild)
    }

    fun pause() {
        sessionPaused = true
        sessionTimeoutFuture = executor.schedule<Unit>({
            socketServer.onSessionResumeTimeout(this)
        }, resumeTimeout, TimeUnit.SECONDS)
        eventEmitter.onSocketContextPaused()
    }

    override fun sendMessage(message: JSONObject) {
        send(message)
    }

    override fun getState(): ISocketContext.State = when {
        session.isOpen -> ISocketContext.State.OPEN
        sessionPaused -> ISocketContext.State.RESUMABLE
        else -> ISocketContext.State.DESTROYED
    }

    /**
     * Either sends the payload now or queues it up
     */
    fun send(payload: JSONObject) = send(payload.toString())

    private fun send(payload: String) {
        eventEmitter.onWebSocketMessageOut(payload)

        if (sessionPaused) {
            resumeEventQueue.add(payload)
            return
        }

        if (!session.isOpen) return

        val undertowSession = (session as StandardWebSocketSession).nativeSession as UndertowSession
        WebSockets.sendText(payload, undertowSession.webSocketChannel,
            object : WebSocketCallback<Void> {
                override fun complete(channel: WebSocketChannel, context: Void?) {
                    log.trace("Sent {}", payload)
                }

                override fun onError(channel: WebSocketChannel, context: Void?, throwable: Throwable) {
                    log.error("Error", throwable)
                }
            })
    }

    private fun info(): JSONObject {
        val json = JSONObject()
        val appInfo = AppInfo()

        json
        .put("version", appInfo.version.takeUnless { it.startsWith("@") } ?: "Unknown")
        .put("build", appInfo.buildNumber.takeUnless { it.startsWith("@") } ?: "Unofficial")
        .put("java", System.getProperty("java.version"))
        .put("lavaplayer", PlayerLibrary.VERSION)
        .put("lavadsp", DspInfo.VERSION)

        val gitRepoState = GitRepoState()
        if (gitRepoState.isLoaded) {
            json
            .put("buildTime", appInfo.buildTime)
            .put("commitTime", gitRepoState.commitTime * 1000)
            .put("commit", gitRepoState.commitIdAbbrev)
            .put("branch", gitRepoState.branch)
        }

        return json
    }

    /**
     * @return true if we can resume, false otherwise
     */
    fun stopResumeTimeout() = sessionTimeoutFuture?.cancel(false) ?: false

    fun resume(session: WebSocketSession) {
        sessionPaused = false
        this.session = session
        log.info("Replaying ${resumeEventQueue.size} events")

        // Bulk actions are not guaranteed to be atomic, so we need to do this imperatively
        while (resumeEventQueue.isNotEmpty()) {
            send(resumeEventQueue.remove())
        }

        players.values.forEach { SocketServer.sendPlayerUpdate(this, it) }
        send(JSONObject().put("op", "resume").put("sessionId", session.id))
    }

    internal fun shutdown() {
        log.info("Shutting down " + playingPlayers.size + " playing players.")
        executor.shutdown()
        playerUpdateService.shutdown()
        players.values.forEach {
            this.destroyPlayer(it.guildId)
        }
        koe.close()
        eventEmitter.onSocketContextDestroyed()
    }

    override fun closeWebSocket(closeCode: Int, reason: String?) {
        session.close(CloseStatus(closeCode, reason))
    }

    override fun closeWebSocket(closeCode: Int) {
        closeWebSocket(closeCode, null)
    }

    override fun closeWebSocket() {
        session.close()
    }

    private inner class WsEventHandler(private val player: Player) : KoeEventAdapter() {
        override fun gatewayClosed(code: Int, reason: String?, byRemote: Boolean) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceConnectionClosed")
            out.put("guildId", player.guildId.toString())
            out.put("reason", reason ?: JSONObject.NULL)
            out.put("code", code)
            out.put("byRemote", byRemote)

            send(out)

            SocketServer.sendPlayerUpdate(this@SocketContext, player)
        }

        override fun gatewayReady(target: InetSocketAddress, ssrc: Int) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceConnectionReady")
            out.put("guildId", player.guildId.toString())
            out.put("ssrc", ssrc)
            out.put("address", target.getAddress().toString())

            send(out)

            SocketServer.sendPlayerUpdate(this@SocketContext, player)
        }

        override fun gatewayResumed() {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceConnectionResumed")
            out.put("guildId", player.guildId.toString())

            send(out)

            SocketServer.sendPlayerUpdate(this@SocketContext, player)
        }

        override fun userConnected(id: String, audioSSRC: Int, videoSSRC: Int, rtxSSRC: Int) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceUserConnected")
            out.put("guildId", player.guildId.toString())
            out.put("userId", id)
            out.put("audioSSRC", audioSSRC)
            out.put("videoSSRC", videoSSRC)
            out.put("rtxSSRC", rtxSSRC)

            send(out)
        }

        override fun userDisconnected(id: String) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceUserDisconnected")
            out.put("guildId", player.guildId.toString())
            out.put("userId", id)

            send(out)
        }

        override fun externalIPDiscovered(target: InetSocketAddress) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceExternalIpDiscovered")
            out.put("guildId", player.guildId.toString())
            out.put("address", target.getAddress().toString())

            send(out)
        }

        override fun sessionDescription(session: JsonObject) {
            val out = JSONObject()
            out.put("op", "event")
            out.put("event", "VoiceSessionDescription")
            out.put("guildId", player.guildId.toString())
            out.put("session", JSONObject(session.toString()))

            send(out)
        }
    }
}
