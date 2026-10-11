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
import org.json.JSONObject
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

    // 屏幕比例: 0: 默认, 1: 16:9, 2: 4:3, 3: 填充, 4: 原始, 5: 裁剪
    private var aspectIndex = 0

    // HDR to SDR: 0: 自动, 1: 开启, 2: 关闭
    private var hdrMode = 0

    // 片头片尾设置 (秒)
    private var skipIntroSec: Int = 0
    private var skipOutroSec: Int = 0
    private var introSkipped = false
    private var hasPlayedBeforeOutro = false

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
        val cacheDir = applicationContext.cacheDir.path

        // 由 MPVView 驱动注册 Surface 回调，确保视频帧正常上屏
        binding.player.initialize(configDir, cacheDir)
        MPVLib.addObserver(this)

        // 天猫魔盒 M19 (S912) 硬解与音频输出配置
        val hwdec = if (sharedPrefs.getBoolean("hardware_decoding", true)) "mediacodec" else "no"
        MPVLib.setOptionString("hwdec", hwdec)
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("ao", "audiotrack")

        // 4K 网盘高码率与直播流网络缓冲设置
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        MPVLib.setOptionString("network-timeout", "25")
        MPVLib.setOptionString("tls-verify", "no")

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
        if (uri == null) {
            Toast.makeText(this, R.string.error_no_file, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentFilePath = uri.toString()
        introSkipped = false
        hasPlayedBeforeOutro = false

        var title = intent.getStringExtra("title")
        if (title.isNullOrEmpty()) {
            title = uri.lastPathSegment ?: "Network Stream"
        }
        binding.titleTextView.text = title

        val cleanPath = "${uri.scheme}://${uri.host}${uri.path}"
        videoMemoryKey = if (title.isNotEmpty()) title else cleanPath

        skipIntroSec = sharedPrefs.getInt("intro_${videoMemoryKey.hashCode()}", 0)
        skipOutroSec = sharedPrefs.getInt("outro_${videoMemoryKey.hashCode()}", 0)
        updateIntroOutroButtonsText()

        // 提取 TVBox 请求头 (兼容数组、Bundle、HashMap、JSON)
        val headerList = mutableListOf<String>()
        var customUserAgent: String? = null
        var customReferer: String? = null

        // 1. 数组格式
        val headersArray = intent.getStringArrayExtra("headers")
        if (headersArray != null && headersArray.size >= 2) {
            for (i in 0 until headersArray.size step 2) {
                val k = headersArray[i]
                val v = headersArray.getOrNull(i + 1) ?: ""
                when {
                    k.equals("User-Agent", ignoreCase = true) -> customUserAgent = v
                    k.equals("Referer", ignoreCase = true) -> customReferer = v
                    else -> headerList.add("$k:$v")
                }
            }
        }

        // 2. Bundle 格式
        val httpHeadersBundle = intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
            ?: intent.getBundleExtra("headers")
        httpHeadersBundle?.let { bundle ->
            for (k in bundle.keySet()) {
                val v = bundle.getString(k) ?: bundle.get(k)?.toString() ?: ""
                when {
                    k.equals("User-Agent", ignoreCase = true) -> customUserAgent = v
                    k.equals("Referer", ignoreCase = true) -> customReferer = v
                    else -> headerList.add("$k:$v")
                }
            }
        }

        // 3. Map 格式
        val headersMap = try {
            intent.getSerializableExtra("headers") as? Map<*, *>
        } catch (e: Exception) {
            null
        }
        headersMap?.forEach { (k, v) ->
            val key = k?.toString() ?: ""
            val value = v?.toString() ?: ""
            if (key.isNotEmpty()) {
                when {
                    key.equals("User-Agent", ignoreCase = true) -> customUserAgent = value
                    key.equals("Referer", ignoreCase = true) -> customReferer = value
                    else -> headerList.add("$key:$value")
                }
            }
        }

        // 4. JSON 格式
        val headersJsonStr = intent.getStringExtra("headers")
        if (!headersJsonStr.isNullOrEmpty() && headersJsonStr.trim().startsWith("{")) {
            try {
                val json = JSONObject(headersJsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "")
                    when {
                        k.equals("User-Agent", ignoreCase = true) -> customUserAgent = v
                        k.equals("Referer", ignoreCase = true) -> customReferer = v
                        else -> headerList.add("$k:$v")
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }

        // 5. 显式 User-Agent 参数
        val explicitUa = intent.getStringExtra("User-Agent") ?: intent.getStringExtra("user_agent")
        if (!explicitUa.isNullOrEmpty()) customUserAgent = explicitUa

        if (!customUserAgent.isNullOrEmpty()) {
            MPVLib.setOptionString("user-agent", customUserAgent)
        }
        if (!customReferer.isNullOrEmpty()) {
            MPVLib.setOptionString("referrer", customReferer)
        }
        if (headerList.isNotEmpty()) {
            MPVLib.setOptionString("http-header-fields", headerList.joinToString(","))
        }

        // 提取 TVBox 传入的断点续播时间 (毫秒)
        val startPositionMs = intent.getIntExtra("position", 0).takeIf { it > 0 }?.toLong()
            ?: intent.getLongExtra("position", 0L)
        if (startPositionMs > 0L) {
            val startSec = (startPositionMs / 1000).toInt()
            MPVLib.setOptionString("start", startSec.toString())
        }

        MPVLib.command(arrayOf("loadfile", currentFilePath))
        showControls()
    }

    private fun setupUIControls() {
        binding.playBtn.setOnClickListener {
            togglePlayPause()
            resetHideControlsTimer()
        }

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
                MPVLib.setPropertyString("tone-mapping", "auto")
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
        aspectIndex = (aspectIndex + 1) % 6
        when (aspectIndex) {
            0 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "-1")
                binding.btnAspectRatio.text = getString(R.string.aspect_default)
            }
            1 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "16:9")
                binding.btnAspectRatio.text = getString(R.string.aspect_16_9)
            }
            2 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "4:3")
                binding.btnAspectRatio.text = getString(R.string.aspect_4_3)
            }
            3 -> {
                val metrics = resources.displayMetrics
                val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", ratio.toString())
                binding.btnAspectRatio.text = getString(R.string.aspect_fill)
            }
            4 -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                MPVLib.setPropertyString("video-aspect-override", "0")
                binding.btnAspectRatio.text = getString(R.string.aspect_original)
            }
            5 -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 1.0)
                binding.btnAspectRatio.text = getString(R.string.aspect_crop)
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
                    if (duration > 0) {
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
        if (eventId == MPVLib.MPV_EVENT_FILE_LOADED) {
            MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_INT64)
            MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_INT64)
            MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        } else if (eventId == MPVLib.MPV_EVENT_END_FILE) {
            runOnUiThread { quitAndReturnResult(isCompleted = true) }
        }
    }

    private fun updatePlayBtnState(playing: Boolean) {
        binding.playBtn.text = if (playing) getString(R.string.btn_pause) else getString(R.string.btn_play)
    }

    private fun showControls() {
        binding.topControls.visibility = View.VISIBLE
        binding.controls.visibility = View.VISIBLE
        binding.playBtn.requestFocus()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isControlsShowing = (binding.controls.visibility == View.VISIBLE)
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!isControlsShowing) {
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isControlsShowing) {
                        MPVLib.command(arrayOf("seek", "-10", "relative"))
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!isControlsShowing) {
                        MPVLib.command(arrayOf("seek", "10", "relative"))
                        showControls()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!isControlsShowing) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isControlsShowing) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (isControlsShowing) hideControls() else showControls()
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
            resetHideControlsTimer()
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
