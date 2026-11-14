package com.videoref.app

// v0.6.6 자르기 화면: 위 = 미리보기(재생기 묶음의 PlayerView), 아래 = 구간 막대(시작·끝 손잡이) + [지금 위치를 시작/끝] + [구간 재생]
//   + 방식 [빠르게(화질 그대로)] [정확하게(다시 만들기)] + [저장]
// 빠르게 = ffmpeg -c copy: 화질 손실 없음·몇 초면 끝. 단 시작이 앞쪽 키프레임으로 맞춰져 1~2초 어긋날 수 있음. 원래 형식 그대로.
// 정확하게 = H.264/AAC 로 다시 만들기(소리 파일은 MP3): 고른 지점 그대로, 길이만큼 시간 걸림. MP4.
// 저장: 영상 '동영상/VideoRef', 소리 '음악/VideoRef', 이름 '<원래 이름> (잘라냄)'. 로그: videoref.log 'trim ' 줄
// adb 시험(시험용 빌드): am start -n <패키지>/com.videoref.app.TrimActivity -d <content 주소> --es name <이름>

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import java.io.File
import java.util.concurrent.Executors
import com.google.android.material.R as M

@UnstableApi
class TrimActivity : AppCompatActivity() {
    companion object {
        fun start(c: Context, uri: Uri, name: String) {
            FileLog.w("trim open $uri name=$name")
            c.startActivity(Intent(c, TrimActivity::class.java).setData(uri).putExtra("name", name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        private val AUDIO_EXT = setOf("mp3", "m4a", "ogg", "opus", "aac", "wav", "flac")
        private val COPY_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "ts", "flv") + AUDIO_EXT
    }

    private lateinit var src: Uri
    private lateinit var name: String
    private lateinit var player: ExoPlayer
    private lateinit var slider: RangeSlider
    private lateinit var info: TextView
    private lateinit var saveBtn: MaterialButton
    private var exact = false
    private var durMs = 0L
    private var stopAtMs = -1L
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun c(id: Int) = getColor(id)

    // 0.7.0: 화면 조각 번호를 실행 때마다 새로 매겨서, 꺼졌다 되살아날 때 저장된 상태가 엉뚱한 조각에 붙어 앱이 꺼졌음(11-06 실측)
    //        -> 화면은 매번 새로 그리므로 조각 상태 되살리기는 건너뜀
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.remove("android:viewHierarchyState")
        super.onRestoreInstanceState(savedInstanceState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = intent.data ?: run { finish(); return }
        name = intent.getStringExtra("name") ?: src.lastPathSegment ?: "video.mp4"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c(R.color.vr_bg)) }
        Ui.applyInsets(col, bottom = true)
        val pv = PlayerView(this).apply { setShowNextButton(false); setShowPreviousButton(false); setBackgroundColor(android.graphics.Color.BLACK) }
        col.addView(pv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(16)) }
        panel.addView(TextView(this).apply { text = name; setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(c(R.color.vr_text)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(c(R.color.vr_text2)); setPadding(0, dp(4), 0, 0); text = T("불러오는 중…") }
        panel.addView(info)
        slider = RangeSlider(this).apply { valueFrom = 0f; valueTo = 1f; setValues(0f, 1f); isEnabled = false; setLabelFormatter { v -> clock((v * 1000).toLong()) } }
        panel.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // [지금을 시작][지금을 끝][구간 재생]
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(t: String, f: () -> Unit) = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply { text = t; Ui.fit(this, 9, 13); setPadding(dp(4), 0, dp(4), 0); setOnClickListener { f() } }
        row.addView(small(T("지금을 시작")) { setEdge(0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(small(T("지금을 끝")) { setEdge(1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(small(T("구간 재생")) { playRange() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        panel.addView(row)
        // 방식
        val mode = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val fastB = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); isSaveEnabled = false; text = T("빠르게 (화질 그대로)"); Ui.fit(this, 9, 13) }
        val exactB = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); isSaveEnabled = false; text = T("정확하게"); Ui.fit(this, 9, 13) }
        mode.addView(fastB, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); mode.addView(exactB, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        mode.check(fastB.id)
        val note = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)); setPadding(dp(4), dp(6), dp(4), 0) }
        fun noteText() { note.text = if (exact) T("고른 지점 그대로 자릅니다. 다시 만드느라 영상 길이만큼 시간이 걸릴 수 있습니다.") else T("몇 초면 끝나고 화질도 그대로입니다. 시작이 1~2초 앞당겨질 수 있습니다.") }
        noteText()
        mode.addOnButtonCheckedListener { _, id, checked -> if (checked) { exact = id == exactB.id; noteText(); FileLog.w("trim mode exact=$exact") } }
        panel.addView(mode, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        panel.addView(note)
        saveBtn = MaterialButton(this).apply { text = T("자르고 저장"); setIconResource(R.drawable.ic_download); cornerRadius = dp(26); isEnabled = false; setOnClickListener { save() } }
        panel.addView(saveBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })
        col.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(col)

        player = ExoPlayer.Builder(this).build()
        pv.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY && durMs <= 0 && player.duration > 0) {
                    durMs = player.duration
                    slider.valueTo = durMs / 1000f; slider.setValues(0f, durMs / 1000f); slider.isEnabled = true; saveBtn.isEnabled = true
                    FileLog.w("trim ready dur=${durMs}ms"); updateInfo()
                }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                // 미리보기가 안 돼도 자르기(ffmpeg)는 될 수 있음 -> 길이를 모르니 막대는 못 씀. 안내만
                FileLog.w("trim preview error ${e.errorCodeName} ${e.message}")
                info.text = T("미리보기를 할 수 없는 형식입니다.")
            }
        })
        player.setMediaItem(MediaItem.fromUri(src)); player.prepare()
        slider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            player.pause(); stopAtMs = -1; player.seekTo((v * 1000).toLong()); updateInfo()
        }
        // 구간 재생 중 끝 지점에서 멈춤 — 재생 중에만 0.2초마다 확인(멈추면 확인도 멈춤)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { if (playing) checkStop() }
        })
    }

    private fun checkStop() {
        if (isDestroyed || !player.isPlaying) return
        if (stopAtMs >= 0 && player.currentPosition >= stopAtMs) { player.pause(); stopAtMs = -1; return }
        slider.postDelayed({ checkStop() }, 200)
    }

    private fun setEdge(i: Int) {
        if (durMs <= 0) return
        val v = player.currentPosition / 1000f
        val vs = slider.values.toMutableList()
        vs[i] = v
        if (vs[0] > vs[1]) { if (i == 0) vs[1] = durMs / 1000f else vs[0] = 0f }
        slider.setValues(vs); updateInfo()
        FileLog.w("trim set ${if (i == 0) "start" else "end"}=${v}s")
    }

    private fun playRange() {
        if (durMs <= 0) return
        val (a, b) = slider.values
        player.seekTo((a * 1000).toLong()); stopAtMs = (b * 1000).toLong(); player.play()
    }

    private fun updateInfo() {
        val (a, b) = slider.values
        info.text = T("시작 {0} · 끝 {1} · 길이 {2}", clock((a * 1000).toLong()), clock((b * 1000).toLong()), clock(((b - a) * 1000).toLong()))
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000; val t = (ms % 1000) / 100
        return if (s >= 3600) "%d:%02d:%02d.%d".format(s / 3600, s % 3600 / 60, s % 60, t) else "%d:%02d.%d".format(s / 60, s % 60, t)
    }

    private fun save() {
        val (a, b) = slider.values
        if (b - a < 0.5f) { Toast.makeText(this, T("구간이 너무 짧습니다"), Toast.LENGTH_SHORT).show(); return }
        player.pause()
        val ext = name.substringAfterLast('.', "mp4").lowercase()
        val audio = ext in AUDIO_EXT
        val outExt = if (exact) (if (audio) "mp3" else "mp4") else (if (ext in COPY_EXT) ext else "mkv")
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true; max = 100; setPadding(dp(24), dp(8), dp(24), 0) }
        cancel = false
        val dlg = MaterialAlertDialogBuilder(this).setTitle(T("자르는 중…")).setView(bar).setCancelable(false)
            .setNegativeButton(T("취소")) { _, _ -> cancel = true; FileLog.w("trim cancel by user") }.show()
        val ss = "%.3f".format(java.util.Locale.US, a); val dur = "%.3f".format(java.util.Locale.US, b - a)
        FileLog.w("trim start ${ss}s len=${dur}s exact=$exact ext=$ext -> $outExt name=$name")
        io.execute {
            val t0 = System.currentTimeMillis()
            val tmp = File(cacheDir, "trim_$t0").apply { mkdirs() }
            var msg: String
            try {
                val (inp, _) = Ffmpeg.input(this, src, tmp, name)
                val out = File(tmp, "out.$outExt")
                val args = mutableListOf("-ss", ss, "-i", inp.path, "-t", dur)
                if (!exact) args += listOf("-map", "0:v?", "-map", "0:a?", "-c", "copy", "-avoid_negative_ts", "make_zero")
                else if (audio) args += listOf("-vn", "-c:a", "libmp3lame", "-q:a", "2")
                else args += listOf("-map", "0:v:0?", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k")
                if (outExt == "mp4" || outExt == "mov" || outExt == "m4a") args += listOf("-movflags", "+faststart")
                args += out.path
                val r = Ffmpeg.run(this, "trim", args, { p -> runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat((p * 100).toInt(), true) } }, { cancel })
                if (cancel) throw InterruptedException(T("취소됨"))
                if (r.code != 0 || out.length() == 0L) throw IllegalStateException(r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg ${r.code}")
                val base = name.substringBeforeLast('.') + " (" + T("잘라냄") + ")"
                val saved = if (outExt in AUDIO_EXT) AudioTool.save(this, out, base).first else Saver.save(this, out, base).first
                msg = T("저장했습니다: {0}", saved)
                FileLog.w("trim done $saved ${out.length()}B ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                FileLog.e("trim fail", e)
                msg = if (cancel) T("취소됨") else T("자르지 못했습니다") + "\n" + (e.message ?: "").take(200)
            } finally { tmp.deleteRecursively() }
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (!isDestroyed) MaterialAlertDialogBuilder(this).setTitle(T("자르기")).setMessage(msg).setPositiveButton(T("확인"), null).show()
            }
        }
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() { cancel = true; if (::player.isInitialized) player.release(); io.shutdown(); FileLog.w("trim close"); super.onDestroy() }
}
