package net.fourdir.player

// v0.2.1: 화면 캡처 때 앱이 꺼지던 것 수정(재생기를 저장 스레드에서 만졌음)
// v0.2.2: 조작 막대 저절로 안 띄움(톡 하면 뜸), 두 손가락 확대가 화면 전체로 퍼지게
// 재생 화면 v0.2.2 — Media3(ExoPlayer) + 기본 조작 막대(PlayerView: 재생·멈춤·이동·이전/다음·반복·속도·소리/자막 트랙)
//   + 위 막대: 뒤로 · 제목 · 자막 파일 · 화면 맞춤 · 회전 · 잠금 · 작은 창 · 더보기
//   + 손짓(TouchFrame): 두 번 톡 10초 이동/재생·멈춤, 길게 누르면 2배속, 밀기로 이동·밝기·소리, 두 손가락 확대
//   + 이어 보기(PlayerPrefs), 목록 재생(PlayerHost.open(items)), A-B 구간 반복, 소리 키우기(LoudnessEnhancer), 무음 건너뛰기
//   + 바깥 자막(같은 이름 자동·파일 열기·시간 맞추기·모양), 화면 캡처(PixelCopy -> 사진), 영상 정보
//   + 작은 창(PiP, 홈 단추로 자동), 뒤에서 재생(PlaybackService + MediaSession: 알림·잠금화면 조작), 이어폰 빼면 멈춤·소리 양보
// 형식: 폰에서 못 여는 형식은 PlayerHost.converter 로 MP4 로 바꿔 그 칸만 바꿔 끼움(칸마다 한 번).
// 기록: PlayerHost.log — 열기·트랙·오류·변환·손짓 결과·메뉴 선택

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

@UnstableApi
class PlayerActivity : AppCompatActivity(), TouchFrame.Callbacks {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_INDEX = "index"
        private const val REQ_SUB = 41
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
        private val SUB_SIZES = floatArrayOf(0.042f, 0.0533f, 0.07f, 0.09f)
    }

    private class SubState(val orig: File, val ext: String, var offsetMs: Long, var cur: File)

    private lateinit var pv: PlayerView
    private lateinit var frame: TouchFrame
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var center: TextView
    private lateinit var zoomBtn: TextView
    private lateinit var lockBtn: ImageButton
    private lateinit var resumeBar: LinearLayout
    private lateinit var resumeText: TextView
    private lateinit var prefs: PlayerPrefs
    private lateinit var am: AudioManager
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var items: List<PlayerHost.Item> = emptyList()
    private val subs = HashMap<Int, SubState>()
    private val sibChecked = HashSet<Int>()
    private val converted = HashMap<Int, File>()
    private val triedConvert = HashSet<Int>()
    private var convDlg: AlertDialog? = null
    @Volatile private var cancelConv = false
    private val main = Handler(Looper.getMainLooper())

    private var locked = false
    private var fill = false
    private var zoom = 1f
    private var abA = -1L; private var abB = -1L
    private var boostPct = 100
    private var enhancer: LoudnessEnhancer? = null
    private var bgStarted = false
    // 밀기 시작 값
    private var dragH = false; private var dragLeft = false
    private var startPos = 0L; private var seekTarget = -1L
    private var startBright = 0.5f; private var startVol = 0
    private var speedBeforeLong = 1f

    private fun t(s: String) = PlayerHost.text(s)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun cur() = player?.currentMediaItemIndex ?: 0

    // ================= 화면 =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.data == null) { PlayerHost.log("no uri -> finish"); finish(); return }
        prefs = PlayerPrefs(this)
        am = getSystemService(AudioManager::class.java)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() = goBack("key") })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        buildUi()
        hideBars()
        load(intent)
    }

    /** 인텐트의 목록으로 재생 시작. 재생 화면은 하나만(singleTask) — 이미 열려 있으면 onNewIntent 로 와서 지금 것을 정리하고 새로 연다 */
    private fun load(i: Intent) {
        val first = i.data ?: return
        val idx0 = i.getIntExtra(EXTRA_INDEX, 0)
        items = PlayerHost.pending?.takeIf { it.getOrNull(idx0)?.uri == first } ?: listOf(PlayerHost.Item(first, i.getStringExtra(EXTRA_TITLE)))
        PlayerHost.pending = null
        val start = if (items.size > 1) idx0 else 0
        PlayerHost.log("load n=${items.size} start=$start uri=$first")
        failDlg?.dismiss(); failDlg = null   // v0.2.1: 앞 파일의 '재생할 수 없습니다' 창이 새 파일 위에 남지 않게
        createPlayer(start)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.data == null) return
        setIntent(i)
        PlayerHost.log("new intent -> reload")
        releasePlayer()
        subs.clear(); sibChecked.clear(); triedConvert.clear(); abA = -1; abB = -1; zoom = 1f; if (::pv.isInitialized) resetZoom()
        if (locked) setLocked(false)
        load(i)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        frame = TouchFrame(this, this)
        pv = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(true); setShowPreviousButton(true)
            setShowSubtitleButton(true)
            setRepeatToggleModes(androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or androidx.media3.common.util.RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
            controllerShowTimeoutMs = 3500
            // v0.2.2: 처음 틀 때·멈출 때·끝날 때 조작 막대가 저절로 떠서 안 사라졌음(11-06 사용자) -> 저절로 띄우지 않음. 톡 하면 뜨고 3.5초 뒤 숨음
            controllerAutoShow = false
            // 숨을 때 '진행 막대만 남기기' 단계를 거치면 위 막대가 늦게 사라짐 -> 한 번에 숨김
            setControllerAnimationEnabled(false)
        }
        frame.addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // v0.2.2: 조작 막대가 뜰 때 화면이 너무 어두워짐(기본 검정 60%, 11-06 사용자) -> 35%
        pv.findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)?.setBackgroundColor(0x59000000)
        root.addView(frame, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 위 막대
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
            visibility = View.GONE   // v0.2.2: 조작 막대와 같이 처음엔 숨김
        }
        fun ib(icon: Int, desc: String, f: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon); contentDescription = desc; setBackgroundColor(Color.TRANSPARENT); setPadding(dp(9), dp(9), dp(9), dp(9)); setOnClickListener(f)
        }
        topBar.addView(ib(R.drawable.p_back, t("뒤로")) { goBack("button") }, LinearLayout.LayoutParams(dp(42), dp(42)))
        titleView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), 0, dp(4), 0) }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        topBar.addView(ib(R.drawable.p_subtitle, t("자막 파일 열기")) { pickSubtitle() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        // v0.2.2: 네 모서리 단추 = 전체 화면(가로로 돌려 비율 그대로 가득). 회전·꽉 채우기·잠금은 더보기로(11-06 사용자)
        topBar.addView(ib(R.drawable.p_aspect, t("전체 화면")) { toggleFull() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        if (Build.VERSION.SDK_INT >= 26) topBar.addView(ib(R.drawable.p_pip, t("작은 창으로 보기")) { enterPip("button") }, LinearLayout.LayoutParams(dp(42), dp(42)))
        topBar.addView(ib(R.drawable.p_more, t("더보기")) { moreMenu() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> topBar.visibility = if (locked || isInPip()) View.GONE else v })

        // 가운데 안내(밝기·소리·이동·배속·확대)
        center = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x99000000.toInt()) }
        }
        root.addView(center, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        // 잠금 풀기 단추(잠금 중 톡 치면 잠깐 보임)
        lockBtn = ImageButton(this).apply {
            setImageResource(R.drawable.p_lock); contentDescription = t("잠금 풀기"); visibility = View.GONE; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            setOnClickListener { setLocked(false) }
        }
        root.addView(lockBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(24) })

        // v0.2.2: 확대한 동안만 보이는 '100%' 단추(누르면 원래 크기·자리)
        zoomBtn = TextView(this).apply {
            text = "100%"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); visibility = View.GONE
            // 검은 화면 위에선 글자만 보여 단추인지 몰랐음(11-06 사용자) -> 흰 테두리
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x99000000.toInt()); setStroke(dp(2), Color.WHITE) }
            contentDescription = t("원래 크기"); setOnClickListener { resetZoom(); showCenter(t("확대") + " 100%") }
        }
        root.addView(zoomBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })

        // 이어 보기 안내
        resumeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(14), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xCC202020.toInt()) }
        }
        resumeText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        resumeBar.addView(resumeText)
        resumeBar.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = t("처음부터"); setTextColor(0xFF8AB4F8.toInt()); isAllCaps = false
            setOnClickListener { player?.seekTo(0); resumeBar.visibility = View.GONE; PlayerHost.log("resume -> from start") }
        })
        root.addView(resumeBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(130) })

        // 노치·둥근 모서리만 비킴(상태 막대는 숨김)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(4) + b.left, dp(4) + b.top, dp(4) + b.right, dp(4))
            pv.setPadding(b.left, 0, b.right, b.bottom)
            ins
        }
        setContentView(root)
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }

    private fun showCenter(s: String, ms: Long = 700) {
        center.text = s; center.visibility = View.VISIBLE
        main.removeCallbacks(hideCenter); main.postDelayed(hideCenter, ms)
    }
    private val hideCenter = Runnable { center.visibility = View.GONE }

    private fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ================= 재생기 =================
    private fun title(i: Int) = items.getOrNull(i)?.title ?: items.getOrNull(i)?.uri?.lastPathSegment.orEmpty()

    private fun mediaItem(i: Int): MediaItem {
        val b = MediaItem.Builder().setUri(converted[i]?.let { Uri.fromFile(it) } ?: items[i].uri).setMediaId("$i")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title(i)).build())
        subs[i]?.let { s ->
            b.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s.cur)).setMimeType(Subs.mime(s.ext))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLanguage("und").setLabel(t("바깥 자막")).build()))
        }
        return b.build()
    }

    private fun createPlayer(start: Int) {
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val p = ExoPlayer.Builder(this, rf)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)   // 다른 소리에 양보
            .setHandleAudioBecomingNoisy(true)                                                                                                // 이어폰 빼면 멈춤
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()
        p.setAudioSessionId(am.generateAudioSessionId())
        player = p
        pv.player = p
        applySubStyle()
        p.addListener(listener)
        val startPos = prefs.pos(items[start].uri.toString())
        p.setMediaItems(items.indices.map { mediaItem(it) }, start, startPos)
        p.prepare()
        p.playWhenReady = true
        titleView.text = title(start)
        if (startPos > 0) showResume(startPos)
        session = MediaSession.Builder(this, p).setId("fourdir_${System.nanoTime()}")
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .build()
        PlaybackService.Core.session = session
        checkSibling(start)
        PlayerHost.log("player start index=$start resume=$startPos session=${session != null}")
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            PlayerHost.log("state=" + when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready dur=${p.duration}ms"; Player.STATE_ENDED -> "ended"; else -> "idle" })
            if (state == Player.STATE_ENDED) prefs.savePos(items[cur()].uri.toString(), 0, 0)
        }
        override fun onIsPlayingChanged(playing: Boolean) {
            if (playing && abB > 0) main.post(abTick)
            if (playing && prefs.bgPlay) startBg()
            updatePipParams()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val i = cur()
            titleView.text = title(i)
            abA = -1; abB = -1
            PlayerHost.log("item -> $i reason=$reason ${items.getOrNull(i)?.uri}")
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val s = prefs.pos(items[i].uri.toString())
                if (s > 0) { player?.seekTo(s); showResume(s) }
            }
            checkSibling(i)
        }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (old.mediaItemIndex != new.mediaItemIndex && old.mediaItemIndex in items.indices) {
                val key = items[old.mediaItemIndex].uri.toString()
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) prefs.savePos(key, 0, 0) else prefs.savePos(key, old.positionMs, 0)
            }
        }
        override fun onTracksChanged(tracks: Tracks) = checkTracks(tracks)
        // v0.2.1 진단: 자막 글자가 실제로 들어오는지(바뀔 때만 기록)
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            val txt = cueGroup.cues.joinToString(" / ") { it.text?.toString().orEmpty() }
            if (txt != lastCue) { lastCue = txt; PlayerHost.log("cues at=${player?.currentPosition} n=${cueGroup.cues.size} text=$txt") }
        }
        override fun onPlayerError(e: PlaybackException) {
            PlayerHost.log("ERROR index=${cur()} code=${e.errorCodeName} msg=${e.message} cause=${e.cause?.javaClass?.simpleName}: ${e.cause?.message}")
            convertOrFail(cur(), false, e.errorCodeName)
        }
        override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) = updatePipParams()
    }

    /** 트랙 기록 + 영상/소리가 있는데 하나도 재생 못 하면 변환 */
    private fun checkTracks(tracks: Tracks) {
        var hasV = false; var okV = false; var hasA = false; var okA = false
        for (g in tracks.groups) {
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                PlayerHost.log("track type=${g.type} mime=${f.sampleMimeType} codecs=${f.codecs} ${f.width}x${f.height} ch=${f.channelCount} lang=${f.language} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}")
            }
            when (g.type) {
                C.TRACK_TYPE_VIDEO -> { hasV = true; if (g.isSupported) okV = true }
                C.TRACK_TYPE_AUDIO -> { hasA = true; if (g.isSupported) okA = true }
            }
        }
        if ((hasV && !okV) || (hasA && !okA)) convertOrFail(cur(), !hasV || okV, "unsupported track video=$hasV/$okV audio=$hasA/$okA")
    }

    private fun convertOrFail(index: Int, audioOnly: Boolean, why: String) {
        val conv = PlayerHost.converter
        if (index in triedConvert || conv == null) { PlayerHost.log("cannot play index=$index ($why)"); showFail(why); return }
        triedConvert += index
        val p = player ?: return
        p.pause()
        PlayerHost.log("convert start index=$index audioOnly=$audioOnly why=$why")
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 100 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(bar) }
        cancelConv = false
        convDlg = AlertDialog.Builder(this).setTitle(t("재생할 수 있게 바꾸는 중…"))
            .setMessage(t("이 폰에서 바로 열 수 없는 형식이라 MP4로 바꿉니다. 영상이 길면 오래 걸립니다."))
            .setView(box).setCancelable(false)
            .setNegativeButton(t("취소")) { _, _ -> cancelConv = true; PlayerHost.log("convert cancel by user"); if (items.size <= 1) finish() }.show()
        val t0 = System.currentTimeMillis()
        val src = items[index].uri
        Thread({
            val out = try { conv.toPlayable(this, src, audioOnly, { v -> runOnUiThread { bar.isIndeterminate = false; bar.progress = (v * 100).toInt() } }, { cancelConv }) }
                catch (e: Exception) { PlayerHost.log("convert exception ${e.javaClass.simpleName}: ${e.message}"); null }
            PlayerHost.log("convert end index=$index out=${out?.name} ${out?.length()}B ${System.currentTimeMillis() - t0}ms")
            runOnUiThread {
                try { convDlg?.dismiss() } catch (_: Exception) {}
                if (isDestroyed || cancelConv) { out?.delete(); return@runOnUiThread }
                if (out == null) { showFail(why); return@runOnUiThread }
                converted[index] = out
                player?.let { pl -> pl.replaceMediaItem(index, mediaItem(index)); pl.prepare(); pl.play() }
            }
        }, "player-convert").start()
    }

    private var failDlg: AlertDialog? = null
    private var lastCue = ""
    private fun showFail(why: String) {
        if (isDestroyed || isFinishing) return
        failDlg?.dismiss()
        failDlg = AlertDialog.Builder(this).setTitle(t("재생할 수 없습니다"))
            .setMessage(t("이 파일의 형식을 열 수 없습니다.") + "\n($why)")
            .setPositiveButton(t("확인")) { _, _ -> if (items.size <= 1) finish() }.setCancelable(false).show()
    }

    private fun showResume(pos: Long) {
        resumeText.text = t("{0}부터 이어서 재생").replace("{0}", clock(pos))
        resumeBar.visibility = View.VISIBLE
        main.removeCallbacks(hideResume); main.postDelayed(hideResume, 6000)
    }
    private val hideResume = Runnable { resumeBar.visibility = View.GONE }

    private fun saveCurPos() {
        val p = player ?: return
        if (items.isEmpty()) return
        prefs.savePos(items[cur()].uri.toString(), p.currentPosition, p.duration)
    }

    // ================= 손짓 =================
    override fun locked() = locked
    override fun onLockedTap() {
        lockBtn.visibility = View.VISIBLE
        main.removeCallbacks(hideLock); main.postDelayed(hideLock, 2500)
    }
    private val hideLock = Runnable { lockBtn.visibility = View.GONE }

    private fun setLocked(v: Boolean) {
        locked = v
        pv.useController = !v
        if (v) { pv.hideController(); topBar.visibility = View.GONE; onLockedTap(); showCenter(t("화면 잠금")) }
        else { lockBtn.visibility = View.GONE; pv.showController(); showCenter(t("잠금 풀림")) }
        PlayerHost.log("lock=$v")
    }

    override fun onDouble(x: Float, w: Int) {
        val p = player ?: return
        when {
            x < w / 3f -> { p.seekBack(); showCenter("◀◀ " + t("{0}초").replace("{0}", "10")) }
            x > w * 2 / 3f -> { p.seekForward(); showCenter(t("{0}초").replace("{0}", "10") + " ▶▶") }
            else -> { if (p.isPlaying) p.pause() else p.play(); showCenter(if (p.isPlaying) "▶" else "❚❚") }
        }
        PlayerHost.log("gesture double x=${(x / w * 100).toInt()}%")
    }

    override fun onLong() {
        val p = player ?: return
        if (!p.isPlaying) return
        speedBeforeLong = p.playbackParameters.speed
        p.setPlaybackSpeed(2f)
        center.text = "2× ▶▶"; center.visibility = View.VISIBLE; main.removeCallbacks(hideCenter)
        PlayerHost.log("gesture long -> 2x")
    }
    override fun onLongEnd() {
        val p = player ?: return
        if (p.playbackParameters.speed == 2f && speedBeforeLong != 2f) p.setPlaybackSpeed(speedBeforeLong)
        center.visibility = View.GONE
    }

    // v0.2.2: 확대한 동안엔 한 손가락 밀기 = 화면 옮기기(소리·밝기·위치 조절은 100% 일 때만)
    private var panning = false; private var panBaseX = 0f; private var panBaseY = 0f
    private fun contentFrame(): View? = if (::pv.isInitialized) pv.findViewById(androidx.media3.ui.R.id.exo_content_frame) else null

    override fun onDragStart(horizontal: Boolean, x: Float, w: Int) {
        if (zoom > 1.01f) { contentFrame()?.let { panning = true; panBaseX = it.translationX; panBaseY = it.translationY; pv.hideController(); return } }
        val p = player ?: return
        dragH = horizontal; dragLeft = x < w / 2f
        startPos = p.currentPosition; seekTarget = -1
        startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val b = window.attributes.screenBrightness
        startBright = if (b >= 0) b else try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f }
        pv.hideController()
    }

    override fun onDrag(dx: Float, dy: Float, w: Int, h: Int) {
        if (panning) { contentFrame()?.let { it.translationX = panBaseX + dx; it.translationY = panBaseY + dy; applyZoom() }; return }
        val p = player ?: return
        if (dragH) {
            val dur = p.duration.takeIf { it > 0 } ?: return
            val span = minOf(dur, 120_000L).coerceAtLeast(20_000L)   // 화면 너비 = 최대 2분
            seekTarget = (startPos + (dx / w * span).toLong()).coerceIn(0, dur)
            val d = seekTarget - startPos
            showCenter((if (d >= 0) "+" else "−") + clock(abs(d)) + "\n" + clock(seekTarget) + " / " + clock(dur), 1500)
        } else if (dragLeft) {
            val v = (startBright - dy / h * 1.2f).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = v }
            showCenter(t("밝기") + " " + (v * 100).toInt() + "%")
        } else {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = (startVol - (dy / h * 1.2f * max)).toInt().coerceIn(0, max)
            if (v != am.getStreamVolume(AudioManager.STREAM_MUSIC)) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            showCenter(t("소리") + " " + v + " / " + max + if (boostPct > 100) " (+${boostPct}%)" else "")
        }
    }

    override fun onDragEnd() {
        if (panning) { panning = false; contentFrame()?.let { PlayerHost.log("gesture pan x=${it.translationX.toInt()} y=${it.translationY.toInt()} zoom=$zoom") }; return }
        if (dragH && seekTarget >= 0) { player?.seekTo(seekTarget); PlayerHost.log("gesture seek ${startPos}ms -> ${seekTarget}ms") }
        else if (!dragH) PlayerHost.log("gesture ${if (dragLeft) "bright=${window.attributes.screenBrightness}" else "vol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}"}")
        seekTarget = -1
    }

    override fun onScale(f: Float) {
        zoom = (zoom * f).coerceIn(0.5f, 4f)
        applyZoom()
        showCenter(t("확대") + " " + (zoom * 100).toInt() + "%")
    }

    // v0.2.2: 영상 그림만 키우면 원래 영상 띠 안에서 잘려 위아래 검은 곳이 그대로였음(11-06 사용자) ->
    //         영상 틀(그림+자막 묶음) 전체를 키워 화면 전체로 퍼지게 하고, 자막은 반대로 줄여 원래 크기·자리 유지
    //         옮긴 자리는 영상 끝이 화면 안으로 들어오지 않게 묶고, 자막은 옮김도 되돌려 원래 자리 유지. 100% 가 아니면 '100%' 단추를 보임
    private fun applyZoom() {
        val cf = contentFrame() ?: return
        cf.scaleX = zoom; cf.scaleY = zoom
        val mx = ((cf.width * zoom - pv.width) / 2f).coerceAtLeast(0f)
        val my = ((cf.height * zoom - pv.height) / 2f).coerceAtLeast(0f)
        cf.translationX = cf.translationX.coerceIn(-mx, mx); cf.translationY = cf.translationY.coerceIn(-my, my)
        pv.subtitleView?.apply { scaleX = 1f / zoom; scaleY = 1f / zoom; translationX = -cf.translationX / zoom; translationY = -cf.translationY / zoom }
        if (::zoomBtn.isInitialized) zoomBtn.visibility = if (abs(zoom - 1f) > 0.01f && !isInPip()) View.VISIBLE else View.GONE
    }
    private fun resetZoom() { zoom = 1f; contentFrame()?.apply { translationX = 0f; translationY = 0f }; applyZoom(); PlayerHost.log("zoom reset") }
    override fun onScaleEnd() { PlayerHost.log("gesture zoom=$zoom") }

    // ================= 위 막대 =================
    // v0.2.2: 뒤로(단추·폰 뒤로) = 앱 화면으로. 재생기는 작은 창 때문에 따로 떠 있어서 그냥 닫으면 폰 홈으로 나갔음(11-06 사용자)
    private fun goBack(why: String) {
        PlayerHost.log("back ($why) -> app")
        if (!isInPip()) packageManager.getLaunchIntentForPackage(packageName)?.let {
            try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { PlayerHost.log("back to app fail ${e.message}") }
        }
        finish()
    }

    // v0.2.2: 전체 화면 = 가로로 돌려 비율 그대로 가득(세로 영상은 세로 그대로). 지금 가로면 세로로 돌아감
    private fun toggleFull() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val vs = player?.videoSize
        val tall = vs != null && vs.height > vs.width * vs.pixelWidthHeightRatio
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; fill = false; resetZoom()
        requestedOrientation = when {
            land -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            tall -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (!land) showCenter(t("전체 화면"))
        PlayerHost.log("full -> ${if (land) "portrait" else if (tall) "portrait(tall video)" else "landscape"}")
    }

    // 더보기: 꽉 채우기(비율 유지, 가장자리 잘림) 켜기/끄기 — '늘리기'(비율 깨짐)는 뺌
    private fun toggleFill() {
        fill = !fill
        pv.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        resetZoom()
        showCenter(if (fill) t("꽉 채우기") else t("화면 맞춤")); PlayerHost.log("fill=$fill")
    }

    private fun rotateDialog() {
        val modes = intArrayOf(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val names = arrayOf(t("가로 고정"), t("세로 고정"), t("자동 회전"))
        AlertDialog.Builder(this).setTitle(t("화면 회전")).setSingleChoiceItems(names, modes.indexOf(requestedOrientation).coerceAtLeast(-1)) { d, w ->
            requestedOrientation = modes[w]; showCenter(names[w]); PlayerHost.log("rotate=${names[w]}"); d.dismiss()
        }.show()
    }

    // ================= 더보기 =================
    private fun moreMenu() {
        val p = player ?: return
        val m = ArrayList<Pair<String, () -> Unit>>()
        m += t("재생 속도") + " (${p.playbackParameters.speed}×)" to { speedDialog() }
        m += (when { abA < 0 -> t("구간 반복: 지금을 A로"); abB < 0 -> t("구간 반복: 지금을 B로"); else -> t("구간 반복 끄기") }) to { abStep() }
        m += t("소리 키우기") + " (${boostPct}%)" to { boostDialog() }
        m += (if (p.skipSilenceEnabled) t("무음 건너뛰기 끄기") else t("무음 건너뛰기 켜기")) to {
            p.skipSilenceEnabled = !p.skipSilenceEnabled; showCenter(if (p.skipSilenceEnabled) t("무음 건너뛰기 켜짐") else t("무음 건너뛰기 꺼짐")); PlayerHost.log("skipSilence=${p.skipSilenceEnabled}")
        }
        m += t("자막 파일 열기") to { pickSubtitle() }
        if (subs[cur()] != null) m += t("자막 시간 맞추기") to { subDelayDialog() }
        m += t("자막 모양") to { subStyleDialog() }
        m += (if (fill) t("꽉 채우기: 켜짐") else t("꽉 채우기: 꺼짐")) to { toggleFill() }
        m += t("화면 회전") to { rotateDialog() }
        m += t("화면 잠금") to { setLocked(true) }
        m += t("화면 캡처") to { capture() }
        if (Build.VERSION.SDK_INT >= 26) m += t("작은 창으로 보기") to { enterPip("menu") }
        m += (if (prefs.autoPip) t("홈 단추로 나가면 작은 창: 켜짐") else t("홈 단추로 나가면 작은 창: 꺼짐")) to {
            prefs.autoPip = !prefs.autoPip; updatePipParams(); PlayerHost.log("autoPip=${prefs.autoPip}")
        }
        m += (if (prefs.bgPlay) t("화면을 나가도 소리 계속: 켜짐") else t("화면을 나가도 소리 계속: 꺼짐")) to {
            prefs.bgPlay = !prefs.bgPlay; if (prefs.bgPlay && p.isPlaying) startBg() else if (!prefs.bgPlay) stopBg(); PlayerHost.log("bgPlay=${prefs.bgPlay}")
        }
        m += t("영상 정보") to { infoDialog() }
        val u = items[cur()].uri
        PlayerHost.actions.forEach { a -> val l = a.label(); m += l to { PlayerHost.log("host action $l"); p.pause(); a.run(this, u, title(cur())) } }
        AlertDialog.Builder(this).setItems(m.map { it.first }.toTypedArray()) { _, w -> m[w].second() }.show()
    }

    private fun speedDialog() {
        val p = player ?: return
        val labels = SPEEDS.map { "${it}×" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t("재생 속도")).setSingleChoiceItems(labels, SPEEDS.indexOfFirst { it == p.playbackParameters.speed }) { d, w ->
            p.playbackParameters = PlaybackParameters(SPEEDS[w]); PlayerHost.log("speed=${SPEEDS[w]}"); d.dismiss()
        }.show()
    }

    private fun abStep() {
        val p = player ?: return
        when {
            abA < 0 -> { abA = p.currentPosition; showCenter("A " + clock(abA), 1200) }
            abB < 0 -> {
                if (p.currentPosition <= abA + 500) { Toast.makeText(this, t("B는 A보다 뒤여야 합니다"), Toast.LENGTH_SHORT).show(); return }
                abB = p.currentPosition; p.seekTo(abA); p.play(); main.post(abTick); showCenter("A-B " + clock(abA) + " ~ " + clock(abB), 1500)
            }
            else -> { abA = -1; abB = -1; showCenter(t("구간 반복 끔")) }
        }
        PlayerHost.log("ab A=$abA B=$abB")
    }
    // 구간 반복 확인은 재생 중이고 B가 있을 때만 0.1초마다
    private val abTick: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (abB < 0 || !p.isPlaying) return
            if (p.currentPosition >= abB || p.currentPosition < abA - 1000) p.seekTo(abA)
            main.postDelayed(this, 100)
        }
    }

    private fun boostDialog() {
        val opts = intArrayOf(100, 150, 200, 300)
        AlertDialog.Builder(this).setTitle(t("소리 키우기")).setSingleChoiceItems(opts.map { "$it%" }.toTypedArray(), opts.indexOf(boostPct)) { d, w ->
            setBoost(opts[w]); d.dismiss()
        }.show()
    }

    private fun setBoost(pct: Int) {
        val p = player ?: return
        try {
            if (enhancer == null && pct > 100) enhancer = LoudnessEnhancer(p.audioSessionId)
            enhancer?.setTargetGain(if (pct > 100) (2000 * log10(pct / 100.0)).toInt() else 0)
            enhancer?.enabled = pct > 100
            boostPct = pct
            showCenter(t("소리 키우기") + " $pct%")
            PlayerHost.log("boost=$pct session=${p.audioSessionId}")
        } catch (e: Exception) { PlayerHost.log("boost fail ${e.message}"); Toast.makeText(this, t("이 폰에서는 소리 키우기를 쓸 수 없습니다"), Toast.LENGTH_SHORT).show() }
    }

    // ---- 자막 ----
    @Suppress("DEPRECATION")
    private fun pickSubtitle() {
        player?.pause()
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_SUB) }
        catch (e: Exception) { PlayerHost.log("subtitle picker fail ${e.message}") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SUB) return
        val u = data?.data ?: run { player?.play(); return }
        var name = u.lastPathSegment.orEmpty()
        try { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) } } catch (_: Exception) {}
        val ext = name.substringAfterLast('.', "").lowercase()
        PlayerHost.log("subtitle picked name=$name")
        if (ext !in Subs.EXT) { Toast.makeText(this, t("자막 파일(SRT·VTT·ASS)이 아닙니다"), Toast.LENGTH_LONG).show(); player?.play(); return }
        val f = File(Subs.dir(this), "pick_${System.currentTimeMillis()}.$ext")
        try { contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        catch (e: Exception) { PlayerHost.log("subtitle copy fail ${e.message}"); return }
        attachSub(cur(), f, ext)
        showCenter(t("자막을 붙였습니다"), 1200)
    }

    /** 같은 이름의 자막이 있으면 자동으로 붙임(칸마다 한 번 확인) */
    private fun checkSibling(i: Int) {
        if (i in sibChecked || subs[i] != null) return
        sibChecked += i
        val u = items.getOrNull(i)?.uri ?: return
        Thread({
            val f = Subs.sibling(this, u) ?: return@Thread
            runOnUiThread { if (!isDestroyed && subs[i] == null) { attachSub(i, f, f.extension); showCenter(t("같은 이름의 자막을 붙였습니다"), 1500) } }
        }, "player-subs").start()
    }

    private fun attachSub(i: Int, f: File, ext: String) {
        subs[i] = SubState(f, ext, 0, f)
        reloadItem(i)
        PlayerHost.log("subtitle attach index=$i ${f.name}")
    }

    private fun reloadItem(i: Int) {
        val p = player ?: return
        val pos = p.currentPosition
        val isCur = i == cur()
        // replaceMediaItem 은 주소가 같으면 자막 설정을 무시하고 그 자리에서 갱신만 함(11-03 실측: 자막 트랙 안 생김) -> 빼고 다시 넣음
        p.removeMediaItem(i)
        p.addMediaItem(i, mediaItem(i))
        if (isCur) { p.seekTo(i, pos); p.prepare(); p.play() }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    private fun subDelayDialog() {
        val s = subs[cur()] ?: return
        val label = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) }
        fun show() { label.text = (if (s.offsetMs >= 0) "+" else "") + "%.1f".format(s.offsetMs / 1000.0) + t("초") }
        show()
        // v0.2.1: 단추를 연달아 누르면 다시 싣기가 겹쳐 자막이 안 나왔음(11-06 실측) -> 누름이 멈추고 0.6초 뒤(또는 창 닫을 때) 한 번만 적용
        var applied = s.offsetMs
        val apply = Runnable {
            if (s.offsetMs == applied) return@Runnable
            applied = s.offsetMs
            try { s.cur = if (s.offsetMs == 0L) s.orig else Subs.shift(this, s.orig, s.ext, s.offsetMs); reloadItem(cur()) } catch (e: Exception) { PlayerHost.log("subtitle shift fail ${e.message}") }
            PlayerHost.log("subtitle offset=${s.offsetMs}")
        }
        fun change(d: Long) {
            s.offsetMs = if (d == 0L) 0 else s.offsetMs + d
            show(); main.removeCallbacks(apply); main.postDelayed(apply, 600)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun b(txt: String, d: Long) = Button(this).apply { text = txt; isAllCaps = false; setOnClickListener { change(d) } }
        row.addView(b("−0.5", -500)); row.addView(b("0", 0)); row.addView(b("+0.5", 500))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(row)
            addView(TextView(context).apply { text = t("자막이 늦게 나오면 −, 일찍 나오면 +"); setTextColor(0xFFBBBBBB.toInt()); gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), 0) }) }
        AlertDialog.Builder(this).setTitle(t("자막 시간 맞추기")).setView(box).setPositiveButton(t("확인"), null)
            .setOnDismissListener { main.removeCallbacks(apply); apply.run() }.show()
    }

    private fun subStyleDialog() {
        val sizes = arrayOf(t("작게"), t("보통"), t("크게"), t("아주 크게"))
        val bgs = arrayOf(t("배경 없음"), t("반투명 배경"))
        val labels = sizes.mapIndexed { i, s -> (if (i == prefs.subSize) "● " else "○ ") + t("크기") + ": " + s } + bgs.mapIndexed { i, s -> (if (i == prefs.subBg) "● " else "○ ") + s }
        AlertDialog.Builder(this).setTitle(t("자막 모양")).setItems(labels.toTypedArray()) { _, w ->
            if (w < sizes.size) prefs.subSize = w else prefs.subBg = w - sizes.size
            applySubStyle(); PlayerHost.log("subtitle style size=${prefs.subSize} bg=${prefs.subBg}")
        }.show()
    }

    private fun applySubStyle() {
        pv.subtitleView?.apply {
            setFractionalTextSize(SUB_SIZES[prefs.subSize.coerceIn(SUB_SIZES.indices)])
            setStyle(CaptionStyleCompat(Color.WHITE, if (prefs.subBg == 1) 0x99000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        }
    }

    // ---- 캡처·정보 ----
    private fun capture() {
        val p = player ?: return
        val sv = pv.videoSurfaceView as? SurfaceView ?: run { Toast.makeText(this, t("캡처할 수 없습니다"), Toast.LENGTH_SHORT).show(); return }
        val vs = p.videoSize
        val w = if (vs.width > 0) vs.width else sv.width; val h = if (vs.height > 0) vs.height else sv.height
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < 24) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val at = p.currentPosition
        // v0.2.1: 이름은 화면 스레드에서 미리 만든다(재생기는 다른 스레드에서 만지면 앱이 꺼짐)
        val name = (title(cur()).substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)) + "_" + clock(at).replace(':', '-') + ".jpg"
        PixelCopy.request(sv, bmp, { r ->
            if (r != PixelCopy.SUCCESS) { PlayerHost.log("capture fail code=$r"); Toast.makeText(this, t("캡처할 수 없습니다"), Toast.LENGTH_SHORT).show(); return@request }
            Thread({
                val ok = try {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.MediaColumns.RELATIVE_PATH, PlayerHost.picturesDir); put(MediaStore.MediaColumns.IS_PENDING, 1) }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    PlayerHost.log("capture saved ${PlayerHost.picturesDir}/$name ${w}x$h"); true
                } catch (e: Exception) { PlayerHost.log("capture save fail ${e.message}"); false }
                runOnUiThread { Toast.makeText(this, if (ok) t("사진에 저장했습니다: {0}").replace("{0}", name) else t("캡처할 수 없습니다"), Toast.LENGTH_SHORT).show() }
            }, "player-capture").start()
        }, main)
    }

    private fun infoDialog() {
        val p = player ?: return
        val u = items[cur()].uri
        var size = -1L
        try { contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) size = it.getLong(0) } } catch (_: Exception) {}
        if (size < 0 && u.scheme == "file") size = File(u.path ?: "").length()
        val v = p.videoFormat; val a = p.audioFormat
        val lines = listOfNotNull(
            title(cur()),
            t("길이") + ": " + clock(p.duration),
            v?.let { t("영상") + ": ${it.width}×${it.height} · ${it.sampleMimeType?.substringAfter('/')}" + (if (it.frameRate > 0) " · ${"%.0f".format(it.frameRate)}fps" else "") + (if (it.bitrate > 0) " · ${it.bitrate / 1000}kbps" else "") },
            a?.let { t("소리") + ": ${it.sampleMimeType?.substringAfter('/')} · ${it.sampleRate}Hz · ${it.channelCount}ch" },
            if (size > 0) t("크기") + ": " + "%.1fMB".format(size / 1048576.0) else null,
            if (converted[cur()] != null) t("이 폰에서 열 수 있게 바꿔서 재생 중") else null,
        )
        AlertDialog.Builder(this).setTitle(t("영상 정보")).setMessage(lines.joinToString("\n")).setPositiveButton(t("확인"), null).show()
    }

    // ================= 작은 창·뒤에서 재생 =================
    private fun isInPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player?.videoSize
        val r = if (vs != null && vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height) else Rational(16, 9)
        val clamped = when { r.toFloat() > 2.39f -> Rational(239, 100); r.toFloat() < 0.42f -> Rational(100, 239); else -> r }
        return PictureInPictureParams.Builder().setAspectRatio(clamped).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(prefs.autoPip && player?.isPlaying == true); setSeamlessResizeEnabled(true) }
        }.build()
    }

    private fun updatePipParams() { if (Build.VERSION.SDK_INT >= 26) try { pipParams()?.let { setPictureInPictureParams(it) } } catch (_: Exception) {} }

    private fun enterPip(why: String) {
        if (Build.VERSION.SDK_INT < 26) return
        try { enterPictureInPictureMode(pipParams()!!); PlayerHost.log("pip enter ($why)") } catch (e: Exception) { PlayerHost.log("pip fail ${e.message}") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 12 이상은 setAutoEnterEnabled 로 시스템이 알아서 들어감
        if (Build.VERSION.SDK_INT in 26..30 && prefs.autoPip && player?.isPlaying == true) enterPip("home")
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        pv.useController = !inPip && !locked
        if (inPip) { pv.hideController(); topBar.visibility = View.GONE; center.visibility = View.GONE; resumeBar.visibility = View.GONE; zoomBtn.visibility = View.GONE }
        else applyZoom()
        PlayerHost.log("pip mode=$inPip")
    }

    private fun startBg() {
        if (bgStarted) return
        try { startService(Intent(this, PlaybackService::class.java)); bgStarted = true; PlayerHost.log("bg service start") }
        catch (e: Exception) { PlayerHost.log("bg service start fail ${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun stopBg() { if (bgStarted) { stopService(Intent(this, PlaybackService::class.java)); bgStarted = false; PlayerHost.log("bg service stop") } }

    private fun releasePlayer() {
        if (player != null) saveCurPos()
        stopBg()
        if (PlaybackService.Core.session === session) PlaybackService.Core.session = null
        session?.release(); session = null
        enhancer?.release(); enhancer = null; boostPct = 100
        player?.release(); player = null
        converted.values.forEach { PlayerHost.log("delete converted ${it.name} ok=${it.delete()}") }
        converted.clear()
    }

    // ================= 생명주기 =================
    override fun onStop() {
        super.onStop()
        saveCurPos()
        // 뒤에서 재생이 꺼져 있으면 멈춤(작은 창을 닫았을 때도 여기로 옴)
        if (!(prefs.bgPlay && player?.isPlaying == true)) player?.pause()
        PlayerHost.log("stop bg=${prefs.bgPlay} playing=${player?.isPlaying}")
    }

    override fun onDestroy() {
        cancelConv = true
        main.removeCallbacksAndMessages(null)
        try { convDlg?.dismiss() } catch (_: Exception) {}
        releasePlayer()
        Subs.dir(this).listFiles()?.forEach { it.delete() }
        PlayerHost.log("activity destroy")
        super.onDestroy()
    }
}
