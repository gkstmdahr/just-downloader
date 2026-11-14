package com.videoref.app

// v0.6.6 받은 영상 도구: 공유(홈·받은 영상 같이 씀), 소리만 저장(MP3)
// 소리만 저장: 영상을 임시 폴더로 복사 -> 앱에 든 yt-dlp(+ffmpeg, libmp3lame 포함)로 file:// 주소에서 소리만 뽑아 MP3 -> '음악/VideoRef' 에 저장.
//   MP3 변환이 실패하면 원래 소리 형식 그대로(다시 인코딩 없음, 보통 m4a) 한 번 더 시도.
// 로그: videoref.log 의 'audio ' 줄

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

object AudioTool {
    const val AUDIO_DIR = "Music/VideoRef/"
    private val io = Executors.newSingleThreadExecutor()

    fun share(a: Activity, uri: Uri) {
        FileLog.w("share video $uri")
        try {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), T("영상 공유")))
        } catch (e: Exception) { FileLog.e("share fail", e); Toast.makeText(a, T("열 수 있는 앱이 없습니다"), Toast.LENGTH_SHORT).show() }
    }

    /** 받은 영상에서 소리만 MP3 로 저장. 진행 중엔 닫을 수 없는 창을 띄우고, 끝나면 결과를 알린다. */
    fun extract(a: Activity, uri: Uri, name: String) {
        val base = name.substringBeforeLast('.')
        val bar = LinearProgressIndicator(a).apply { isIndeterminate = true; setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), 0) }
        val dlg = MaterialAlertDialogBuilder(a).setTitle(T("소리만 저장")).setMessage(T("소리를 뽑는 중입니다…") + "\n" + base)
            .setView(bar).setCancelable(false).show()
        FileLog.w("audio start uri=$uri name=$name")
        io.execute {
            val t0 = System.currentTimeMillis()
            val tmp = File(a.cacheDir, "audio_${t0}").apply { mkdirs() }
            var msg: String
            try {
                Engine.ensure(a)
                val src = File(tmp, "in." + name.substringAfterLast('.', "mp4").lowercase())
                a.contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it, 1 shl 20) } }
                FileLog.w("audio copied ${src.length()}B ${System.currentTimeMillis() - t0}ms")
                val out = run("mp3", src, tmp) ?: run("best", src, tmp) ?: throw IllegalStateException(T("소리를 뽑지 못했습니다"))
                val saved = save(a, out, base).first
                msg = T("음악/VideoRef 에 저장했습니다: {0}", saved)
                FileLog.w("audio done $saved ${out.length()}B ${System.currentTimeMillis() - t0}ms")
            } catch (e: Exception) {
                FileLog.e("audio fail", e)
                msg = T("소리를 뽑지 못했습니다") + "\n" + (e.message ?: "").take(200)
            } finally { tmp.deleteRecursively() }
            a.runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (!a.isDestroyed) MaterialAlertDialogBuilder(a).setTitle(T("소리만 저장")).setMessage(msg).setPositiveButton(T("확인"), null).show()
            }
        }
    }

    /** @param fmt "mp3"(변환) 또는 "best"(원래 형식 그대로) @return 만든 파일, 실패면 null */
    private fun run(fmt: String, src: File, tmp: File): File? {
        tmp.listFiles()?.filter { it.name.startsWith("out.") }?.forEach { it.delete() }
        val req = YoutubeDLRequest("file://" + src.absolutePath)
            .addOption("--enable-file-urls").addOption("-x").addOption("--audio-format", fmt)
            .addOption("-o", File(tmp, "out.%(ext)s").absolutePath).addOption("--no-mtime").addOption("--no-playlist")
        if (fmt == "mp3") req.addOption("--audio-quality", "2")   // VBR 약 190kbps
        return try {
            val r = YoutubeDL.getInstance().execute(req, "audio_${System.currentTimeMillis()}", false, null)
            val f = tmp.listFiles()?.firstOrNull { it.name.startsWith("out.") && it.length() > 0 }
            FileLog.w("audio run fmt=$fmt exit=${r.exitCode} ${r.elapsedTime}ms out=${f?.name} ${f?.length()}B")
            f
        } catch (e: Exception) {
            FileLog.w("audio run fmt=$fmt fail ${e.message?.lines()?.takeLast(3)?.joinToString(" | ")?.take(300)}")
            null
        }
    }

    /** '음악/VideoRef' 에 저장(이름 겹치면 (2),(3)…). v0.6.6: 홈 '소리만' 받기도 씀. @return 저장된 이름과 주소 */
    fun save(a: android.content.Context, f: File, baseName: String): Pair<String, Uri> {
        val ext = f.extension
        val mime = when (ext) { "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "webm" -> "audio/webm"; else -> "audio/*" }
        val base = Saver.cleanName(baseName)
        val cr = a.contentResolver
        fun exists(n: String) = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(AUDIO_DIR, n), null)?.use { it.count > 0 } ?: false
        var name = "$base.$ext"; var n = 2
        while (exists(name)) { name = "$base ($n).$ext"; n++ }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VideoRef"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException(T("저장소 항목 만들기 실패"))
        try {
            cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 20) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        FileLog.w("audio save uri=$uri name=$name mime=$mime")
        return name to uri
    }
}
