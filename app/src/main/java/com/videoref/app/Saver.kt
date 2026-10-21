package com.videoref.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 받은 파일을 휴대폰 '동영상/VideoRef' 에 넣는다(갤러리에 보임). 이름은 제목, 겹치면 (2),(3)… */
object Saver {
    const val REL_DIR = "Movies/VideoRef/"

    fun cleanName(raw: String): String {
        // 안드로이드 저장소가 거부하는 문자만 바꾼다. 한글·공백은 그대로.
        var s = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.')
        if (s.length > 120) s = s.substring(0, 120).trim()
        return s.ifEmpty { "video" }
    }

    private fun mimeOf(ext: String) = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        else -> "video/*"
    }

    private fun existsName(ctx: Context, name: String): Boolean {
        val c = ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(REL_DIR, name), null) ?: return false
        c.use { return it.count > 0 }
    }

    /** @return 실제로 저장된 파일 이름과 저장소 주소(미리보기·열기용) */
    fun save(ctx: Context, src: File, baseName: String): Pair<String, android.net.Uri> {
        val ext = src.extension.ifEmpty { "mp4" }
        val base = cleanName(baseName)
        var name = "$base.$ext"
        var n = 2
        while (existsName(ctx, name)) { name = "$base ($n).$ext"; n++ }
        FileLog.w("save src=${src.name} size=${src.length()} -> $REL_DIR$name")

        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(ext))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoRef")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException(T("저장소 항목 만들기 실패"))
        try {
            cr.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        // 저장소가 이름을 바꿨을 수 있으니 실제 이름을 다시 읽는다
        var real = name
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) real = it.getString(0) }
        FileLog.w("save done uri=$uri name=$real")
        return real to uri
    }
}
