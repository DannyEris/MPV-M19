package `is`.xyz.mpv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
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
import java.lang.StringBuilder
import java.util.TreeMap
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

    // 屏幕比例: 0: 默认比例, 1: 16:9, 2: 4:3, 3: 裁切填充(无黑边), 4: 拉伸全屏
    private var aspectIndex = 0

    // 解码模式: 0: HW+ (mediacodec), 1: HW (mediacodec-copy), 2: SW (no)
    private var decoderIndex = 0

    // HDR to SDR: 0: 自动, 1: 开启, 2: 关闭
    private var hdrMode = 0

    // 片头片尾设置 (秒)
    private var skipIntroSec: Int = 0
    private var skipOutroSec: Int = 0
    private var introSkipped = false
    private var hasPlayedBeforeOutro = false

    // 进度条遥控操作防竞争标记与防抖
    private var userIsOperatingSeekbar = false
    private val seekbarCommitRunnable = Runnable {
        if (duration > 0) {
            val targetSec = (binding.playbackSeekbar.progress.toFloat() / 1000f * duration)
            MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute+keyframes"))
        }
        userIsOperatingSeekbar = false
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

    private fun initPlayerCore() {
        val configDir = applicationContext.filesDir.path
        val cacheDir = applicationContext.cacheDir.path

        // 由 MPVView 驱动注册 Surface 回调 (原生 SurfaceView)
        binding.player.initialize(configDir, cacheDir)
        MPVLib.addObserver(this)

        // 1. 硬件解码配置：必须传入单一有效值 "mediacodec"，严禁传复合字符串避免回退软解
        MPVLib.setPropertyString("hwdec", "mediacodec")
        MPVLib.setPropertyString("hwdec-codecs", "all")
        MPVLib.setPropertyString("ao", "audiotrack")

        // 2. 严禁使用 gpu-dumb-mode！恢复官方原生画面纵横比计算
        MPVLib.setPropertyString("profile", "fast")
        MPVLib.setPropertyString("keepaspect", "yes")
        MPVLib.setPropertyString("video-aspect-override", "no")
        MPVLib.setPropertyDouble("panscan", 0.0)

        // 3. 调优 4K 60fps 渲染管线，降低 Mali-T820 负载
        MPVLib.setPropertyString("scale", "bilinear")
        MPVLib.setPropertyString("cscale", "bilinear")
        MPVLib.setPropertyString("dscale", "bilinear")
        MPVLib.setPropertyString("fbo-format", "rgba8")
        MPVLib.setPropertyString("dither-depth", "no")
        MPVLib.setPropertyString("correct-downscaling", "no")
        MPVLib.setPropertyString("sigmoid-upscaling", "no")
        MPVLib.setPropertyString("hdr-compute-peak", "no")
        MPVLib.setPropertyString("tone-mapping", "reinhard")
        MPVLib.setPropertyString("video-sync", "audio")
        MPVLib.setPropertyString("framedrop", "vo")
        MPVLib.setPropertyString("vd-lavc-threads", "8")

        // 4. 网络流高码率缓冲与连接超时设置
        MPVLib.setPropertyString("demuxer-max-bytes", "${128 * 1024 * 1024}")
        MPVLib.setPropertyString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        MPVLib.setPropertyString("demuxer-readahead-secs", "30")
        MPVLib.setPropertyString("network-timeout", "30")

        // 5. 解决 Android 5.1 TLS 证书不信任问题
        val caFile = File(configDir, "cacert.pem")
        if (caFile.exists()) {
            MPVLib.setPropertyString("tls-ca-file", caFile.absolutePath)
        }
        MPVLib.setPropertyString("tls-verify", "no")

        // 初始化 HDR 状态
        hdrMode = sharedPrefs.getInt("hdr_to_sdr_mode", 0)
        applyHdrMode(hdrMode, updateText = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = intent.data
        var path = uri?.toString()
        if (path.isNullOrEmpty()) {
            path = intent.getStringExtra("url") ?: intent.getStringExtra("filepath")
        }

        if (path.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_no_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentFilePath = path
        introSkipped = false
        hasPlayedBeforeOutro = false

        var title = intent.getStringExtra("title")
        if (title.isNullOrEmpty()) {
            title = uri?.lastPathSegment ?: "Network Stream"
        }
        binding.titleTextView.text = title

        val cleanPath = if (uri != null && uri.scheme != null) {
            "${uri.scheme}://${uri.host ?: ""}${uri.path ?: ""}"
        } else {
            path
        }
        videoMemoryKey = if (!title.isNullOrEmpty()) title else cleanPath

        skipIntroSec = sharedPrefs.getInt("intro_${videoMemoryKey.hashCode()}", 0)
        skipOutroSec = sharedPrefs.getInt("outro_${videoMemoryKey.hashCode()}", 0)
        updateIntroOutroButtonsText()

        // 提取 TVBox 请求头
        val headersMap = TreeMap<String, String>(java.lang.String.CASE_INSENSITIVE_ORDER)

        val httpHeadersBundle = intent.getBundleExtra("headers")
            ?: intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
        httpHeadersBundle?.let { bundle ->
            for (k in bundle.keySet()) {
                val v = bundle.getString(k) ?: bundle.get(k)?.toString() ?: ""
                if (k.isNotEmpty() && v.isNotEmpty()) headersMap[k] = v
            }
        }

        val headersArray = intent.getStringArrayExtra("headers")
        if (headersArray != null && headersArray.size >= 2) {
            for (i in 0 until headersArray.size step 2) {
                val k = headersArray[i]
                val v = headersArray.getOrNull(i + 1) ?: ""
                if (k.isNotEmpty() && v.isNotEmpty()) headersMap[k] = v
            }
        }

        try {
            val serializableMap = intent.getSerializableExtra("headers") as? Map<*, *>
            serializableMap?.forEach { (k, v) ->
                val key = k?.toString() ?: ""
                val value = v?.toString() ?: ""
                if (key.isNotEmpty() && value.isNotEmpty()) headersMap[key] = value
            }
        } catch (e: Exception) {
            // Ignore format mismatch
        }

        val headersJsonStr = intent.getStringExtra("headers")
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

        // 针对百度网盘做专属客户端伪装，其他站源默认伪装为 Chrome 浏览器防拦截
        val isBaidu = path.contains("baidupcs.com", ignoreCase = true) || path.contains("pcs.baidu.com", ignoreCase = true)
        val defaultBrowserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        var finalUa = headersMap["User-Agent"]
        if (isBaidu) {
            finalUa = "pan.baidu.com"
        } else if (finalUa.isNullOrEmpty()) {
            finalUa = defaultBrowserUa
        }
        MPVLib.setPropertyString("user-agent", finalUa)

        val customRef = headersMap["Referer"]
        if (!customRef.isNullOrEmpty()) {
            MPVLib.setPropertyString("referrer", customRef)
        } else if (isBaidu) {
            MPVLib.setPropertyString("referrer", "https://pan.baidu.com/")
        }

        val mpvHeaderList = mutableListOf<String>()
        for ((k, v) in headersMap) {
            if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Referer", ignoreCase = true)) {
                val safeVal = v.replace(",", "\\,")
                mpvHeaderList.add("$k:$safeVal")
            }
        }
        if (mpvHeaderList.isNotEmpty()) {
            MPVLib.setPropertyString("http-header-fields", mpvHeaderList.joinToString(","))
        }

        // 断点续播时间 (毫秒)
        val startPositionMs = intent.getIntExtra("position", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("position", 0L)
        if (startPositionMs > 0L) {
            val startSec = (startPositionMs / 1000).toInt()
            MPVLib.setPropertyString("start", startSec.toString())
        } else {
            MPVLib.setPropertyString("start", "0")
        }

        // 启动播放
        MPVLib.command(arrayOf("loadfile", currentFilePath))
        hideControls()
    }

    private fun setupUIControls() {
        binding.playBtn.setOnClickListener {
            togglePlayPause()
            resetHideControlsTimer()
        }

        // 解码器切换快捷按钮 (HW+ / HW / SW)
        binding.btnDecoder.setOnClickListener {
            cycleDecoder()
            resetHideControlsTimer()
        }

        // 进度条事件：兼容遥控器与触屏拖动
        binding.playbackSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    userIsOperatingSeekbar = true
                    val targetSec = (progress.toFloat() / 1000f * duration).roundToInt()
                    binding.timeCurrent.text = formatTime(targetSec)
                    
                    // 遥控器方向键调节时进行防抖异步提交，解决遥控器拖动不生效的问题
                    handler.removeCallbacks(seekbarCommitRunnable)
                    handler.postDelayed(seekbarCommitRunnable, 250)
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
                    val targetSec = progress.toFloat() / 1000f * duration
                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute+keyframes"))
                }
                userIsOperatingSeekbar = false
                resetHideControlsTimer()
            }
        })

        binding.btnHdrMode.setOnClickListener {
            hdrMode = (hdrMode + 1) % 3
            sharedPrefs.edit().putInt("hdr_to_sdr_mode", hdrMode).apply()
            applyHdrMode(hdrMode, updateText = true)
            resetHideControlsTimer()
        }

        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
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
                MPVLib.setPropertyString("hwdec", "mediacodec")
                binding.btnDecoder.text = "解码: HW+"
                Toast.makeText(this, "已切换为: 硬件直出 (HW+)", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                MPVLib.setPropertyString("hwdec", "mediacodec-copy")
                binding.btnDecoder.text = "解码: HW"
                Toast.makeText(this, "已切换为: 硬件拷贝 (HW)", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                MPVLib.setPropertyString("hwdec", "no")
                binding.btnDecoder.text = "解码: SW"
                Toast.makeText(this, "已切换为: CPU软解 (SW)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun togglePlayPause() {
        val paused = MPVLib.getPropertyBoolean("pause") ?: false
        MPVLib.setPropertyBoolean("pause", !paused)
        updatePlayBtnState(!paused)
    }

    private fun updateIntroOutroButtonsText() {
        binding.btnIntro.text = if (skipIntroSec > 0) {
            "${getString(R.string.btn_skip_intro)}:${formatTime(skipIntroSec)}"
        } else {
            getString(R.string.btn_skip_intro)
        }

        binding.btnOutro.text = if (skipOutroSec > 0) {
            "${getString(R.string.btn_skip_outro)}:${formatTime(skipOutroSec)}"
        } else {
            getString(R.string.btn_skip_outro)
        }
    }

    private fun applyHdrMode(mode: Int, updateText: Boolean) {
        when (mode) {
            0 -> {
                MPVLib.setPropertyString("tone-mapping", "reinhard")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}:${getString(R.string.hdr_auto)}"
            }
            1 -> {
                MPVLib.setPropertyString("tone-mapping", "mobius")
                MPVLib.setPropertyString("target-trc", "srgb")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}:${getString(R.string.hdr_on)}"
            }
            2 -> {
                MPVLib.setPropertyString("tone-mapping", "clip")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}:${getString(R.string.hdr_off)}"
            }
        }
    }

    private fun cycleAspectRatio() {
        aspectIndex = (aspectIndex + 1) % 5
        when (aspectIndex) {
            0 -> {
                // 默认比例：严格遵照原片宽高比，居中显示，两侧黑边填充，绝不拉伸
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "no")
                binding.btnAspectRatio.text = getString(R.string.aspect_default)
            }
            1 -> {
                // 16:9
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "16:9")
                binding.btnAspectRatio.text = getString(R.string.aspect_16_9)
            }
            2 -> {
                // 4:3
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "4:3")
                binding.btnAspectRatio.text = getString(R.string.aspect_4_3)
            }
            3 -> {
                // 裁切填充：等比放大直到占满整个屏幕，切掉黑边，不破坏人物纵横比
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyString("video-aspect-override", "no")
                MPVLib.setPropertyDouble("panscan", 1.0)
                binding.btnAspectRatio.text = getString(R.string.aspect_crop)
            }
            4 -> {
                // 拉伸全屏：强行铺满视口像素
                val metrics = resources.displayMetrics
                val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", ratio.toString())
                binding.btnAspectRatio.text = getString(R.string.aspect_fill)
            }
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

        val options = arrayOf("选择字幕轨道", "字幕大小调节", "字幕垂直位置", "字幕时间轴微调")
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
                        val sizes = arrayOf("小 (0.8x)", "标准 (1.0x)", "较大 (1.2x)", "特大 (1.5x)", "超大 (1.8x)")
                        val scaleVals = arrayOf(0.8, 1.0, 1.2, 1.5, 1.8)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sub_font_size)
                            .setItems(sizes) { _, sIdx ->
                                MPVLib.setPropertyDouble("sub-scale", scaleVals[sIdx])
                            }.show()
                    }
                    2 -> {
                        val positions = arrayOf("底部 (100)", "偏底 (90)", "偏中 (80)", "中间 (50)", "顶部 (0)")
                        val posVals = arrayOf(100, 90, 80, 50, 0)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sub_vertical_position)
                            .setItems(positions) { _, pIdx ->
                                MPVLib.setPropertyInt("sub-pos", posVals[pIdx])
                            }.show()
                    }
                    3 -> {
                        val delays = arrayOf("-2.0s", "-1.0s", "-0.5s", "0s (标准)", "+0.5s", "+1.0s", "+2.0s")
                        val delayVals = arrayOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sub_delay)
                            .setItems(delays) { _, dIdx ->
                                MPVLib.setPropertyDouble("sub-delay", delayVals[dIdx])
                            }.show()
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
                audioTracks.add(Pair(id, if (lang.isNotEmpty()) "$title ($lang)" else title))
            }
        }

        val titles = audioTracks.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_audio_track)
            .setItems(titles) { _, which ->
                val trackId = audioTracks[which].first
                if (trackId == 0) MPVLib.setPropertyString("aid", "no")
                else MPVLib.setPropertyInt("aid", trackId)
            }.show()
    }

    private fun showVideoTrackDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val videoTracks = mutableListOf<Pair<Int, String>>()

        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") == "video") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Video #$id"
                val w = MPVLib.getPropertyInt("track-list/$i/demux-w") ?: 0
                val h = MPVLib.getPropertyInt("track-list/$i/demux-h") ?: 0
                val res = if (w > 0 && h > 0) " (${w}x${h})" else ""
                videoTracks.add(Pair(id, "$title$res"))
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
                            MPVLib.command(arrayOf("seek", skipIntroSec.toString(), "absolute"))
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
        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            MPVLib.observeProperty("time-pos", MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("duration", MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        } else if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            runOnUiThread { quitAndReturnResult(isCompleted = true) }
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

    // 左右键独立轻量 Toast 提示
    private var seekToast: Toast? = null
    private fun showSeekToast(msg: String) {
        seekToast?.cancel()
        seekToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(android.view.Gravity.CENTER, 0, 0)
            show()
        }
    }

    // 左右键长按平滑加速控制参数
    private var lastSeekDispatchTime = 0L
    private var totalSeekDelta = 0
    private var isLongPressSeeking = false

    private fun handleDpadSeek(isForward: Boolean, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        val dir = if (isForward) 1 else -1

        if (event.repeatCount == 0) {
            // 单击点按：固定快退/快进 10 秒
            isLongPressSeeking = false
            totalSeekDelta = dir * 10
            MPVLib.command(arrayOf("seek", (dir * 10).toString(), "relative"))
            val targetPos = (currentPos + totalSeekDelta).coerceIn(0, duration)
            val sign = if (dir > 0) "+" else ""
            showSeekToast("${sign}${totalSeekDelta}秒 (${formatTime(targetPos)})")
            lastSeekDispatchTime = now
            return true
        }

        // 长按模式：节流 250ms 防止指令过多打爆解码器，并随着时长智能递增步长
        isLongPressSeeking = true
        if (now - lastSeekDispatchTime >= 250L) {
            val step = when {
                event.repeatCount > 20 -> 60 // 超过约2秒，每跳1分钟
                event.repeatCount > 8  -> 30 // 超过约1秒，每跳30秒
                else                   -> 15 // 初始长按，每跳15秒
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
                // 1. OK 键：隐藏时只控制播放/暂停，不呼出面板
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!isControlsShowing) {
                        togglePlayPause()
                        return true
                    }
                }
                // 2. 菜单键与上下方向键：专门用于呼出控制面板
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
                // 3. 左右方向键：隐藏时执行带长按加速的平滑快退/快进，不弹出面板
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
                // 遥控器独立多媒体按键
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
        handler.removeCallbacksAndMessages(null)
        MPVLib.removeObserver(this)
        binding.player.destroy()
        super.onDestroy()
    }
}
