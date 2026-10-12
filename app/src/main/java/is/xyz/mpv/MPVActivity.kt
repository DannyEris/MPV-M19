package `is`.xyz.mpv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvFormat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import kotlin.math.pow
import kotlin.math.roundToInt

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver {

    private lateinit var binding: PlayerBinding
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var duration = 0
    private var currentPos = 0
    private var currentFilePath: String = ""
    private var videoMemoryKey: String = ""

    // 本地调试日志路径
    private var logFilePath: String = ""

    // 封装容器解析出的真实物理视频宽高
    private var containerVideoWidth = 0
    private var containerVideoHeight = 0

    // 屏幕比例模式:
    // 0: 默认原始, 1: 裁切填充, 2: 16:9, 3: 4:3, 4: 拉伸全屏
    private var aspectIndex = 0

    // 画面自由等比缩放倍率 (基数 0.0 代表 100%)
    private var currentVideoZoom = 0.0

    // 字幕微调参数
    private var currentSubScale = 1.0
    private var currentSubPos = 100
    private var currentSubDelay = 0.0

    // 解码模式: 0: HW (mediacodec-copy), 1: HW+ (mediacodec), 2: SW (no)
    private var decoderIndex = 0

    // HDR 模式: 0: 自动(Mobius), 1: 鲜艳(Hable), 2: 关闭(Clip)
    private var hdrMode = 0

    // 片头片尾 (秒)
    private var skipIntroSec: Int = 0
    private var skipOutroSec: Int = 0
    private var introSkipped = false
    private var hasPlayedBeforeOutro = false

    // 进度条防抖与锁定
    private var userIsOperatingSeekbar = false
    private var seekTargetSec = -1.0
    private val seekbarCommitRunnable = Runnable {
        if (seekTargetSec >= 0) {
            MPVLib.setPropertyDouble("time-pos", seekTargetSec)
        }
        handler.postDelayed({ userIsOperatingSeekbar = false }, 800)
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        try {
            Utils.copyAssets(this)
        } catch (e: Exception) {
            // Ignore asset copy errors
        }

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initPlayerCore()
        setupUIControls()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun writeAppDebugLog(content: String) {
        try {
            val sdcardDir = Environment.getExternalStorageDirectory()
            val debugFile = if (sdcardDir != null && sdcardDir.exists()) {
                File(sdcardDir, "mpv_app.log")
            } else {
                File(applicationContext.filesDir, "mpv_app.log")
            }
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            FileOutputStream(debugFile, true).use { out ->
                out.write("[$timeStr] $content\n".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Ignore log write errors
        }
    }

    private fun initPlayerCore() {
        val configDir = applicationContext.filesDir.path
        val cacheDir = applicationContext.cacheDir.path

        binding.player.initialize(configDir, cacheDir)
        MPVLib.addObserver(this)

        // 1. 初始化全量日志文件 (存储在 /sdcard/mpv.log)
        val sdcardDir = Environment.getExternalStorageDirectory()
        val logFile = if (sdcardDir != null && sdcardDir.exists()) {
            File(sdcardDir, "mpv.log")
        } else {
            File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "mpv.log")
        }
        logFilePath = logFile.absolutePath

        MPVLib.setPropertyString("log-file", logFilePath)
        MPVLib.setPropertyString("msg-level", "all=v")

        MPVLib.command(arrayOf("apply-profile", "fast"))

        // 2. 硬件解码配置：针对 S912 芯片锁定稳妥的 mediacodec-copy
        MPVLib.setPropertyString("hwdec", "mediacodec-copy")
        MPVLib.setPropertyString("hwdec-codecs", "all")
        MPVLib.setPropertyInt("hwdec-extra-frames", 6) // 将缓冲池由 16 缩减为 6，降低总线排队延迟

        // 3. 核心音画同步与丢帧控制：允许开播与寻道时丢帧追赶；禁止开播时阻塞
        MPVLib.setPropertyString("framedrop", "vo")
        MPVLib.setPropertyString("hr-seek", "no")
        MPVLib.setPropertyString("hr-seek-framedrop", "yes") // 允许寻道期间丢弃过渡帧，杜绝开播产生 10 秒初始延迟
        MPVLib.setPropertyString("video-sync", "audio")

        // 4. 音频输出：成熟的 16-bit 整数立体声，彻底根治多声道时钟漂移
        MPVLib.setPropertyString("audio-format", "s16")
        MPVLib.setPropertyString("audio-channels", "stereo")
        MPVLib.setPropertyString("ao", "audiotrack")
        MPVLib.setPropertyDouble("audio-buffer", 0.2)
        MPVLib.setPropertyString("audio-pitch-correction", "no") // 关闭变调运算，释放 A53 CPU 算力

        // 5. 比例保持机制
        MPVLib.setPropertyString("keepaspect", "yes")
        MPVLib.setPropertyDouble("panscan", 0.0)

        // 6. 极速渲染管线调优
        MPVLib.setPropertyString("opengl-pbo", "no")
        MPVLib.setPropertyString("scale", "bilinear")
        MPVLib.setPropertyString("cscale", "bilinear")
        MPVLib.setPropertyString("dscale", "bilinear")
        MPVLib.setPropertyString("fbo-format", "rgba8")
        MPVLib.setPropertyString("dither-depth", "no")
        MPVLib.setPropertyString("dither", "no")
        MPVLib.setPropertyString("deband", "no")
        MPVLib.setPropertyString("correct-downscaling", "no")
        MPVLib.setPropertyString("sigmoid-upscaling", "no")
        MPVLib.setPropertyString("vd-lavc-threads", "4")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "nonref")

        // 7. 色彩映射
        MPVLib.setPropertyString("target-prim", "auto")
        MPVLib.setPropertyString("target-trc", "auto")
        MPVLib.setPropertyString("tone-mapping", "auto")
        MPVLib.setPropertyString("hdr-compute-peak", "no")

        // 8. 彻底优化 OSD 渲染开销
        MPVLib.setPropertyString("sub-font-provider", "none")

        // 9. 内存与缓存调优：将缓存降至 64MB，避免 128MB 撑满 2GB 内存导致总线带宽被系统内存回收抢占
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyString("cache-secs", "20")
        MPVLib.setPropertyString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "${16 * 1024 * 1024}")
        MPVLib.setPropertyString("demuxer-readahead-secs", "15")
        MPVLib.setPropertyString("demuxer-hysteresis-secs", "3")
        MPVLib.setPropertyString("demuxer-thread", "yes")

        // 10. 网络与 TLS 优化
        val caFile = File(configDir, "cacert.pem")
        if (caFile.exists()) {
            MPVLib.setPropertyString("tls-ca-file", caFile.absolutePath)
        }
        MPVLib.setPropertyString("tls-verify", "no")
        MPVLib.setPropertyString("network-timeout", "20")
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5,tls_verify=0")
        MPVLib.setPropertyString("demuxer-lavf-o", "tls_verify=0")

        // 初始化 HDR 按钮状态
        hdrMode = sharedPrefs.getInt("hdr_to_sdr_mode", 0)
        applyHdrMode(hdrMode, updateText = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun parseUrlAndHeaders(rawPath: String): Pair<String, Map<String, String>> {
        var cleanUrl = rawPath.trim()
        val extraHeaders = mutableMapOf<String, String>()

        if (cleanUrl.contains("|")) {
            val parts = cleanUrl.split("|", limit = 2)
            cleanUrl = parts[0].trim()
            if (parts.size > 1) {
                parts[1].trim().split("&").forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotEmpty() && kv[1].isNotEmpty()) {
                        extraHeaders[kv[0].trim()] = kv[1].trim()
                    }
                }
            }
        }

        if (cleanUrl.contains(";headers=") || cleanUrl.contains(";header=")) {
            val delimiter = if (cleanUrl.contains(";headers=")) ";headers=" else ";header="
            val parts = cleanUrl.split(delimiter, limit = 2)
            cleanUrl = parts[0].trim()
            if (parts.size > 1) {
                try {
                    val json = JSONObject(parts[1].trim())
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = json.optString(k, "")
                        if (k.isNotEmpty() && v.isNotEmpty()) extraHeaders[k] = v
                    }
                } catch (e: Exception) {
                    // Ignore parse error
                }
            }
        }

        if (cleanUrl.contains(";{") && cleanUrl.endsWith("}")) {
            val idx = cleanUrl.indexOf(";{")
            val headerContent = cleanUrl.substring(idx + 2, cleanUrl.length - 1)
            cleanUrl = cleanUrl.substring(0, idx).trim()
            headerContent.split("&&").forEach { item ->
                val kv = item.split("@", limit = 2)
                if (kv.size == 2 && kv[0].isNotEmpty() && kv[1].isNotEmpty()) {
                    extraHeaders[kv[0].trim()] = kv[1].trim()
                }
            }
        }

        return Pair(cleanUrl, extraHeaders)
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = intent.data
        var path = uri?.toString()
        if (path.isNullOrEmpty()) {
            path = intent.getStringExtra("url") ?: intent.getStringExtra("filepath")
        }

        if (path.isNullOrEmpty()) {
            writeAppDebugLog("ERROR: Intent received empty video path!")
            Toast.makeText(this, R.string.error_no_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        writeAppDebugLog("Received Raw Path: $path")

        val (cleanedPath, urlExtractedHeaders) = parseUrlAndHeaders(path)
        currentFilePath = cleanedPath

        // 核心突破 1：针对百度网盘 Kaiser 代理（端口 38109 报 502 问题），自动解构提取真实的 DLNA 直链
        if (currentFilePath.contains("127.0.0.1") && (currentFilePath.contains("baidupcs.com") || currentFilePath.contains("key=baidupan"))) {
            try {
                val parsedUri = Uri.parse(currentFilePath)
                val directBaiduUrl = parsedUri.getQueryParameter("url")
                if (!directBaiduUrl.isNullOrEmpty()) {
                    val decodedUrl = URLDecoder.decode(directBaiduUrl, "UTF-8")
                    writeAppDebugLog("Baidu Kaiser Bypass: Direct PCS URL extracted: $decodedUrl")
                    currentFilePath = decodedUrl
                }
            } catch (e: Exception) {
                writeAppDebugLog("Baidu URL extraction failed: ${e.message}")
            }
        }

        writeAppDebugLog("Cleaned Target Path: $currentFilePath")

        introSkipped = false
        hasPlayedBeforeOutro = false
        containerVideoWidth = 0
        containerVideoHeight = 0
        currentVideoZoom = 0.0

        var title = intent.getStringExtra("title")
        if (title.isNullOrEmpty()) {
            title = uri?.lastPathSegment ?: "Network Stream"
        }
        binding.titleTextView.text = title

        val cleanUri = Uri.parse(currentFilePath)
        val cleanPath = if (cleanUri != null && cleanUri.scheme != null) {
            "${cleanUri.scheme}://${cleanUri.host ?: ""}${cleanUri.path ?: ""}"
        } else {
            currentFilePath
        }
        videoMemoryKey = if (!title.isNullOrEmpty()) title else cleanPath

        skipIntroSec = sharedPrefs.getInt("intro_${videoMemoryKey.hashCode()}", 0)
        skipOutroSec = sharedPrefs.getInt("outro_${videoMemoryKey.hashCode()}", 0)
        updateIntroOutroButtonsText()

        val headersMap = TreeMap<String, String>(java.lang.String.CASE_INSENSITIVE_ORDER)
        headersMap.putAll(urlExtractedHeaders)

        val httpHeadersBundle = intent.getBundleExtra("headers")
            ?: intent.getBundleExtra("header")
            ?: intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
        httpHeadersBundle?.let { bundle ->
            for (k in bundle.keySet()) {
                val v = bundle.getString(k) ?: bundle.get(k)?.toString() ?: ""
                if (k.isNotEmpty() && v.isNotEmpty()) headersMap[k] = v
            }
        }

        val headersArray = intent.getStringArrayExtra("headers")
            ?: intent.getStringArrayExtra("header")
        if (headersArray != null && headersArray.size >= 2) {
            for (i in 0 until headersArray.size step 2) {
                val k = headersArray[i]
                val v = headersArray.getOrNull(i + 1) ?: ""
                if (k.isNotEmpty() && v.isNotEmpty()) headersMap[k] = v
            }
        }

        try {
            val serializableMap = (intent.getSerializableExtra("headers") as? Map<*, *>)
                ?: (intent.getSerializableExtra("header") as? Map<*, *>)
            serializableMap?.forEach { (k, v) ->
                val key = k?.toString() ?: ""
                val value = v?.toString() ?: ""
                if (key.isNotEmpty() && value.isNotEmpty()) headersMap[key] = value
            }
        } catch (e: Exception) {
            // Ignore format mismatch
        }

        val headersJsonStr = intent.getStringExtra("headers")
            ?: intent.getStringExtra("header")
        if (!headersJsonStr.isNullOrEmpty() && headersJsonStr.trim().startsWith("{")) {
            try {
                val json = JSONObject(headersJsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "")
                    if (k.isNotEmpty() && v.isNotEmpty()) headersMap[k] = v
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }

        val explicitUa = intent.getStringExtra("User-Agent")
            ?: intent.getStringExtra("user-agent")
            ?: intent.getStringExtra("user_agent")
        if (!explicitUa.isNullOrEmpty()) headersMap["User-Agent"] = explicitUa

        val explicitRef = intent.getStringExtra("Referer")
            ?: intent.getStringExtra("referer")
        if (!explicitRef.isNullOrEmpty()) headersMap["Referer"] = explicitRef

        val explicitCookie = intent.getStringExtra("Cookie")
            ?: intent.getStringExtra("cookie")
        if (!explicitCookie.isNullOrEmpty()) headersMap["Cookie"] = explicitCookie

        val isBaidu = currentFilePath.contains("baidupcs.com", ignoreCase = true) ||
                currentFilePath.contains("key=baidupan", ignoreCase = true)

        val defaultBrowserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        // 1. 设置专属 User-Agent
        var finalUa = headersMap["User-Agent"]
        if (finalUa.isNullOrEmpty()) {
            finalUa = if (isBaidu) "themisdlna" else defaultBrowserUa
        }
        MPVLib.setPropertyString("user-agent", finalUa)
        writeAppDebugLog("Applied User-Agent: $finalUa")

        // 2. 设置专属 Referer
        val customRef = headersMap["Referer"]
        if (!customRef.isNullOrEmpty()) {
            MPVLib.setPropertyString("referrer", customRef)
            writeAppDebugLog("Applied Referer: $customRef")
        } else {
            MPVLib.setPropertyString("referrer", "")
        }

        // 3. 核心突破 2：构建请求头列表时，严格排除 User-Agent 与 Referer！
        // 彻底解决瓜子站源由于 HTTP 头部出现重复两行 Referer 而被 CDN 判定为非法请求、强行跳防盗链视频的 Bug！
        val mpvHeaderList = mutableListOf<String>()
        for ((k, v) in headersMap) {
            if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Referer", ignoreCase = true)) {
                val safeVal = v.replace(",", "\\,")
                mpvHeaderList.add("$k: $safeVal")
            }
        }
        if (mpvHeaderList.isNotEmpty()) {
            val joined = mpvHeaderList.joinToString(",")
            MPVLib.setPropertyString("http-header-fields", joined)
            writeAppDebugLog("Applied http-header-fields: $joined")
        } else {
            MPVLib.setPropertyString("http-header-fields", "")
        }

        val startPositionMs = intent.getIntExtra("position", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("position", 0L)
        if (startPositionMs > 0L) {
            val startSec = (startPositionMs / 1000).toInt()
            MPVLib.setPropertyString("start", startSec.toString())
        } else {
            MPVLib.setPropertyString("start", "0")
        }

        writeAppDebugLog("Calling MPV loadfile: $currentFilePath")
        MPVLib.command(arrayOf("loadfile", currentFilePath))
        hideControls()
    }

    private fun setupUIControls() {
        binding.playBtn.setOnClickListener {
            togglePlayPause()
            resetHideControlsTimer()
        }

        binding.btnDecoder.setOnClickListener {
            cycleDecoder()
            resetHideControlsTimer()
        }

        binding.btnStats.setOnClickListener {
            MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
            resetHideControlsTimer()
        }

        binding.playbackSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    userIsOperatingSeekbar = true
                    seekTargetSec = progress.toDouble() / 1000.0 * duration
                    binding.timeCurrent.text = formatTime(seekTargetSec.roundToInt())

                    handler.removeCallbacks(seekbarCommitRunnable)
                    handler.postDelayed(seekbarCommitRunnable, 200)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userIsOperatingSeekbar = true
                handler.removeCallbacks(seekbarCommitRunnable)
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(seekbarCommitRunnable)
                val progress = seekBar?.progress ?: 0
                if (duration > 0) {
                    seekTargetSec = progress.toDouble() / 1000.0 * duration
                    MPVLib.setPropertyDouble("time-pos", seekTargetSec)
                }
                handler.postDelayed({ userIsOperatingSeekbar = false }, 800)
                resetHideControlsTimer()
            }
        })

        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
            resetHideControlsTimer()
        }

        binding.btnZoomIn.setOnClickListener {
            currentVideoZoom = (currentVideoZoom + 0.05).coerceAtMost(1.5)
            MPVLib.setPropertyDouble("video-zoom", currentVideoZoom)
            val percent = (2.0.pow(currentVideoZoom) * 100).roundToInt()
            Toast.makeText(this, "画面放大: ${percent}%", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        binding.btnZoomReset.setOnClickListener {
            currentVideoZoom = 0.0
            MPVLib.setPropertyDouble("video-zoom", 0.0)
            Toast.makeText(this, "画面缩放已重置 (100%)", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        binding.btnZoomOut.setOnClickListener {
            currentVideoZoom = (currentVideoZoom - 0.05).coerceAtLeast(-1.0)
            MPVLib.setPropertyDouble("video-zoom", currentVideoZoom)
            val percent = (2.0.pow(currentVideoZoom) * 100).roundToInt()
            Toast.makeText(this, "画面缩小: ${percent}%", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        binding.btnHdrMode.setOnClickListener {
            hdrMode = (hdrMode + 1) % 3
            sharedPrefs.edit().putInt("hdr_to_sdr_mode", hdrMode).apply()
            applyHdrMode(hdrMode, updateText = true)
            resetHideControlsTimer()
        }

        binding.btnSubSettings.setOnClickListener {
            showSubtitleDialog()
            resetHideControlsTimer()
        }

        binding.btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            resetHideControlsTimer()
        }

        binding.btnVideoTrack.setOnClickListener {
            showVideoTrackDialog()
            resetHideControlsTimer()
        }

        binding.btnIntro.setOnClickListener {
            skipIntroSec = currentPos
            sharedPrefs.edit().putInt("intro_${videoMemoryKey.hashCode()}", skipIntroSec).apply()
            updateIntroOutroButtonsText()
            Toast.makeText(this, getString(R.string.toast_intro_set, formatTime(skipIntroSec)), Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }
        binding.btnIntro.setOnLongClickListener {
            skipIntroSec = 0
            sharedPrefs.edit().remove("intro_${videoMemoryKey.hashCode()}").apply()
            updateIntroOutroButtonsText()
            Toast.makeText(this, R.string.toast_intro_reset, Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
            true
        }

        binding.btnOutro.setOnClickListener {
            skipOutroSec = currentPos
            hasPlayedBeforeOutro = false
            sharedPrefs.edit().putInt("outro_${videoMemoryKey.hashCode()}", skipOutroSec).apply()
            updateIntroOutroButtonsText()
            Toast.makeText(this, getString(R.string.toast_outro_set, formatTime(skipOutroSec)), Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
            true
        }
        binding.btnOutro.setOnLongClickListener {
            skipOutroSec = 0
            hasPlayedBeforeOutro = false
            sharedPrefs.edit().remove("outro_${videoMemoryKey.hashCode()}").apply()
            updateIntroOutroButtonsText()
            Toast.makeText(this, R.string.toast_outro_reset, Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
            true
        }

        binding.speedBtn.setOnClickListener {
            val speeds = arrayOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            val speedValues = arrayOf(0.75, 1.0, 1.25, 1.5, 2.0)
            AlertDialog.Builder(this)
                .setTitle(R.string.title_speed_dialog)
                .setItems(speeds) { _, which ->
                    MPVLib.setPropertyDouble("speed", speedValues[which])
                    binding.speedBtn.text = speeds[which]
                }.show()
            resetHideControlsTimer()
        }

        binding.root.setOnClickListener {
            if (binding.controls.visibility == View.VISIBLE) hideControls() else showControls()
        }
    }

    private fun cycleDecoder() {
        decoderIndex = (decoderIndex + 1) % 3
        when (decoderIndex) {
            0 -> {
                MPVLib.setPropertyString("hwdec", "mediacodec-copy")
                binding.btnDecoder.text = "解码: HW"
                Toast.makeText(this, "已切换为: 硬件拷贝 (HW，M19推荐)", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                MPVLib.setPropertyString("hwdec", "mediacodec")
                binding.btnDecoder.text = "解码: HW+"
                Toast.makeText(this, "已切换为: 硬件直出 (HW+)", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                MPVLib.setPropertyString("hwdec", "no")
                binding.btnDecoder.text = "解码: SW"
                Toast.makeText(this, "已切换为: CPU软解 (SW)", Toast.LENGTH_SHORT).show()
            }
        }
        applyCurrentAspectRatio()
    }

    private fun togglePlayPause() {
        val paused = MPVLib.getPropertyBoolean("pause") ?: false
        MPVLib.setPropertyBoolean("pause", !paused)
        updatePlayBtnState(!paused)
    }

    private fun updateIntroOutroButtonsText() {
        binding.btnIntro.text = if (skipIntroSec > 0) {
            "${getString(R.string.btn_skip_intro)}: ${formatTime(skipIntroSec)}"
        } else {
            getString(R.string.btn_skip_intro)
        }

        binding.btnOutro.text = if (skipOutroSec > 0) {
            "${getString(R.string.btn_skip_outro)}: ${formatTime(skipOutroSec)}"
        } else {
            getString(R.string.btn_skip_outro)
        }
    }

    private fun applyHdrMode(mode: Int, updateText: Boolean) {
        when (mode) {
            0 -> {
                MPVLib.setPropertyString("tone-mapping", "mobius")
                MPVLib.setPropertyString("target-prim", "bt.709")
                MPVLib.setPropertyString("target-trc", "gamma2.2")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: ${getString(R.string.hdr_auto)}"
            }
            1 -> {
                MPVLib.setPropertyString("tone-mapping", "hable")
                MPVLib.setPropertyString("target-prim", "bt.709")
                MPVLib.setPropertyString("target-trc", "gamma2.2")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: 鲜艳"
            }
            2 -> {
                MPVLib.setPropertyString("tone-mapping", "clip")
                MPVLib.setPropertyString("target-prim", "auto")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: ${getString(R.string.hdr_off)}"
            }
        }
    }

    private fun applyCurrentAspectRatio() {
        when (aspectIndex) {
            0 -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                if (containerVideoWidth > 0 && containerVideoHeight > 0) {
                    MPVLib.setPropertyString("video-aspect-override", "$containerVideoWidth:$containerVideoHeight")
                } else {
                    MPVLib.setPropertyString("video-aspect-override", "-1")
                }
                binding.btnAspectRatio.text = getString(R.string.aspect_default)
            }
            1 -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                if (containerVideoWidth > 0 && containerVideoHeight > 0) {
                    MPVLib.setPropertyString("video-aspect-override", "$containerVideoWidth:$containerVideoHeight")
                } else {
                    MPVLib.setPropertyString("video-aspect-override", "-1")
                }
                MPVLib.setPropertyDouble("panscan", 1.0)
                binding.btnAspectRatio.text = getString(R.string.aspect_crop)
            }
            2 -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "16:9")
                binding.btnAspectRatio.text = getString(R.string.aspect_16_9)
            }
            3 -> {
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "4:3")
                binding.btnAspectRatio.text = getString(R.string.aspect_4_3)
            }
            4 -> {
                MPVLib.setPropertyString("keepaspect", "no")
                MPVLib.setPropertyDouble("panscan", 0.0)
                val metrics = resources.displayMetrics
                val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
                MPVLib.setPropertyString("video-aspect-override", ratio.toString())
                binding.btnAspectRatio.text = getString(R.string.aspect_fill)
            }
        }
    }

    private fun cycleAspectRatio() {
        aspectIndex = (aspectIndex + 1) % 5
        applyCurrentAspectRatio()
    }

    private fun updateTrackButtonLabels() {
        val videoCodec = MPVLib.getPropertyString("video-codec")?.uppercase() ?: ""
        binding.btnVideoTrack.text = if (containerVideoWidth > 0 && containerVideoHeight > 0) {
            val res = if (containerVideoWidth >= 3800) "4K" else if (containerVideoWidth >= 1900) "1080P" else "${containerVideoHeight}P"
            if (videoCodec.isNotEmpty()) "视轨: $videoCodec $res" else "视轨: $res"
        } else if (videoCodec.isNotEmpty()) {
            "视轨: $videoCodec"
        } else {
            getString(R.string.btn_video_track)
        }

        val audioCodec = MPVLib.getPropertyString("audio-codec")?.uppercase() ?: ""
        val channels = MPVLib.getPropertyInt("audio-params/channel-count") ?: 0
        val chStr = if (channels > 2) "${channels}声道" else if (channels == 2) "立体声" else if (channels == 1) "单声道" else ""
        binding.btnAudioTrack.text = if (audioCodec.isNotEmpty()) {
            if (chStr.isNotEmpty()) "音轨: $audioCodec $chStr" else "音轨: $audioCodec"
        } else {
            getString(R.string.btn_audio_track)
        }
    }

    private fun showSubtitleDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val subTracks = mutableListOf<Pair<Int, String>>()
        subTracks.add(Pair(0, getString(R.string.track_off)))

        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") == "sub") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Sub #$id"
                subTracks.add(Pair(id, if (lang.isNotEmpty()) "$title ($lang)" else title))
            }
        }

        val options = arrayOf(
            "选择字幕轨道",
            "字号增大 (+0.1) [当前: ${String.format("%.1f", currentSubScale)}x]",
            "字号减小 (-0.1) [当前: ${String.format("%.1f", currentSubScale)}x]",
            "字号重置 (标准 1.0x)",
            "字幕上移 (往上) [位置: $currentSubPos]",
            "字幕下移 (往下) [位置: $currentSubPos]",
            "字幕位置重置 (底部 100)",
            "时间轴提前 (-0.5s) [延迟: ${currentSubDelay}s]",
            "时间轴延后 (+0.5s) [延迟: ${currentSubDelay}s]",
            "时间轴重置 (0.0s)"
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_subtitle_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val titles = subTracks.map { it.second }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(R.string.track_subs)
                            .setItems(titles) { _, idx ->
                                val sel = subTracks[idx].first
                                if (sel == 0) MPVLib.setPropertyString("sid", "no")
                                else MPVLib.setPropertyInt("sid", sel)
                            }.show()
                    }
                    1 -> {
                        currentSubScale = (currentSubScale + 0.1).coerceAtMost(3.0)
                        MPVLib.setPropertyDouble("sub-scale", currentSubScale)
                        Toast.makeText(this, "字幕字号: ${String.format("%.1f", currentSubScale)}x", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        currentSubScale = (currentSubScale - 0.1).coerceAtLeast(0.5)
                        MPVLib.setPropertyDouble("sub-scale", currentSubScale)
                        Toast.makeText(this, "字幕字号: ${String.format("%.1f", currentSubScale)}x", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        currentSubScale = 1.0
                        MPVLib.setPropertyDouble("sub-scale", 1.0)
                        Toast.makeText(this, "字幕字号已重置为 1.0x", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        currentSubPos = (currentSubPos - 5).coerceAtLeast(0)
                        MPVLib.setPropertyInt("sub-pos", currentSubPos)
                        Toast.makeText(this, "字幕位置: $currentSubPos (顶部0~底部100)", Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        currentSubPos = (currentSubPos + 5).coerceAtMost(100)
                        MPVLib.setPropertyInt("sub-pos", currentSubPos)
                        Toast.makeText(this, "字幕位置: $currentSubPos (顶部0~底部100)", Toast.LENGTH_SHORT).show()
                    }
                    6 -> {
                        currentSubPos = 100
                        MPVLib.setPropertyInt("sub-pos", 100)
                        Toast.makeText(this, "字幕位置已重置 (底部100)", Toast.LENGTH_SHORT).show()
                    }
                    7 -> {
                        currentSubDelay -= 0.5
                        MPVLib.setPropertyDouble("sub-delay", currentSubDelay)
                        Toast.makeText(this, "字幕时间轴: ${currentSubDelay}s", Toast.LENGTH_SHORT).show()
                    }
                    8 -> {
                        currentSubDelay += 0.5
                        MPVLib.setPropertyDouble("sub-delay", currentSubDelay)
                        Toast.makeText(this, "字幕时间轴: ${currentSubDelay}s", Toast.LENGTH_SHORT).show()
                    }
                    9 -> {
                        currentSubDelay = 0.0
                        MPVLib.setPropertyDouble("sub-delay", 0.0)
                        Toast.makeText(this, "字幕时间轴已重置", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun showAudioTrackDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val audioTracks = mutableListOf<Pair<Int, String>>()
        audioTracks.add(Pair(0, getString(R.string.track_off)))

        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") == "audio") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Audio #$id"
                val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
                val codec = MPVLib.getPropertyString("track-list/$i/codec")?.uppercase() ?: ""
                val channels = MPVLib.getPropertyInt("track-list/$i/demux-channel-count") ?: 0
                val chStr = if (channels > 2) "${channels}声道" else if (channels == 2) "立体声" else if (channels == 1) "单声道" else ""

                val spec = listOf(codec, chStr, lang).filter { it.isNotEmpty() }.joinToString(" ")
                val label = if (spec.isNotEmpty()) "$title [$spec]" else title
                audioTracks.add(Pair(id, label))
            }
        }

        val titles = audioTracks.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_audio_track)
            .setItems(titles) { _, which ->
                val trackId = audioTracks[which].first
                if (trackId == 0) MPVLib.setPropertyString("aid", "no")
                else MPVLib.setPropertyInt("aid", trackId)
                handler.postDelayed({ updateTrackButtonLabels() }, 500)
            }.show()
    }

    private fun showVideoTrackDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val videoTracks = mutableListOf<Pair<Int, String>>()

        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") == "video") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Video #$id"
                val codec = MPVLib.getPropertyString("track-list/$i/codec")?.uppercase() ?: ""
                val w = MPVLib.getPropertyInt("track-list/$i/demux-w") ?: 0
                val h = MPVLib.getPropertyInt("track-list/$i/demux-h") ?: 0
                val fps = MPVLib.getPropertyDouble("track-list/$i/demux-fps") ?: 0.0
                val fpsStr = if (fps > 0) "${fps.roundToInt()}fps" else ""
                val resStr = if (w > 0 && h > 0) "${w}x${h}" else ""

                val spec = listOf(codec, resStr, fpsStr).filter { it.isNotEmpty() }.joinToString(" ")
                val label = if (spec.isNotEmpty()) "$title [$spec]" else title
                videoTracks.add(Pair(id, label))
            }
        }

        if (videoTracks.isEmpty()) {
            Toast.makeText(this, "仅有单一视轨", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = videoTracks.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_video_track)
            .setItems(titles) { _, which ->
                MPVLib.setPropertyInt("vid", videoTracks[which].first)
                handler.postDelayed({ updateTrackButtonLabels() }, 500)
            }.show()
    }

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> {
                val sec = value.toInt()
                currentPos = sec
                runOnUiThread {
                    binding.timeCurrent.text = formatTime(sec)
                    if (duration > 0 && !userIsOperatingSeekbar) {
                        val progress = ((sec.toFloat() / duration) * 1000).toInt()
                        binding.playbackSeekbar.progress = progress
                    }

                    if (!introSkipped && skipIntroSec > 0) {
                        if (sec < skipIntroSec) {
                            MPVLib.setPropertyDouble("time-pos", skipIntroSec.toDouble())
                            Toast.makeText(this@MPVActivity, R.string.toast_intro_skipped, Toast.LENGTH_SHORT).show()
                        }
                        introSkipped = true
                    }

                    if (skipOutroSec > 10) {
                        if (sec < skipOutroSec) {
                            hasPlayedBeforeOutro = true
                        } else if (hasPlayedBeforeOutro && sec >= skipOutroSec) {
                            quitAndReturnResult(isCompleted = true)
                        }
                    }
                }
            }
            "duration" -> {
                duration = value.toInt()
                runOnUiThread {
                    binding.timeDuration.text = formatTime(duration)
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            isPlaying = !value
            runOnUiThread { updatePlayBtnState(!value) }
        }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos" || property == "duration") {
            eventProperty(property, value.toLong())
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                writeAppDebugLog("MPV_EVENT_FILE_LOADED: Video loaded successfully!")
                MPVLib.observeProperty("time-pos", MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("duration", MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)

                val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                for (i in 0 until count) {
                    if (MPVLib.getPropertyString("track-list/$i/type") == "video") {
                        val dw = MPVLib.getPropertyInt("track-list/$i/demux-w") ?: 0
                        val dh = MPVLib.getPropertyInt("track-list/$i/demux-h") ?: 0
                        if (dw > 0 && dh > 0) {
                            containerVideoWidth = dw
                            containerVideoHeight = dh
                            break
                        }
                    }
                }
                runOnUiThread {
                    applyCurrentAspectRatio()
                    updateTrackButtonLabels()
                }
            }
            MpvEvent.MPV_EVENT_END_FILE -> {
                writeAppDebugLog("MPV_EVENT_END_FILE: Playback stopped or failed to open stream.")
                runOnUiThread { quitAndReturnResult(isCompleted = true) }
            }
        }
    }

    private fun updatePlayBtnState(playing: Boolean) {
        binding.playBtn.text = if (playing) getString(R.string.btn_pause) else getString(R.string.btn_play)
    }

    private fun showControls() {
        hideSystemBars()
        binding.topControls.visibility = View.VISIBLE
        binding.controls.visibility = View.VISIBLE
        binding.playBtn.requestFocus()
        resetHideControlsTimer()
    }

    private fun hideControls() {
        binding.topControls.visibility = View.GONE
        binding.controls.visibility = View.GONE
        hideSystemBars()
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 6000)
    }

    private var seekToast: Toast? = null
    private fun showSeekToast(msg: String) {
        seekToast?.cancel()
        seekToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(android.view.Gravity.CENTER, 0, 0)
            show()
        }
    }

    private var lastSeekDispatchTime = 0L
    private var totalSeekDelta = 0

    private fun handleDpadSeek(isForward: Boolean, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        val dir = if (isForward) 1 else -1

        if (event.repeatCount == 0) {
            totalSeekDelta = dir * 10
            MPVLib.command(arrayOf("seek", totalSeekDelta.toString(), "relative"))
            val targetPos = (currentPos + totalSeekDelta).coerceIn(0, duration)
            val sign = if (totalSeekDelta > 0) "+" else ""
            showSeekToast("${sign}${totalSeekDelta}秒 (${formatTime(targetPos)})")
            lastSeekDispatchTime = now
            return true
        }

        if (now - lastSeekDispatchTime >= 200L) {
            val step = when {
                event.repeatCount > 20 -> 60
                event.repeatCount > 8  -> 30
                else                   -> 15
            } * dir

            totalSeekDelta += step
            MPVLib.command(arrayOf("seek", step.toString(), "relative"))
            val targetPos = (currentPos + totalSeekDelta).coerceIn(0, duration)
            val sign = if (totalSeekDelta > 0) "+" else ""
            showSeekToast("${if (isForward) "快进" else "快退"} >> ${sign}${totalSeekDelta}秒 (${formatTime(targetPos)})")
            lastSeekDispatchTime = now
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isControlsShowing = (binding.controls.visibility == View.VISIBLE)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!isControlsShowing) {
                        togglePlayPause()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (isControlsShowing) hideControls() else showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isControlsShowing) {
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isControlsShowing) {
                        return handleDpadSeek(isForward = false, event = event)
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isControlsShowing) {
                        return handleDpadSeek(isForward = true, event = event)
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (isControlsShowing) {
                        hideControls()
                        return true
                    } else {
                        quitAndReturnResult(isCompleted = false)
                        return true
                    }
                }
            }
            if (isControlsShowing) {
                resetHideControlsTimer()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (binding.controls.visibility == View.VISIBLE) {
            hideControls()
        } else {
            quitAndReturnResult(isCompleted = false)
        }
    }

    private fun quitAndReturnResult(isCompleted: Boolean) {
        val completed = isCompleted || (duration > 0 && currentPos >= (duration - 15))
        val resultIntent = Intent().apply {
            data = Uri.parse(currentFilePath)
            putExtra("position", currentPos * 1000)
            putExtra("duration", duration * 1000)
            putExtra("end_by", if (completed) "playback_completion" else "user")
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun formatTime(sec: Int): String {
        val positiveSec = if (sec < 0) 0 else sec
        val h = positiveSec / 3600
        val m = (positiveSec % 3600) / 60
        val s = positiveSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        writeAppDebugLog("=== MPVActivity Destroyed ===")
        handler.removeCallbacksAndMessages(null)
        MPVLib.removeObserver(this)
        binding.player.destroy()
        super.onDestroy()
    }
}
