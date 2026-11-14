package com.videoref.app

// just downloader 모바일 v0.7.3 — 좁은 단추에서 잘리던 번역 줄임(선택 삭제 등, 독·불·러·스·포·영)
// v0.7.2 — 여러 주소 한 번에 받기(홈 주소칸·붙여넣기, 최대 20개, 확인 창), 받은 영상 찾기(제목)
// v0.7.1 — 용량 줄이기(CompressActivity: 폰 하드웨어 인코더, 실패하면 내장 ffmpeg) — 받은 영상·최근·재생기 메뉴
// v0.7.0 —재생기 보강(player 모듈 v0.2.0: 손짓·이어 보기·잠금·화면 맞춤·회전·A-B 반복·소리 키우기·무음 건너뛰기·
//   바깥 자막·자막 시간/모양·화면 캡처·영상 정보·목록 이전/다음·더보기에 자르기/공유/소리만 저장·작은 창·뒤에서 재생)
//   / v0.6.7 — (75: 74 는 10/29 판을 플레이 임시 버전에 올려 번호가 소진돼 번호만 올림, 내용은 아래 0.6.6 전부)
//   v0.6.6 — 패트리온 바로 브라우저, 설정 '브라우저 로그인 지우기', 홈 공유 단추, 소리만 저장(MP3),
//   홈 [영상][소리만] 전환(주소·브라우저 받기 모두 MP3 로 바로 받기), 결과 알림 줄이기(앱 보는 중엔 안 띄움·브라우저 자동 전환 때 실패 알림 지움),
//   앱 안 재생(player 모듈, Media3 + 못 여는 형식은 ffmpeg 로 MP4 변환), 자르기(TrimActivity: 빠르게=-c copy / 정확하게=다시 만들기)
//   / v0.6.5 — 틱톡 받기(쿠키 넘김), 홈 '받을 수 있는 사이트' 창, 모바일 데이터 한 번만 묻기,
//   틱톡·페이스북·레딧 바로 브라우저로·스토어 넘김 막기, 홈 실패 기록 지우기
//   / v0.6.4 —내장 yt-dlp 2026.10.26 교체(인스타 주소 받기), 인스타 조각 주소 받기 막음
//   / v0.5.3 자동 브라우저 전환·연결 재시도·화면 배치 개선
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

const val APP_VER = "0.7.4"
/** v0.6.4: res/raw/ytdlp 에 넣은 yt-dlp 정식판(깃허브 릴리스, sha256 e51e69e8…). 바꾸면 이 값도 바꿀 것. */
const val BUNDLED_YTDLP = "2026.10.26"
const val CH_PROGRESS = "progress"
const val CH_RESULT = "result"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.open(this)
        // v0.6.6: 앱 화면(홈·브라우저·설정)이 하나라도 보이면 받기 결과 알림을 띄우지 않음 — 화면 카드로 이미 보임
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { Visible.n++ }
            override fun onActivityStopped(a: android.app.Activity) { Visible.n = (Visible.n - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // v0.5.0: 시안 색(하늘색 톤)을 쓰려고 배경화면 동적 색은 끔. 색은 res/values(-night)/colors.xml
        Prefs.applyTheme(this)   // v0.4.3 다크 모드
        I18n.load(this)          // v0.5.0 다국어
        // v0.6.6 재생기 묶음(player 모듈)에 기록·문구·변환기 넘김 — 모듈은 앱 코드를 모름
        net.fourdir.player.PlayerHost.log = { FileLog.w("player| $it") }
        net.fourdir.player.PlayerHost.text = { T(it) }
        net.fourdir.player.PlayerHost.converter = Ffmpeg.PlayConverter
        net.fourdir.player.PlayerHost.picturesDir = "Pictures/VideoRef"
        net.fourdir.player.PlayerHost.actions = listOf(   // 재생 화면 더보기 메뉴에 붙는 앱 기능
            net.fourdir.player.PlayerHost.Action({ T("자르기") }) { a, u, t -> TrimActivity.start(a, u, t ?: u.lastPathSegment ?: "video.mp4") },
            net.fourdir.player.PlayerHost.Action({ T("용량 줄이기") }) { a, u, t -> CompressActivity.start(a, u, t ?: u.lastPathSegment ?: "video.mp4") },
            net.fourdir.player.PlayerHost.Action({ T("공유") }) { a, u, _ -> AudioTool.share(a, u) },
            net.fourdir.player.PlayerHost.Action({ T("소리만 저장 (MP3)") }) { a, u, t -> AudioTool.extract(a, u, t ?: u.lastPathSegment ?: "audio") },
        )
        FileLog.w("===== VideoRef v$APP_VER edition=${Edition.NAME} pkg=$packageName start android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} abi=${Build.SUPPORTED_ABIS.joinToString()}")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, T("받는 중"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, T("받기 결과"), NotificationManager.IMPORTANCE_DEFAULT))
    }
}

/** v0.6.6: 지금 보이는 앱 화면 수(0이면 앱이 뒤에 있음). 메인 스레드에서만 바뀜 */
object Visible { @Volatile var n = 0; val any get() = n > 0 }

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
        refreshBundled(ctx)
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
        FileLog.w("engine init done ${System.currentTimeMillis() - t0}ms ytdlp=${YoutubeDL.getInstance().versionName(ctx) ?: "내장판 $BUNDLED_YTDLP"}")
        autoUpdate(ctx)
    }

    /**
     * v0.6.4: 앱에 넣은 yt-dlp(res/raw/ytdlp, 라이브러리 것을 덮어씀)는 라이브러리가 '파일이 없을 때만' 풀어 넣는다.
     * 앱을 업데이트해도 예전 파일이 남으므로, 플레이판은 넣은 판이 바뀌면 한 번 지워 새 판을 풀게 한다.
     * (사이트판은 자동 업데이트가 있으니 건드리지 않음)
     */
    private fun refreshBundled(ctx: Context) {
        if (!Edition.PLAY) return
        val sp = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val had = sp.getString("bundledYtdlp", "")
        if (had == BUNDLED_YTDLP) return
        val f = File(ctx.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        val ok = !f.exists() || f.delete()
        FileLog.w("engine bundled yt-dlp change $had -> $BUNDLED_YTDLP oldFile=${f.length()}B delete=$ok")
        if (ok) sp.edit().putString("bundledYtdlp", BUNDLED_YTDLP).apply()
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
