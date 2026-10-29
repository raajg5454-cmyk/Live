package com.livehelp

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*

/** Signaling + WebRTC state. Capture starts only after the user approved Android's screen-capture dialog. */
object Session {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }
    interface Listener { fun onChange() }

    var listener: Listener? = null
    var state = State.DISCONNECTED; private set
    var note = ""; private set
    var sharing = false; private set

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var token = ""
    private var ws: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var pc: PeerConnection? = null
    private var videoTx: RtpSender? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var vSource: VideoSource? = null
    private var vTrack: VideoTrack? = null
    private var projData: Intent? = null
    private val pending = mutableListOf<IceCandidate>()
    private var remoteSet = false
    private var ice = listOf<PeerConnection.IceServer>()

    open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
    open class PcObs : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun setState(s: State, n: String) { state = s; note = n; listener?.onChange() }
    private fun tx(o: JSONObject) { ws?.send(o.toString()) }
    private fun msg(type: String) = JSONObject().put("type", type)

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val e = EglBase.create(); egl = e
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    /** [data] is the result of the system "start recording / casting" dialog the user just approved. */
    fun start(ctx: Context, tok: String, data: Intent) {
        if (state != State.DISCONNECTED) return
        app = ctx.applicationContext; token = tok; projData = data
        setState(State.CONNECTING, "Connecting…")
        Thread {
            try {
                val (code, j) = Api.get("/api/ice", token)
                if (code == 401) { Store.clear(app); ui.post { cleanup("Login expired. Reopen the app.") }; return@Thread }
                val arr = j.getJSONArray("iceServers")
                ice = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it); val u = o.getJSONArray("urls")
                    PeerConnection.IceServer.builder(List(u.length()) { i -> u.getString(i) }).apply {
                        if (o.has("username")) setUsername(o.getString("username")).setPassword(o.getString("credential"))
                    }.createIceServer()
                }
                val req = Request.Builder().url(BuildConfig.SERVER_URL.replaceFirst("http", "ws") + "/ws").build()
                ui.post {
                    ws = Api.http.newWebSocket(req, object : WebSocketListener() {
                        override fun onOpen(w: WebSocket, r: Response) {
                            w.send(msg("auth").put("token", token).toString())
                            w.send(msg("request_support").toString())
                        }
                        override fun onMessage(w: WebSocket, text: String) { ui.post { if (w == ws) handle(JSONObject(text)) } }
                        override fun onClosed(w: WebSocket, c: Int, r: String) { ui.post { if (w == ws) cleanup("Disconnected") } }
                        override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { ui.post { if (w == ws) cleanup("Connection lost") } }
                    })
                }
            } catch (e: Exception) { ui.post { cleanup("Cannot reach server") } }
        }.start()
    }

    private fun handle(m: JSONObject) {
        when (m.getString("type")) {
            "waiting" -> setState(State.CONNECTING, "Waiting for admin…")
            "connected" -> {
                startPeer()
                app.startForegroundService(Intent(app, ShareService::class.java))
                setState(State.CONNECTED, "Your screen is being shared")
            }
            "signal" -> onSignal(m.getJSONObject("data"))
            "ended" -> cleanup("Session ended")
        }
    }

    private fun startPeer() {
        val f = factory()
        val cfg = PeerConnection.RTCConfiguration(ice).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val p = f.createPeerConnection(cfg, object : PcObs() {
            override fun onIceCandidate(c: IceCandidate?) {
                c ?: return
                ui.post {
                    tx(msg("signal").put("data", JSONObject().put("ice", JSONObject()
                        .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))))
                }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s == PeerConnection.IceConnectionState.FAILED) ui.post { cleanup("Connection failed") }
            }
        }) ?: return cleanup("Connection failed")
        pc = p
        videoTx = p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)).sender
        p.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                d ?: return
                p.setLocalDescription(Sdp(), d)
                ui.post { tx(msg("signal").put("data", JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", d.description)))) }
            }
        }, MediaConstraints())
    }

    private fun onSignal(d: JSONObject) {
        val p = pc ?: return
        if (d.has("sdp")) {
            val s = d.getJSONObject("sdp")
            p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    ui.post { remoteSet = true; pending.forEach { p.addIceCandidate(it) }; pending.clear() }
                }
            }, SessionDescription(SessionDescription.Type.fromCanonicalForm(s.getString("type")), s.getString("sdp")))
        } else if (d.has("ice")) {
            val c = d.getJSONObject("ice")
            val ic = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
            if (remoteSet) p.addIceCandidate(ic) else pending.add(ic)
        }
    }

    /** Called by ShareService once the foreground notification is showing. */
    fun onServiceReady() {
        val data = projData ?: return; projData = null
        val f = factory ?: return
        try {
            val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() { ui.post { end() } }   // user revoked from Android system UI
            })
            helper = SurfaceTextureHelper.create("cap", egl!!.eglBaseContext)
            vSource = f.createVideoSource(true)
            cap.initialize(helper, app, vSource!!.capturerObserver)
            val dm = app.resources.displayMetrics
            val k = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
            cap.startCapture((dm.widthPixels * k).toInt() / 2 * 2, (dm.heightPixels * k).toInt() / 2 * 2, 15)
            vTrack = f.createVideoTrack("screen", vSource)
            videoTx?.setTrack(vTrack, false)
            capturer = cap; sharing = true; listener?.onChange()
        } catch (e: Exception) { end() }
    }

    private fun stopShare() {
        sharing = false; projData = null
        try { videoTx?.setTrack(null, false) } catch (_: Exception) {}
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        vTrack?.dispose(); vTrack = null
        vSource?.dispose(); vSource = null
        helper?.dispose(); helper = null
        if (::app.isInitialized) app.stopService(Intent(app, ShareService::class.java))
    }

    /** Stop: ends sharing immediately and closes the session. */
    fun end() { tx(msg("end")); cleanup("Stopped") }

    private fun cleanup(n: String) {
        stopShare()
        val w = ws; ws = null; w?.close(1000, null)
        try { pc?.close() } catch (_: Exception) {}
        pc = null; videoTx = null; remoteSet = false; pending.clear()
        setState(State.DISCONNECTED, n)
    }
}
