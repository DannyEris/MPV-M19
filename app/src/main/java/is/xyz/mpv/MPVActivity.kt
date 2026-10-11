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

    // 封装容器解析出的真实物理视频宽高
    private var containerVideoWidth = 0
    private var containerVideoHeight = 0

    // 屏幕比例模式:
    // 0: 默认原始 (保持原片固有宽高比适应屏幕，带黑边不拉伸)
    // 1: 裁切填充 (保持原片宽高比放大直至满屏，切除黑边不拉伸)
    // 2: 16:9 比例
    // 3: 4:3 比例
    // 4: 拉伸全屏 (忽略比例铺满视口)
    private var aspectIndex = 0

    // 解码模式: 0: HW (mediacodec-copy，S912专属), 1: HW+ (mediacodec), 2: SW (no)
    private var decoderIndex = 0

    // HDR 模式: 0: 自动(Mobius算法，通透不灰白), 1: 鲜艳(Hable胶片算法), 2: 关闭(Clip截断)
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

    private fun initPlayerCore() {
        val configDir = applicationContext.filesDir.path
        val cacheDir = applicationContext.cacheDir.path

        // 由 MPVView 驱动注册 SurfaceView 回调
        binding.player.initialize(configDir, cacheDir)
        MPVLib.addObserver(this)

        // 1. 正确生效 profile=fast (通过底层指令接口调用，属性设置会被忽略)
        MPVLib.command(arrayOf("apply-profile", "fast"))

        // 2. 硬件解码配置：天猫魔盒 M19 (S912/Android 5.1) 核心使用 mediacodec-copy
        MPVLib.setPropertyString("hwdec", "mediacodec-copy")
        MPVLib.setPropertyString("hwdec-codecs", "all")
        MPVLib.setPropertyInt("hwdec-extra-frames", 16) // 扩大帧缓冲池至 16 帧，杜绝 60fps 帧饥饿
        MPVLib.setPropertyString("ao", "audiotrack")
        MPVLib.setPropertyDouble("audio-buffer", 0.2)   // 200ms 音频缓冲，防止欠载音画不同步

        // 3. 比例保持机制：严格禁止 gpu-dumb-mode，初始化锁定原片比例
        MPVLib.setPropertyString("keepaspect", "yes")
        MPVLib.setPropertyDouble("panscan", 0.0)

        // 4. 4K 60fps 基础极速渲染管线（兼顾 Mali-T820 低带宽与平滑丢帧保护）
        MPVLib.setPropertyString("scale", "bilinear")
        MPVLib.setPropertyString("cscale", "bilinear")
        MPVLib.setPropertyString("dscale", "bilinear")
        MPVLib.setPropertyString("fbo-format", "rgba8") // 限制 FBO 为 8 位带宽，节省显存带宽
        MPVLib.setPropertyString("dither-depth", "no")
        MPVLib.setPropertyString("correct-downscaling", "no")
        MPVLib.setPropertyString("sigmoid-upscaling", "no")
        MPVLib.setPropertyString("video-sync", "audio")
        MPVLib.setPropertyString("framedrop", "vo")      // 允许渲染层平滑丢弃过期帧，保持 60fps 稳定
        MPVLib.setPropertyString("vd-lavc-threads", "8")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "nonref") // 跳过非参考帧滤波，解码效率成倍提升

        // 5. 核心修复：锁定电视标准色域 (BT.709) 与电视标准伽马 (Gamma 2.2)，彻底消除白雾发灰
        MPVLib.setPropertyString("target-prim", "bt.709")
        MPVLib.setPropertyString("target-trc", "gamma2.2")
        MPVLib.setPropertyString("hdr-compute-peak", "no")

        // 6. 深度内存缓存机制 (RAM Cache)：彻底解决网盘 4K 60fps 码率波动卡顿
        MPVLib.setPropertyString("cache", "yes")
        MPVLib.setPropertyString("cache-secs", "120")
        MPVLib.setPropertyString("demuxer-max-bytes", "${256 * 1024 * 1024}") // 256MB 快速内存缓冲区
        MPVLib.setPropertyString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        MPVLib.setPropertyString("demuxer-readahead-secs", "120")
        MPVLib.setPropertyString("demuxer-hysteresis-secs", "10")
        MPVLib.setPropertyString("demuxer-thread", "yes")          // 独立解复用线程，网络 I/O 绝不卡死渲染
        MPVLib.setPropertyString("stream-buffer-size", "4194304")
        MPVLib.setPropertyString("network-timeout", "30")

        // 启用 OpenGL 异步像素缓冲对象 (PBO)，减轻 CPU 搬运数据时的内存等待
        MPVLib.setPropertyString("opengl-pbo", "yes")

        // 7. 协议层自动重连与证书校验忽略
        val caFile = File(configDir, "cacert.pem")
        if (caFile.exists()) {
            MPVLib.setPropertyString("tls-ca-file", caFile.absolutePath)
        }
        MPVLib.setPropertyString("tls-verify", "no")
        MPVLib.setPropertyString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5,tls_verify=0")

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
        containerVideoWidth = 0
        containerVideoHeight = 0

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

        // 伪装浏览器标识与百度直链防护
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

        // 断点续播
        val startPositionMs = intent.getIntExtra("position", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("position", 0L)
        if (startPositionMs > 0L) {
            val startSec = (startPositionMs / 1000).toInt()
            MPVLib.setPropertyString("start", startSec.toString())
        } else {
            MPVLib.setPropertyString("start", "0")
        }

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

        // SeekBar 进度条遥控器与触摸响应
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

    // 精准 HDR -> SDR 色彩空间重映射函数
    private fun applyHdrMode(mode: Int, updateText: Boolean) {
        when (mode) {
            0 -> {
                // 推荐模式：莫比乌斯算法 (运算开销极低，色彩通透不发白，对比度扎实)
                MPVLib.setPropertyString("tone-mapping", "mobius")
                MPVLib.setPropertyString("target-prim", "bt.709")
                MPVLib.setPropertyString("target-trc", "gamma2.2")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}:${getString(R.string.hdr_auto)}"
            }
            1 -> {
                // 鲜艳/胶片模式：Hable 算法 (好莱坞胶片级对比曲线，暗部更深沉，色彩浓郁)
                MPVLib.setPropertyString("tone-mapping", "hable")
                MPVLib.setPropertyString("target-prim", "bt.709")
                MPVLib.setPropertyString("target-trc", "gamma2.2")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: 鲜艳"
            }
            2 -> {
                // 关闭模式：纯截断 (原样输出)
                MPVLib.setPropertyString("tone-mapping", "clip")
                MPVLib.setPropertyString("target-prim", "auto")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}:${getString(R.string.hdr_off)}"
            }
        }
    }

    // 核心比例计算引擎：从封装容器层直接锁定真实物理比例
    private fun applyCurrentAspectRatio() {
        when (aspectIndex) {
            0 -> {
                // 1. 默认原始模式：维持影片固有宽高比适应屏幕，绝不拉伸
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
                // 2. 裁切填充模式：维持原片比例放大直至铺满 16:9，切除黑边，人物不拉伸
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
                // 3. 16:9 比例
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "16:9")
                binding.btnAspectRatio.text = getString(R.string.aspect_16_9)
            }
            3 -> {
                // 4. 4:3 比例
                MPVLib.setPropertyString("keepaspect", "yes")
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "4:3")
                binding.btnAspectRatio.text = getString(R.string.aspect_4_3)
            }
            4 -> {
                // 5. 拉伸全屏：强行拉满视口
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
        if (eventId == MpvEvent.MPV_EVENT_FILE_LOADED) {
            MPVLib.observeProperty("time-pos", MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("duration", MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)

            // 从解复用器直接抓取视频封装层的真实物理像素宽高
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
            runOnUiThread { applyCurrentAspectRatio() }
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

    // 左右方向键轻量无打扰 Toast 提示
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

    private fun handleDpadSeek(isForward: Boolean, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        val dir = if (isForward) 1 else -1

        if (event.repeatCount == 0) {
            // 单击点按：快进/快退 10 秒
            totalSeekDelta = dir * 10
            MPVLib.command(arrayOf("seek", totalSeekDelta.toString(), "relative"))
            val targetPos = (currentPos + totalSeekDelta).coerceIn(0, duration)
            val sign = if (totalSeekDelta > 0) "+" else ""
            showSeekToast("${sign}${totalSeekDelta}秒 (${formatTime(targetPos)})")
            lastSeekDispatchTime = now
            return true
        }

        // 长按模式：200ms 防抖节流，按住不放阶梯平滑加速
        if (now - lastSeekDispatchTime >= 200L) {
            val step = when {
                event.repeatCount > 20 -> 60 // 超过约2秒，步进 60 秒
                event.repeatCount > 8  -> 30 // 超过约1秒，步进 30 秒
                else                   -> 15 // 初始长按，步进 15 秒
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
                // 3. 左右方向键：短按 10 秒 / 长按平滑加速，绝不弹出面板
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
                // 遥控器独立多媒体键
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
