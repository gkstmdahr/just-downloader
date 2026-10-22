package com.videoref.app

// just downloader 모바일 v0.5.3 — 자동 브라우저 전환·연결 재시도·화면 배치 개선
//   / v0.4.x 하단 탭·다크 모드·사이트 실측 / 머티리얼 화면 개편·영상 위 받기 버튼 / 4단계: 설정·기록 보내기 / 3단계: 내장 브라우저 감지 -> 받기 연결 (2단계: 주소 -> yt-dlp 받기 -> 동영상/VideoRef 저장 -> 진행률 알림)
// 로그: /sdcard/Android/data/<패키지>/files/videoref.log  (adb pull 로 가져가 분석)
//   v0.6.0 패키지: 플레이판 net.fourdir.justdownloader / 사이트판 net.fourdir.justdownloader.full (Edition.kt)

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val APP_VER = "0.6.3"
const val CH_PROGRESS = "progress"
const val CH_RESULT = "result"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.open(this)
        // v0.5.0: 시안 색(하늘색 톤)을 쓰려고 배경화면 동적 색은 끔. 색은 res/values(-night)/colors.xml
        Prefs.applyTheme(this)   // v0.4.3 다크 모드
        I18n.load(this)          // v0.5.0 다국어
        FileLog.w("===== VideoRef v$APP_VER edition=${Edition.NAME} pkg=$packageName start android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString()}")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, T("받는 중"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, T("받기 결과"), NotificationManager.IMPORTANCE_DEFAULT))
    }
}

/** 파일 로그. 사람이 아니라 분석용 — 흐름·입력값·분기·에러를 전부 남긴다. 5MB 넘으면 .old 로 넘긴다. */
object FileLog {
    private var file: File? = null
    private var writer: FileWriter? = null
    private val tf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun open(ctx: Context) {
        val f = File(ctx.getExternalFilesDir(null), "videoref.log")
        if (f.length() > 5L * 1024 * 1024) { File(f.path + ".old").delete(); f.renameTo(File(f.path + ".old")) }
        file = f
        writer = FileWriter(f, true)
    }

    fun path(): String = file?.absolutePath ?: "(없음)"

    @Synchronized
    fun clear() {
        val f = file ?: return
        try { writer?.close() } catch (_: Exception) {}
        f.delete(); File(f.path + ".old").delete()
        writer = FileWriter(f, true)
        w("===== log cleared by user (v$APP_VER)")
    }

    @Synchronized
    fun w(msg: String) {
        val line = "${tf.format(Date())} [${Thread.currentThread().name}] $msg"
        Log.i("VideoRef", line)
        try { writer?.apply { write(line); write("\n"); flush() } } catch (_: Exception) {}
    }

    fun e(msg: String, t: Throwable) = w("ERROR $msg :: ${t.javaClass.name}: ${t.message}\n${Log.getStackTraceString(t)}")
}

/** yt-dlp(파이썬 포함)·ffmpeg 준비. 첫 실행 때 압축 풀기로 몇 초 걸린다. */
object Engine {
    @Volatile private var ready = false

    @Synchronized
    fun ensure(ctx: Context) {
        if (ready) return
        val t0 = System.currentTimeMillis()
        FileLog.w("engine init start nativeLibDir=${ctx.applicationInfo.nativeLibraryDir}")
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
        FileLog.w("engine init done ${System.currentTimeMillis() - t0}ms ytdlp=${YoutubeDL.getInstance().versionName(ctx) ?: "내장판"}")
        autoUpdate(ctx)
    }

    /** v0.0.2: 내장 yt-dlp 는 금방 낡아 사이트가 깨진다 -> 7일에 한 번 최신판 확인(실패해도 받기는 계속). */
    private fun autoUpdate(ctx: Context) {
        // v0.6.0: 플레이판은 플레이 밖에서 실행 코드를 받으면 안 됨 -> 앱에 들어 있는 판만 씀(앱 업데이트 때 같이 바뀜)
        if (Edition.PLAY) { FileLog.w("engine auto-update off (play edition)"); return }
        val sp = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val last = sp.getLong("lastCheck", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 7L * 24 * 3600 * 1000) { FileLog.w("engine auto-update skip (last ${(now - last) / 3600000}h ago)"); return }
        update(ctx, "auto")
    }

    /** @return 사람이 읽을 결과 문구 */
    fun update(ctx: Context, why: String): String {
        val t0 = System.currentTimeMillis()
        val before = versionText(ctx)
        if (Edition.PLAY) { FileLog.w("engine update($why) refused (play edition)"); return T("이미 최신: {0}", before) }
        return try {
            val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            ctx.getSharedPreferences("engine", Context.MODE_PRIVATE).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val after = versionText(ctx)
            FileLog.w("engine update($why) status=$st before=$before after=$after ${System.currentTimeMillis() - t0}ms")
            if (st == YoutubeDL.UpdateStatus.DONE) T("최신으로 바꿈: {0}", after) else T("이미 최신: {0}", after)
        } catch (e: Exception) {
            FileLog.e("engine update($why) fail before=$before", e)
            T("확인 실패: {0}", e.message)
        }
    }

    fun versionText(ctx: Context): String = YoutubeDL.getInstance().versionName(ctx) ?: T("내장판")
}
