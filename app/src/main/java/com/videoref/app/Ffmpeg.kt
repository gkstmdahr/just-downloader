package com.videoref.app

// v0.6.6 내장 ffmpeg 직접 실행 — 자르기·재생기 변환에 씀.
// 실행 파일 = 앱 라이브러리 폴더의 libffmpeg.so (youtubedl-android ffmpeg 묶음). 필요한 .so 는 Engine.ensure 가 풀어 둔
//   noBackupFilesDir/youtubedl-android/packages/{python,ffmpeg}/usr/lib -> LD_LIBRARY_PATH 로 넘김(yt-dlp 가 부를 때와 같은 방식).
// 진행률: -progress pipe:1 의 out_time_us 줄 / 길이: 출력의 'Duration:' 줄. 취소: 진행 줄마다 확인해 프로세스 종료.
// 로그: videoref.log 의 'ffmpeg ' 줄(명령·종료 코드·마지막 출력 15줄)

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import net.fourdir.player.PlayerHost
import java.io.File

object Ffmpeg {
    class Result(val code: Int, val tail: List<String>)

    fun run(ctx: Context, tag: String, args: List<String>, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
        Engine.ensure(ctx)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val cmd = listOf(bin.absolutePath, "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1", "-nostats") + args
        FileLog.w("ffmpeg[$tag] cmd: ${cmd.drop(1).joinToString(" ")}")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = "${pk}/python/usr/lib:${pk}/ffmpeg/usr/lib"
        val t0 = System.currentTimeMillis()
        val p = pb.start()
        val tail = ArrayDeque<String>()
        var durUs = 0L
        var killed = false
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (durUs == 0L) Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""").find(line)?.let { m ->
                    durUs = ((m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60) * 1_000_000 + (m.groupValues[3].toDouble() * 1_000_000).toLong())
                }
                if (line.startsWith("out_time_us=")) {
                    val us = line.substringAfter('=').toLongOrNull() ?: 0
                    if (durUs > 0 && us > 0) progress((us.toFloat() / durUs).coerceIn(0f, 1f))
                    if (cancelled()) { killed = true; p.destroy(); break }
                    continue
                }
                if (line.contains('=') && !line.contains(' ')) continue   // 그 밖의 진행 줄(frame=, speed= 등)
                tail.addLast(line); if (tail.size > 15) tail.removeFirst()
            }
        }
        val code = if (killed) -9 else p.waitFor()
        FileLog.w("ffmpeg[$tag] exit=$code killed=$killed ${System.currentTimeMillis() - t0}ms tail=\n  ${tail.joinToString("\n  ")}")
        return Result(code, tail.toList())
    }

    /** 입력을 ffmpeg 가 읽을 파일로: 저장소 경로를 바로 읽을 수 있으면 그대로, 아니면 tmp 로 복사. @return 파일, 복사했는지 */
    fun input(ctx: Context, uri: Uri, tmp: File, name: String): Pair<File, Boolean> {
        if (uri.scheme == "file") return File(uri.path!!) to false
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (it.moveToFirst()) { val f = File(it.getString(0) ?: ""); if (f.canRead()) { FileLog.w("ffmpeg input direct ${f.path}"); return f to false } }
            }
        } catch (e: Exception) { FileLog.w("ffmpeg input path query fail ${e.message}") }
        val f = File(tmp, "in." + name.substringAfterLast('.', "mp4").lowercase())
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 20) } }
        FileLog.w("ffmpeg input copied ${f.length()}B")
        return f to true
    }

    /** 재생기 묶음에 넘기는 변환기: 못 여는 파일 -> H.264/AAC MP4 (소리만 문제면 영상은 복사) */
    object PlayConverter : PlayerHost.Converter {
        override fun toPlayable(ctx: Context, src: Uri, audioOnly: Boolean, progress: (Float) -> Unit, cancelled: () -> Boolean): File? {
            val dir = File(ctx.cacheDir, "play_conv").apply { deleteRecursively(); mkdirs() }   // 지난번 남은 것 정리
            val (inp, copied) = input(ctx, src, dir, src.lastPathSegment ?: "in.mp4")
            val out = File(dir, "play_${System.currentTimeMillis()}.mp4")
            val maps = listOf("-map", "0:v:0?", "-map", "0:a:0?")
            val audio = listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2")
            val full = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p")
            try {
                if (audioOnly) {
                    val r = run(ctx, "play-a", listOf("-i", inp.path) + maps + listOf("-c:v", "copy") + audio + listOf("-movflags", "+faststart", out.path), progress, cancelled)
                    if (r.code == 0 && out.length() > 0) return out
                    if (r.code == -9) return null
                }
                val r = run(ctx, "play-v", listOf("-i", inp.path) + maps + full + audio + listOf("-movflags", "+faststart", out.path), progress, cancelled)
                return if (r.code == 0 && out.length() > 0) out else { out.delete(); null }
            } finally { if (copied) inp.delete() }
        }
    }
}
