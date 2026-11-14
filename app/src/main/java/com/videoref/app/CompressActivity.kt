package com.videoref.app

// v0.7.1 용량 줄이기 화면: 원본 정보(해상도·길이·크기) + [고화질 1080p][보통 720p][작게 480p](예상 크기) + [줄이고 저장]
// 방식: 1) 폰 변환 장치(Media3 Transformer, 하드웨어 H.264/AAC) — 빠르고 배터리 적게 씀
//       2) 1이 실패하면(폰이 못 여는 형식 등) 내장 ffmpeg(libx264/aac)로 대신
// 해상도는 짧은 변 기준(세로 영상도 맞게). 원본이 더 작으면 해상도는 그대로 두고 비트레이트만 낮춤.
// 화면을 연 동안만 돈다(화면 켜짐 유지) — 포그라운드 서비스를 쓰지 않으므로 플레이 콘솔 추가 신고 없음.
// 저장: '동영상/VideoRef/<원래 이름> (줄임).mp4', 원본은 그대로. 로그: videoref.log 'compress ' 줄
// adb 시험(시험용 빌드): am start -n <패키지>/com.videoref.app.MainActivity --es compress <앱 files 폴더 파일이름>

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.util.concurrent.Executors
import com.google.android.material.R as M

@UnstableApi
class CompressActivity : AppCompatActivity() {
    companion object {
        fun start(c: Context, uri: Uri, name: String) {
            FileLog.w("compress open $uri name=$name")
            c.startActivity(Intent(c, CompressActivity::class.java).setData(uri).putExtra("name", name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        private const val AUDIO_BPS = 128_000
    }

    /** 단계: 짧은 변 높이, 영상 비트레이트 */
    private class Level(val label: String, val side: Int, val vbps: Int)
    private val levels by lazy { listOf(Level(T("고화질 (1080p)"), 1080, 6_000_000), Level(T("보통 (720p)"), 720, 3_000_000), Level(T("작게 (480p)"), 480, 1_500_000)) }

    private lateinit var src: Uri
    private lateinit var name: String
    private lateinit var info: TextView
    private lateinit var est: TextView
    private lateinit var goBtn: MaterialButton
    private var sel = 1
    private var w = 0; private var h = 0; private var durMs = 0L; private var size = 0L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false
    private var transformer: Transformer? = null

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun c(id: Int) = getColor(id)

    // 0.7.0 과 같음: 화면은 매번 새로 그리므로 조각 상태 되살리기는 건너뜀
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.remove("android:viewHierarchyState")
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = intent.data ?: run { finish(); return }
        name = intent.getStringExtra("name") ?: src.lastPathSegment ?: "video.mp4"
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(c(R.color.vr_bg)) }
        Ui.applyInsets(panel, bottom = true)
        panel.addView(TextView(this).apply { text = T("용량 줄이기"); setTextAppearance(M.style.TextAppearance_Material3_HeadlineSmall); setTextColor(c(R.color.vr_text)) })
        panel.addView(TextView(this).apply { text = name; setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(c(R.color.vr_text)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(12), 0, 0) })
        info = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(c(R.color.vr_text2)); setPadding(0, dp(4), 0, 0); text = T("불러오는 중…") }
        panel.addView(info)
        val group = MaterialButtonToggleGroup(this).apply { orientation = LinearLayout.VERTICAL; isSingleSelection = true; isSelectionRequired = true }
        val ids = levels.map { lv ->
            val b = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply { id = View.generateViewId(); isSaveEnabled = false; text = lv.label; Ui.fit(this, 10, 15) }
            group.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, dp(52))); b.id
        }
        group.check(ids[sel])
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) { sel = ids.indexOf(id); updateEst(); FileLog.w("compress level=${levels[sel].side}") } }
        panel.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        est = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(c(R.color.vr_text)); setPadding(dp(4), dp(10), dp(4), 0) }
        panel.addView(est)
        panel.addView(TextView(this).apply {
            text = T("원본은 그대로 두고 새 파일로 저장합니다. 줄이는 동안 이 화면을 열어 두세요.")
            setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)); setPadding(dp(4), dp(6), dp(4), 0)
        })
        goBtn = MaterialButton(this).apply { text = T("줄이고 저장"); setIconResource(R.drawable.ic_download); cornerRadius = dp(26); isEnabled = false; setOnClickListener { go() } }
        panel.addView(goBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })
        setContentView(ScrollView(this).apply { setBackgroundColor(c(R.color.vr_bg)); addView(panel) })
        io.execute { probe() }
    }

    /** 원본 해상도·길이·크기 읽기(배경 스레드) */
    private fun probe() {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, src)
            w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) { FileLog.w("compress probe fail ${e.javaClass.simpleName}: ${e.message}") } finally { try { r.release() } catch (_: Exception) {} }
        size = try { contentResolver.openAssetFileDescriptor(src, "r")?.use { it.length } ?: 0 } catch (_: Exception) { 0 }
        FileLog.w("compress probe ${w}x$h dur=${durMs}ms size=$size")
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            val res = if (w > 0 && h > 0) "${w}×$h · " else ""
            // 폰이 못 읽는 형식(ogg 등)은 길이가 0 -> 길이 빼고 보임(11-06 실측 '0:00' 표시)
            info.text = T("원본: {0}", res + (if (durMs > 0) clock(durMs) + " · " else "") + mb(size))
            goBtn.isEnabled = true; updateEst()
        }
    }

    private fun short() = if (w > 0 && h > 0) minOf(w, h) else 0
    private fun estBytes(lv: Level) = if (durMs <= 0) 0L else (lv.vbps.toLong() + AUDIO_BPS) * durMs / 8000

    private fun updateEst() {
        val lv = levels[sel]; val e = estBytes(lv)
        if (e <= 0) { est.text = ""; return }
        var s = T("예상 크기: 약 {0}", mb(e))
        if (short() in 1..lv.side) s += "\n" + T("원본 해상도가 이보다 낮아 해상도는 그대로 둡니다.")
        if (size > 0 && e >= size * 9 / 10) s += "\n" + T("원본보다 크게 줄지 않을 수 있습니다.")
        est.text = s
    }

    private fun mb(b: Long) = "%.1fMB".format(b / 1048576.0)
    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    private lateinit var bar: LinearProgressIndicator
    private var dlg: androidx.appcompat.app.AlertDialog? = null
    private var t0 = 0L
    private lateinit var tmp: File

    private fun go() {
        val lv = levels[sel]
        cancel = false; t0 = System.currentTimeMillis()
        tmp = File(cacheDir, "compress_$t0").apply { mkdirs() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bar = LinearProgressIndicator(this).apply { isIndeterminate = true; max = 100; setPadding(dp(24), dp(8), dp(24), 0) }
        dlg = MaterialAlertDialogBuilder(this).setTitle(T("줄이는 중…")).setView(bar).setCancelable(false)
            .setNegativeButton(T("취소")) { _, _ -> cancel = true; transformer?.cancel(); FileLog.w("compress cancel by user"); finish(false, T("취소됨")) }.show()
        FileLog.w("compress start side=${lv.side} vbps=${lv.vbps} src=${w}x$h dur=${durMs}ms size=$size name=$name")
        startTransformer(lv)
    }

    // ---- 1) 폰 변환 장치 ----
    private fun startTransformer(lv: Level) {
        val out = File(tmp, "out.mp4")
        val effects = if (short() > lv.side) Effects(emptyList(), listOf(Presentation.createForShortSide(lv.side))) else Effects.EMPTY
        val item = EditedMediaItem.Builder(MediaItem.fromUri(src)).setEffects(effects).build()
        val enc = DefaultEncoderFactory.Builder(this).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(lv.vbps).build()).build()
        val tr = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, r: ExportResult) {
                    FileLog.w("compress hw done ${out.length()}B ${System.currentTimeMillis() - t0}ms vEnc=${r.videoEncoderName} aEnc=${r.audioEncoderName}")
                    io.execute { saveOut(out) }
                }
                override fun onError(composition: Composition, r: ExportResult, e: ExportException) {
                    if (cancel) return
                    FileLog.w("compress hw fail code=${e.errorCodeName} ${e.message} cause=${e.cause?.javaClass?.simpleName}: ${e.cause?.message} -> ffmpeg")
                    out.delete(); runOnUiThread { bar.isIndeterminate = true }
                    io.execute { ffmpeg(lv) }
                }
            }).build()
        transformer = tr
        try { tr.start(item, out.path); pollProgress() }
        catch (e: Exception) { FileLog.w("compress hw start fail ${e.message} -> ffmpeg"); io.execute { ffmpeg(lv) } }
    }

    private val holder = ProgressHolder()
    private fun pollProgress() {
        val tr = transformer ?: return
        if (cancel || isDestroyed) return
        if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) { bar.isIndeterminate = false; bar.setProgressCompat(holder.progress, true) }
        if (tr.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) main.postDelayed({ pollProgress() }, 500)
    }

    // ---- 2) 내장 ffmpeg(배경 스레드) ----
    private fun ffmpeg(lv: Level) {
        transformer = null
        if (cancel) return
        try {
            val (inp, _) = Ffmpeg.input(this, src, tmp, name)
            val out = File(tmp, "out_ff.mp4")
            val args = mutableListOf("-i", inp.path, "-map", "0:v:0", "-map", "0:a:0?")
            if (short() > lv.side) args += listOf("-vf", "scale='if(gt(iw,ih),-2,${lv.side})':'if(gt(iw,ih),${lv.side},-2)'")
            args += listOf("-c:v", "libx264", "-preset", "veryfast", "-b:v", "${lv.vbps / 1000}k", "-maxrate", "${lv.vbps / 1000}k", "-bufsize", "${lv.vbps / 500}k",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "${AUDIO_BPS / 1000}k", "-movflags", "+faststart", out.path)
            val r = Ffmpeg.run(this, "compress", args, { p -> runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat((p * 100).toInt(), true) } }, { cancel })
            if (cancel) return
            if (r.code != 0 || out.length() == 0L) throw IllegalStateException(r.tail.lastOrNull { it.isNotBlank() } ?: "ffmpeg ${r.code}")
            FileLog.w("compress ffmpeg done ${out.length()}B ${System.currentTimeMillis() - t0}ms")
            saveOut(out)
        } catch (e: Exception) {
            FileLog.e("compress fail", e)
            runOnUiThread { finish(false, T("줄이지 못했습니다") + "\n" + (e.message ?: "").take(200)) }
        }
    }

    /** 결과 저장(배경 스레드) */
    private fun saveOut(out: File) {
        if (cancel) return
        try {
            val base = name.substringBeforeLast('.') + " (" + T("줄임") + ")"
            val saved = Saver.save(this, out, base).first
            val sec = (System.currentTimeMillis() - t0) / 1000
            FileLog.w("compress saved $saved $size -> ${out.length()}B ${sec}s")
            val msg = T("저장했습니다: {0}", saved) + "\n" + mb(size) + " → " + mb(out.length()) + " · " + T("{0}초 걸림", sec)
            runOnUiThread { finish(true, msg) }
        } catch (e: Exception) {
            FileLog.e("compress save fail", e)
            runOnUiThread { finish(false, T("줄이지 못했습니다") + "\n" + (e.message ?: "").take(200)) }
        }
    }

    private fun finish(ok: Boolean, msg: String) {
        main.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { dlg?.dismiss() } catch (_: Exception) {}
        if (::tmp.isInitialized) io.execute { tmp.deleteRecursively() }
        if (!isDestroyed) MaterialAlertDialogBuilder(this).setTitle(T("용량 줄이기")).setMessage(msg).setPositiveButton(T("확인"), null).show()
        FileLog.w("compress end ok=$ok")
    }

    override fun onDestroy() {
        cancel = true; transformer?.cancel(); main.removeCallbacksAndMessages(null)
        io.execute { if (::tmp.isInitialized) tmp.deleteRecursively() }; io.shutdown()
        FileLog.w("compress close"); super.onDestroy()
    }
}
