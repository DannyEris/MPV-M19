package is.xyz.mpv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import is.xyz.mpv.databinding.PlayerBinding
import java.io.Serializable
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

    // 屏幕比例索引: 0: 默认, 1: 16:9, 2: 4:3, 3: 填充, 4: 原始, 5: 裁剪
    private var aspectIndex = 0

    // HDR to SDR: 0: 自动, 1: 开启, 2: 关闭
    private var hdrMode = 0

    // 片头片尾设置 (毫秒)
    private var skipIntroMs: Long = 0L
    private var skipOutroMs: Long = 0L
    private var introSkipped = false

    // UI 自动隐藏计时
    private val hideControlsRunnable = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        initPlayerCore()
        setupUIControls()
        handleIntent(intent)
    }

    private fun initPlayerCore() {
        val configDir = applicationContext.filesDir.path
        MPVLib.create(this)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)

        // 针对电视盒子（天猫魔盒 S912）的硬解与缓存增强配置
        val hwdec = if (sharedPrefs.getBoolean("hardware_decoding", true)) "mediacodec-copy" else "no"
        MPVLib.setOptionString("hwdec", hwdec)
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")

        // 优化 4K 网盘及 m3u8 高码率流媒体缓冲能力
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        MPVLib.setOptionString("network-timeout", "20")
        MPVLib.setOptionString("tls-verify", "no")

        // 初始化 HDR to SDR
        hdrMode = sharedPrefs.getInt("hdr_to_sdr_mode", 0)
        applyHdrMode(hdrMode, updateText = false)

        MPVLib.init()
        MPVLib.addObserver(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    /**
     * 全面解析 TVBox / FongMi / MXPlayer 协议规范传入的数据与 HTTP Headers
     */
    private fun handleIntent(intent: Intent) {
        val uri: Uri? = intent.data
        if (uri == null) {
            Toast.makeText(this, R.string.error_no_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentFilePath = uri.toString()
        introSkipped = false

        // 从 SharedPreferences 读取已持久化的片头片尾时间
        val introKey = "intro_${currentFilePath.hashCode()}"
        val outroKey = "outro_${currentFilePath.hashCode()}"
        skipIntroMs = sharedPrefs.getLong(introKey, 0L)
        skipOutroMs = sharedPrefs.getLong(outroKey, 0L)

        // 解析显示标题
        var title = intent.getStringExtra("title")
        if (title.isNullOrEmpty()) {
            title = uri.lastPathSegment ?: "Network Stream"
        }
        binding.titleTextView.text = title

        // 提取 TVBox 各种形式发送的 Headers
        val headerList = mutableListOf<String>()
        var customUserAgent: String? = null

        // 1. 数组形态 (MX Player 规范: ["Key1", "Val1", "Key2", "Val2"...])
        val headersArray = intent.getStringArrayExtra("headers")
        if (headersArray != null && headersArray.size >= 2) {
            for (i in 0 until headersArray.size step 2) {
                val key = headersArray[i]
                val value = headersArray.getOrNull(i + 1) ?: ""
                if (key.equals("User-Agent", ignoreCase = true)) {
                    customUserAgent = value
                } else {
                    headerList.add("$key: $value")
                }
            }
        }

        // 2. Bundle 扩展包形态 (Android 标准 android.media.intent.extra.HTTP_HEADERS)
        val httpHeadersBundle = intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
            ?: intent.getBundleExtra("headers")
        httpHeadersBundle?.let { bundle ->
            for (key in bundle.keySet()) {
                val value = bundle.getString(key) ?: bundle.get(key)?.toString() ?: ""
                if (key.equals("User-Agent", ignoreCase = true)) {
                    customUserAgent = value
                } else {
                    headerList.add("$key: $value")
                }
            }
        }

        // 3. 常见独立 Extra 字段
        val explicitUa = intent.getStringExtra("User-Agent") ?: intent.getStringExtra("user_agent")
        if (!explicitUa.isNullOrEmpty()) {
            customUserAgent = explicitUa
        }

        // 4. 将 Headers 注入 mpv 内核
        if (!customUserAgent.isNullOrEmpty()) {
            MPVLib.setOptionString("user-agent", customUserAgent)
        }
        if (headerList.isNotEmpty()) {
            val combinedHeaders = headerList.joinToString(",")
            MPVLib.setOptionString("http-header-fields", combinedHeaders)
        }

        // 5. 启动播放
        MPVLib.command(arrayOf("loadfile", currentFilePath))
        showControls()
    }

    private fun setupUIControls() {
        // 播放 / 暂停
        binding.playBtn.setOnClickListener {
            val paused = MPVLib.getPropertyBoolean("pause") ?: false
            MPVLib.setPropertyBoolean("pause", !paused)
            updatePlayBtnState(!paused)
            resetHideControlsTimer()
        }

        // 进度条
        binding.playbackSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) {
                    val targetSec = (progress.toFloat() / 1000f * duration).roundToInt()
                    binding.timeCurrent.text = formatTime(targetSec)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (duration > 0) {
                    val targetSec = progress.toFloat() / 1000f * duration
                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute"))
                }
                resetHideControlsTimer()
            }
        })

        // HDR to SDR 切换 (自动 / 强制开启 / 关闭)
        binding.btnHdrMode.setOnClickListener {
            hdrMode = (hdrMode + 1) % 3
            sharedPrefs.edit().putInt("hdr_to_sdr_mode", hdrMode).apply()
            applyHdrMode(hdrMode, updateText = true)
            resetHideControlsTimer()
        }

        // 画面比例切换 (默认 / 16:9 / 4:3 / 填充 / 原始 / 裁剪)
        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
            resetHideControlsTimer()
        }

        // 字幕弹窗 (选择轨道 & 调整字号/位置/延迟)
        binding.btnSubSettings.setOnClickListener {
            showSubtitleDialog()
            resetHideControlsTimer()
        }

        // 音轨切换
        binding.btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
            resetHideControlsTimer()
        }

        // 视轨切换
        binding.btnVideoTrack.setOnClickListener {
            showVideoTrackDialog()
            resetHideControlsTimer()
        }

        // 片头按钮：单击设当前时间为片头，长按重置
        binding.btnIntro.setOnClickListener {
            skipIntroMs = (currentPos * 1000).toLong()
            sharedPrefs.edit().putLong("intro_${currentFilePath.hashCode()}", skipIntroMs).apply()
            Toast.makeText(this, getString(R.string.toast_intro_set, formatTime(currentPos)), Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }
        binding.btnIntro.setOnLongClickListener {
            skipIntroMs = 0L
            sharedPrefs.edit().remove("intro_${currentFilePath.hashCode()}").apply()
            Toast.makeText(this, R.string.toast_intro_reset, Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
            true
        }

        // 片尾按钮：单击设当前时间为片尾，长按重置
        binding.btnOutro.setOnClickListener {
            skipOutroMs = (currentPos * 1000).toLong()
            sharedPrefs.edit().putLong("outro_${currentFilePath.hashCode()}", skipOutroMs).apply()
            Toast.makeText(this, getString(R.string.toast_outro_set, formatTime(currentPos)), Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }
        binding.btnOutro.setOnLongClickListener {
            skipOutroMs = 0L
            sharedPrefs.edit().remove("outro_${currentFilePath.hashCode()}").apply()
            Toast.makeText(this, R.string.toast_outro_reset, Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
            true
        }

        // 播放倍速
        binding.speedBtn.setOnClickListener {
            val speeds = arrayOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            val speedValues = arrayOf(0.75, 1.0, 1.25, 1.5, 2.0)
            AlertDialog.Builder(this)
                .setTitle(R.string.title_speed_dialog)
                .setItems(speeds) { _, which ->
                    val s = speedValues[which]
                    MPVLib.setPropertyDouble("speed", s)
                    binding.speedBtn.text = speeds[which]
                }.show()
            resetHideControlsTimer()
        }

        // 点击屏幕切换控制层显隐
        binding.root.setOnClickListener {
            if (binding.controls.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }
    }

    /**
     * HDR to SDR 映射应用
     */
    private fun applyHdrMode(mode: Int, updateText: Boolean) {
        when (mode) {
            0 -> { // 自动
                MPVLib.setPropertyString("tone-mapping", "auto")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "yes")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: ${getString(R.string.hdr_auto)}"
            }
            1 -> { // 强制开启
                MPVLib.setPropertyString("tone-mapping", "bt.2446a")
                MPVLib.setPropertyString("target-trc", "srgb")
                MPVLib.setPropertyString("hdr-compute-peak", "yes")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: ${getString(R.string.hdr_on)}"
            }
            2 -> { // 关闭
                MPVLib.setPropertyString("tone-mapping", "clip")
                MPVLib.setPropertyString("target-trc", "auto")
                MPVLib.setPropertyString("hdr-compute-peak", "no")
                if (updateText) binding.btnHdrMode.text = "${getString(R.string.hdr_to_sdr)}: ${getString(R.string.hdr_off)}"
            }
        }
    }

    /**
     * 屏幕比例切换实现
     */
    private fun cycleAspectRatio() {
        aspectIndex = (aspectIndex + 1) % 6
        when (aspectIndex) {
            0 -> { // 默认
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "-1")
                binding.btnAspectRatio.text = getString(R.string.aspect_default)
            }
            1 -> { // 16:9
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "16:9")
                binding.btnAspectRatio.text = getString(R.string.aspect_16_9)
            }
            2 -> { // 4:3
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "4:3")
                binding.btnAspectRatio.text = getString(R.string.aspect_4_3)
            }
            3 -> { // 拉伸填充全屏
                val displayMetrics = resources.displayMetrics
                val ratio = displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels.toFloat()
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", ratio.toString())
                binding.btnAspectRatio.text = getString(R.string.aspect_fill)
            }
            4 -> { // 原始比例 1:1
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "0")
                binding.btnAspectRatio.text = getString(R.string.aspect_original)
            }
            5 -> { // 裁剪消除黑边 (Zoom)
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 1.0)
                binding.btnAspectRatio.text = getString(R.string.aspect_crop)
            }
        }
    }

    /**
     * 字幕综合控制面板
     */
    private fun showSubtitleDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val subTracks = mutableListOf<Pair<Int, String>>()
        subTracks.add(Pair(0, getString(R.string.track_off)))

        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type")
            if (type == "sub") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Track $id"
                subTracks.add(Pair(id, if (lang.isNotEmpty()) "$title ($lang)" else title))
            }
        }

        val options = arrayOf("切换字幕轨道", "调整字幕字体大小", "调整字幕垂直位置", "调整字幕延迟")
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_subtitle_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // 切换轨道
                        val titles = subTracks.map { it.second }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(R.string.track_subs)
                            .setItems(titles) { _, idx ->
                                val selectedId = subTracks[idx].first
                                if (selectedId == 0) {
                                    MPVLib.setPropertyString("sid", "no")
                                } else {
                                    MPVLib.setPropertyInt("sid", selectedId)
                                }
                            }.show()
                    }
                    1 -> { // 字体大小
                        val currentScale = MPVLib.getPropertyDouble("sub-scale") ?: 1.0
                        val sizes = arrayOf("小 (0.8x)", "标准 (1.0x)", "较大 (1.2x)", "特大 (1.5x)", "超大 (1.8x)")
                        val scaleVals = arrayOf(0.8, 1.0, 1.2, 1.5, 1.8)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sub_font_size)
                            .setItems(sizes) { _, sIdx ->
                                MPVLib.setPropertyDouble("sub-scale", scaleVals[sIdx])
                            }.show()
                    }
                    2 -> { // 垂直位置
                        val positions = arrayOf("底部 (100)", "偏底 (90)", "居中偏下 (80)", "中间 (50)", "顶部 (0)")
                        val posVals = arrayOf(100, 90, 80, 50, 0)
                        AlertDialog.Builder(this)
                            .setTitle(R.string.sub_vertical_position)
                            .setItems(positions) { _, pIdx ->
                                MPVLib.setPropertyInt("sub-pos", posVals[pIdx])
                            }.show()
                    }
                    3 -> { // 字幕延迟
                        val delays = arrayOf("-2.0s", "-1.0s", "-0.5s", "0s (正常)", "+0.5s", "+1.0s", "+2.0s")
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

    /**
     * 音轨选择对话框
     */
    private fun showAudioTrackDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val audioTracks = mutableListOf<Pair<Int, String>>()
        audioTracks.add(Pair(0, getString(R.string.track_off)))

        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type")
            if (type == "audio") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Audio $id"
                val lang = MPVLib.getPropertyString("track-list/$i/lang") ?: ""
                audioTracks.add(Pair(id, if (lang.isNotEmpty()) "$title ($lang)" else title))
            }
        }

        val titles = audioTracks.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_audio_track)
            .setItems(titles) { _, which ->
                val trackId = audioTracks[which].first
                if (trackId == 0) {
                    MPVLib.setPropertyString("aid", "no")
                } else {
                    MPVLib.setPropertyInt("aid", trackId)
                }
            }.show()
    }

    /**
     * 视轨选择对话框
     */
    private fun showVideoTrackDialog() {
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val videoTracks = mutableListOf<Pair<Int, String>>()

        for (i in 0 until count) {
            val type = MPVLib.getPropertyString("track-list/$i/type")
            if (type == "video") {
                val id = MPVLib.getPropertyInt("track-list/$i/id") ?: i
                val title = MPVLib.getPropertyString("track-list/$i/title") ?: "Video $id"
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

    /**
     * MPV 内核属性监听回调（驱动时间进度、片头片尾检测）
     */
    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> {
                val sec = value.toInt()
                currentPos = sec
                runOnUiThread {
                    binding.timeCurrent.text = formatTime(sec)
                    if (duration > 0) {
                        val progress = ((sec.toFloat() / duration) * 1000).toInt()
                        binding.playbackSeekbar.progress = progress
                    }

                    // 自动跳过片头检查
                    if (!introSkipped && skipIntroMs > 0L) {
                        val introSec = (skipIntroMs / 1000).toInt()
                        if (sec < introSec) {
                            MPVLib.command(arrayOf("seek", introSec.toString(), "absolute"))
                            introSkipped = true
                            Toast.makeText(this@MPVActivity, R.string.toast_intro_skipped, Toast.LENGTH_SHORT).show()
                        } else {
                            introSkipped = true
                        }
                    }

                    // 自动跳过片尾检查 (接近/到达片尾设定点自动退出或下一集)
                    if (skipOutroMs > 0L) {
                        val outroSec = (skipOutroMs / 1000).toInt()
                        if (sec >= outroSec && outroSec > 10) {
                            finish()
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
        if (property == "time-pos") {
            eventProperty(property, value.toLong())
        } else if (property == "duration") {
            eventProperty(property, value.toLong())
        }
    }

    override fun event(eventId: Int) {
        if (eventId == MPVLib.mpvEventId.MPV_EVENT_FILE_LOADED) {
            MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("duration", MPVLib.mpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("pause", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        } else if (eventId == MPVLib.mpvEventId.MPV_EVENT_END_FILE) {
            finish()
        }
    }

    private fun updatePlayBtnState(playing: Boolean) {
        binding.playBtn.text = if (playing) getString(R.string.btn_pause) else getString(R.string.btn_play)
    }

    private fun showControls() {
        binding.topControls.visibility = View.VISIBLE
        binding.controls.visibility = View.VISIBLE
        resetHideControlsTimer()
    }

    private fun hideControls() {
        binding.topControls.visibility = View.GONE
        binding.controls.visibility = View.GONE
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 6000)
    }

    /**
     * 适配电视盒子遥控器物理按键（方向键、OK键、返回键、菜单键）
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (binding.controls.visibility != View.VISIBLE) {
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.controls.visibility != View.VISIBLE) {
                        MPVLib.command(arrayOf("seek", "-10", "relative"))
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.controls.visibility != View.VISIBLE) {
                        MPVLib.command(arrayOf("seek", "10", "relative"))
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (binding.controls.visibility == View.VISIBLE) hideControls() else showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (binding.controls.visibility == View.VISIBLE) {
                        hideControls()
                        return true
                    }
                }
            }
            resetHideControlsTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun formatTime(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        super.onDestroy()
    }
}
