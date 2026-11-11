package com.example.usbspeaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SpeakerService : Service() {

    companion object {
        const val DISCOVERY_PORT = 50505
        const val ACTION_STOP = "com.example.usbspeaker.STOP"

        const val STATE_STOPPED = 0
        const val STATE_SEARCHING = 1
        const val STATE_PLAYING = 2
        const val STATE_ERROR = 3

        @Volatile var lastStatus: String = "대기 중"
        @Volatile var lastState: Int = STATE_STOPPED
        @Volatile var statusListener: ((Int, String) -> Unit)? = null
        @Volatile var instance: SpeakerService? = null
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var enhancer: LoudnessEnhancer? = null
    @Volatile private var equalizer: Equalizer? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var pin = ""
    private var targetMs = 60
    private var maxMs = 180
    @Volatile private var boostMb = 0
    private var eqOn = false
    private var eqPreset = -1
    private var eqBands = IntArray(10)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!running) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            pin = prefs.getString("pin", "") ?: ""
            boostMb = prefs.getInt("boost_db", 0) * 100
            when (prefs.getInt("mode", 1)) {
                0 -> { targetMs = 30; maxMs = 100 }   // 저지연
                2 -> { targetMs = 120; maxMs = 400 }  // 안정
                else -> { targetMs = 60; maxMs = 180 } // 균형
            }
            eqOn = prefs.getBoolean("eq_on", false)
            eqPreset = prefs.getInt("eq_preset", -1)
            for (i in eqBands.indices) eqBands[i] = prefs.getInt("eq_b${'$'}i", 0)
            if (pin.length != 6) {
                setStatus(STATE_ERROR, "PIN 6자리를 입력한 뒤 다시 시작하세요")
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbSpeaker::play").apply { acquire() }
            worker = Thread({ mainLoop() }, "speaker-worker").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        setStatus(STATE_STOPPED, "중지됨", notify = false)
        super.onDestroy()
    }

    /** 재생 중에도 증폭 값을 바로 반영 */
    fun applyBoost(mb: Int) {
        boostMb = mb
        try { enhancer?.setTargetGain(mb) } catch (_: Exception) {}
    }

    /** 이퀄라이저 켜기/끄기 (재생 중 즉시 반영) */
    fun setEqEnabled(on: Boolean) {
        eqOn = on
        try { equalizer?.enabled = on } catch (_: Exception) {}
    }

    /** 프리셋 적용 (재생 중 즉시 반영) */
    fun applyEqPreset(p: Int) {
        eqPreset = p
        try {
            equalizer?.let { if (p in 0 until it.numberOfPresets.toInt()) it.usePreset(p.toShort()) }
        } catch (_: Exception) {}
    }

    /** 밴드 하나 조절 (재생 중 즉시 반영, mb = 밀리벨) */
    fun applyEqBand(band: Int, mb: Int) {
        eqPreset = -1
        if (band in eqBands.indices) eqBands[band] = mb
        try {
            equalizer?.let {
                val r = it.bandLevelRange
                it.setBandLevel(band.toShort(), mb.coerceIn(r[0].toInt(), r[1].toInt()).toShort())
            }
        } catch (_: Exception) {}
    }

    // ---------- 상태 / 알림 ----------

    private fun setStatus(state: Int, s: String, notify: Boolean = true) {
        lastStatus = s
        lastState = state
        mainHandler.post { statusListener?.invoke(state, s) }
        if (running && notify) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(s))
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("speaker") == null) {
            nm.createNotificationChannel(
                NotificationChannel("speaker", "USB 스피커", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SpeakerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "speaker")
            .setContentTitle("USB 스피커")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "중지", stopPi).build())
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val n = buildNotification(lastStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- 메인 동작 ----------

    private fun mainLoop() {
        while (running) {
            try {
                setStatus(STATE_SEARCHING, "PC 찾는 중… (USB 테더링과 PC 프로그램을 확인하세요)")
                val pc = discoverPc()
                if (pc == null) {
                    sleepQuiet(1000)
                    continue
                }
                stream(pc)
            } catch (e: AuthException) {
                if (running) {
                    setStatus(STATE_ERROR, "PIN이 올바르지 않습니다. PC 화면의 PIN을 다시 확인하세요.")
                    sleepQuiet(2500)
                }
            } catch (e: Exception) {
                if (running) {
                    setStatus(STATE_SEARCHING, "연결 끊김 — 다시 연결 시도 중")
                    sleepQuiet(1500)
                }
            }
        }
    }

    private class AuthException : Exception()

    private fun isUsbIface(name: String) =
        name.startsWith("rndis") || name.startsWith("ncm") || name.startsWith("usb")

    // USB 쪽 네트워크부터 PC를 찾고, 없으면 Wi-Fi에서도 찾음
    private fun discoverPc(): InetSocketAddress? {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val targets = mutableListOf<Pair<InetAddress, InetAddress>>()
        for (ni in ifaces.filter { it.isUp && !it.isLoopback }.sortedBy { if (isUsbIface(it.name)) 0 else 1 }) {
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address
                val bc = ia.broadcast
                if (addr is Inet4Address && bc != null) targets.add(addr to bc)
            }
        }
        for ((local, bc) in targets) {
            if (!running) return null
            try {
                DatagramSocket(InetSocketAddress(local, 0)).use { ds ->
                    ds.broadcast = true
                    ds.soTimeout = 700
                    val msg = "SPKR_DISCOVER2".toByteArray()
                    ds.send(DatagramPacket(msg, msg.size, bc, DISCOVERY_PORT))
                    val buf = ByteArray(64)
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val reply = String(p.data, 0, p.length)
                    if (reply.startsWith("SPKR_HERE")) {
                        val port = reply.substringAfter(' ').trim().toIntOrNull()
                        if (port != null) return InetSocketAddress(p.address, port)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun stream(pc: InetSocketAddress) {
        setStatus(STATE_SEARCHING, "PC에 연결 중… ${pc.address.hostAddress}")
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.receiveBufferSize = 64 * 1024
        s.connect(pc, 3000)
        s.soTimeout = 5000
        val input = DataInputStream(s.getInputStream())
        val output = s.getOutputStream()

        // --- PIN 인증 (HMAC-SHA256 챌린지 응답: PIN 자체는 전송되지 않음) ---
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic) != "SPK2") throw Exception("알 수 없는 신호")
        val nonce = ByteArray(16)
        input.readFully(nonce)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(), "HmacSHA256"))
        output.write(mac.doFinal(nonce))
        output.flush()
        if (input.read() != 1) throw AuthException()

        val rate = input.readInt()
        val ch = input.readInt()
        val frameBytes = 2 * ch

        val chMask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, rate * frameBytes * targetMs / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        enhancer = try {
            LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(boostMb)
                enabled = true
            }
        } catch (_: Exception) { null }
        equalizer = try {
            Equalizer(0, track.audioSessionId).apply {
                enabled = eqOn
                if (eqPreset in 0 until numberOfPresets.toInt()) {
                    usePreset(eqPreset.toShort())
                } else {
                    val r = bandLevelRange
                    for (i in 0 until numberOfBands.toInt()) {
                        val mb = eqBands.getOrElse(i) { 0 }
                        setBandLevel(i.toShort(), mb.coerceIn(r[0].toInt(), r[1].toInt()).toShort())
                    }
                }
            }
        } catch (_: Exception) { null }
        track.play()
        setStatus(STATE_PLAYING, "재생 중 (PC: ${pc.address.hostAddress})")

        val chunk = (rate / 100) * frameBytes               // 10ms
        val buf = ByteArray(chunk * 4)
        val maxBacklog = rate * frameBytes * maxMs / 1000
        val targetBacklog = rate * frameBytes * targetMs / 1000

        var bytesAcc = 0L
        var lastStat = System.currentTimeMillis()

        try {
            while (running) {
                val avail = input.available()
                if (avail > maxBacklog) {
                    var toSkip = (avail - targetBacklog) / frameBytes * frameBytes
                    while (toSkip > 0) {
                        val n = input.read(buf, 0, minOf(buf.size, toSkip))
                        if (n < 0) throw Exception("PC 연결 종료")
                        toSkip -= n
                    }
                    continue
                }
                val n = input.read(buf, 0, chunk)
                if (n < 0) throw Exception("PC 연결 종료")
                var total = n
                val rem = total % frameBytes
                if (rem != 0) {
                    input.readFully(buf, total, frameBytes - rem)
                    total += frameBytes - rem
                }
                track.write(buf, 0, total)
                bytesAcc += total

                val now = System.currentTimeMillis()
                if (now - lastStat >= 1000) {
                    val kbps = bytesAcc * 8 / (now - lastStat)
                    val bufMs = input.available() * 1000L / (rate * frameBytes)
                    bytesAcc = 0
                    lastStat = now
                    setStatus(
                        STATE_PLAYING,
                        "재생 중 · ${rate}Hz · ${kbps}kbps · 버퍼 ${bufMs}ms",
                        notify = false
                    )
                }
            }
        } finally {
            try { enhancer?.release() } catch (_: Exception) {}
            enhancer = null
            try { equalizer?.release() } catch (_: Exception) {}
            equalizer = null
            try { track.stop() } catch (_: Exception) {}
            track.release()
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }
}
