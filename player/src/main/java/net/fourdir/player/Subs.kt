package net.fourdir.player

// 바깥 자막 도우미 v0.2.0
//   mime     : 확장자 -> Media3 자막 형식
//   sibling  : 영상과 같은 이름의 자막 파일 찾기(file:// 는 같은 폴더, content:// 는 저장소의 같은 폴더) -> 캐시로 복사
//   shift    : 자막 시간 맞추기 — 원본 글에서 시각만 offset 만큼 밀어 새 파일로(SRT·VTT·ASS/SSA)

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import java.io.File

object Subs {
    val EXT = setOf("srt", "vtt", "ass", "ssa", "ttml")

    fun mime(ext: String): String = when (ext.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun dir(ctx: Context) = File(ctx.cacheDir, "player_subs").apply { mkdirs() }

    /** 같은 이름(확장자만 다름)의 자막 -> 캐시 파일. 없으면 null */
    fun sibling(ctx: Context, video: Uri): File? {
        try {
            if (video.scheme == "file") {
                val f = File(video.path ?: return null)
                val base = f.nameWithoutExtension
                val hit = f.parentFile?.listFiles()?.firstOrNull { it.extension.lowercase() in EXT && it.nameWithoutExtension.startsWith(base) } ?: return null
                return hit.copyTo(File(dir(ctx), "sib_${System.currentTimeMillis()}.${hit.extension.lowercase()}"), true)
            }
            val cr = ctx.contentResolver
            var rel: String? = null; var name: String? = null
            cr.query(video, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) { rel = it.getString(0); name = it.getString(1) }
            }
            val base = name?.substringBeforeLast('.') ?: return null
            val files = MediaStore.Files.getContentUri("external")
            cr.query(files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(rel, "$base%"), null)?.use {
                while (it.moveToNext()) {
                    val n = it.getString(1); val ext = n.substringAfterLast('.', "").lowercase()
                    if (ext !in EXT) continue
                    val out = File(dir(ctx), "sib_${System.currentTimeMillis()}.$ext")
                    cr.openInputStream(android.content.ContentUris.withAppendedId(files, it.getLong(0)))?.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    PlayerHost.log("subs sibling found $n")
                    return out
                }
            }
        } catch (e: Exception) { PlayerHost.log("subs sibling fail ${e.javaClass.simpleName}: ${e.message}") }
        return null
    }

    private val SRT = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")
    private val VTT_SHORT = Regex("""(?<![\d:])(\d{2}):(\d{2})\.(\d{3})(?![\d:])""")
    private val ASS = Regex("""(?<![\d:])(\d):(\d{2}):(\d{2})\.(\d{2})(?!\d)""")

    /** 원본 자막 글에서 시각만 offsetMs 만큼 밀어 새 파일로. 0초보다 앞으로 가면 0초 */
    fun shift(ctx: Context, orig: File, ext: String, offsetMs: Long): File {
        val txt = orig.readText(Charsets.UTF_8)
        fun clamp(ms: Long) = ms.coerceAtLeast(0)
        val out = when (ext.lowercase()) {
            "ass", "ssa" -> ASS.replace(txt) { m ->
                val v = clamp(((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong() * 10 + offsetMs)
                "%d:%02d:%02d.%02d".format(v / 3600000, v / 60000 % 60, v / 1000 % 60, v % 1000 / 10)
            }
            else -> {
                val sep = if (ext.lowercase() == "srt") "," else "."
                val a = SRT.replace(txt) { m ->
                    val v = clamp(((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong() + offsetMs)
                    "%02d:%02d:%02d%s%03d".format(v / 3600000, v / 60000 % 60, v / 1000 % 60, sep, v % 1000)
                }
                if (ext.lowercase() == "vtt") VTT_SHORT.replace(a) { m ->
                    val v = clamp((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + m.groupValues[3].toLong() + offsetMs)
                    "%02d:%02d:%02d.%03d".format(v / 3600000, v / 60000 % 60, v / 1000 % 60, v % 1000)
                } else a
            }
        }
        val f = File(dir(ctx), "shift_${System.currentTimeMillis()}.$ext")
        f.writeText(out, Charsets.UTF_8)
        return f
    }
}
