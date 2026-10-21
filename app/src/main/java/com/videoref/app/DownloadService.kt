package com.videoref.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * 받기 전담 서비스. 화면을 꺼도 계속 받는다(포그라운드 서비스 + 진행률 알림).
 * 한 번에 하나씩 받는다(동시 연결을 낮게 — 데스크탑판 계정 블락 사고 교훈). 일이 없으면 스스로 종료.
 */
class DownloadService : Service() {
    companion object {
        const val ACT_ADD = "add"
        const val ACT_CANCEL = "cancel"
        const val NID_PROGRESS = 1
        const val MIN_FREE = 500L * 1024 * 1024   // 남은 공간이 이보다 적으면 시작 안 함
        // 조각 동시 받기 수는 설정(Prefs.fragments, 기본 8)

        fun add(ctx: Context, url: String, referer: String? = null, title: String? = null, ua: String? = null) {
            val i = Intent(ctx, DownloadService::class.java).setAction(ACT_ADD)
                .putExtra("url", url).putExtra("referer", referer).putExtra("title", title).putExtra("ua", ua)
            ctx.startForegroundService(i)
        }

        fun cancel(ctx: Context, id: String) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACT_CANCEL).putExtra("id", id))
        }
    }

    private val queue = LinkedBlockingQueue<Job>()
    @Volatile private var worker: Thread? = null
    @Volatile private var current: Job? = null
    private lateinit var nm: NotificationManager
    private var lastNotify = 0L
    private var resultNid = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        FileLog.w("service create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 포그라운드 시작은 매번 먼저 한다(늦으면 시스템이 앱을 죽임)
        val n = progressNotif(null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NID_PROGRESS, n)

        when (intent?.action) {
            ACT_ADD -> {
                val url = intent.getStringExtra("url").orEmpty()
                // v0.6.0: 플레이판 유튜브 차단 — 화면(Net.start)에서 막지만 여기서 한 번 더
                if (Edition.blocked(url, intent.getStringExtra("referer"))) {
                    FileLog.w("BLOCK youtube in service url=${url.take(200)}")
                    if (queue.isEmpty() && current == null) stopSelf()
                    return START_NOT_STICKY
                }
                val j = JobStore.add(url, intent.getStringExtra("referer"), intent.getStringExtra("title"), intent.getStringExtra("ua"))
                if (Prefs.testMode(this)) { j.dbgRate = Prefs.dbgRate; j.dbgMinFree = Prefs.dbgMinFree }
                FileLog.w("job add id=${j.id} url=$url referer=${j.referer} title=${j.title} ua=${j.ua?.take(60)}")
                queue.add(j)
                startWorker()
            }
            ACT_CANCEL -> {
                val id = intent.getStringExtra("id").orEmpty()
                val j = JobStore.find(id)
                FileLog.w("cancel request id=$id state=${j?.state}")
                if (j != null && j.state == JobState.WAIT) { queue.remove(j); j.state = JobState.CANCEL; JobStore.changed() }
                else if (j != null && j.state == JobState.RUN) YoutubeDL.getInstance().destroyProcessById(id)
                if (worker == null) stopIfIdle()
            }
            else -> { FileLog.w("start without action -> idle check"); if (worker == null) stopIfIdle() }
        }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startWorker() {
        if (worker != null) return
        worker = Thread({ loop() }, "dl-worker").also { it.start() }
    }

    private fun loop() {
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoRef:dl")
        wl.acquire(6 * 60 * 60 * 1000L)
        try {
            try { Engine.ensure(this) } catch (e: Exception) {
                FileLog.e("engine init failed", e)
                while (true) { val j = queue.poll() ?: break; j.state = JobState.FAIL; j.message = T("받기 프로그램 준비 실패: {0}", e.message); resultNotif(j) }
                JobStore.changed()
                return
            }
            while (true) {
                val j = queue.poll() ?: break
                current = j
                runJob(j)
                current = null
            }
        } finally {
            if (wl.isHeld) wl.release()
            synchronized(this) { worker = null }
            // 처리 중에 새 작업이 들어왔으면 다시 돈다
            if (queue.isNotEmpty()) startWorker() else stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (queue.isEmpty() && current == null) {
            FileLog.w("service idle -> stop")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runJob(j: Job) {
        try { runJobInner(j) } finally { File(cacheDir, "cookies_${j.id}.txt").delete() }
    }

    private fun runJobInner(j: Job) {
        val t0 = System.currentTimeMillis()
        val tmp = File(getExternalFilesDir("tmp"), j.id)
        tmp.deleteRecursively(); tmp.mkdirs()
        j.state = JobState.RUN; j.progress = -1f; j.message = T("준비 중")
        JobStore.changed(); updateProgress(j, force = true)

        val free = StatFs(getExternalFilesDir(null)!!.path).availableBytes
        FileLog.w("job run id=${j.id} free=${free / 1048576}MB tmp=${tmp.path}")
        val minFree = if (j.dbgMinFree >= 0) j.dbgMinFree else MIN_FREE
        if (free < minFree) {
            j.state = JobState.FAIL; j.message = T("휴대폰 남은 공간 부족 ({0}MB)", free / 1048576)
            FileLog.w("job fail id=${j.id} reason=low-space"); tmp.deleteRecursively(); JobStore.changed(); resultNotif(j); return   // v0.3.1: 빈 임시 폴더 남던 것
        }

        val tail = ArrayDeque<String>()
        var checkFormats = false
        while (true) {
            tail.clear()
            try {
                runYtdlp(j, tmp, checkFormats, tail)
                break
            } catch (e: YoutubeDL.CanceledException) {
                j.state = JobState.CANCEL; j.message = T("취소됨")
                FileLog.w("job canceled id=${j.id}"); tmp.deleteRecursively(); JobStore.changed(); resultNotif(j); return
            } catch (e: Exception) {
                // v0.0.3: Vimeo 등은 'DRM 일 수도 있음' 형식을 먼저 고른다 -> 형식 확인 옵션을 붙여 한 번 더(실측 2026-10-19)
                if (!checkFormats && tail.any { it.contains("DRM protected") && it.contains("--check-formats") }) {
                    FileLog.w("job retry id=${j.id} reason=maybe-drm -> --check-formats")
                    checkFormats = true; j.message = T("다른 형식으로 다시 시도"); JobStore.changed()
                    tmp.deleteRecursively(); tmp.mkdirs()
                    continue
                }
                j.state = JobState.FAIL
                val raw = tail.lastOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR: ") ?: (e.message ?: e.javaClass.simpleName)
                val diagnosis = diagnoseFailure(raw, tail.joinToString("\n"))
                j.message = "${T(diagnosis.stage)}: ${diagnosis.message}"
                FileLog.e("job fail id=${j.id} stage=${diagnosis.stage} msg=${j.message} raw=$raw tail=\n  ${tail.joinToString("\n  ")}", e)
                tmp.deleteRecursively(); JobStore.changed(); resultNotif(j); return
            }
        }
        // 결과 파일: 임시 폴더에서 가장 큰 완성 파일
        val files = tmp.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".part-Frag") }
        FileLog.w("tmp files: " + tmp.listFiles().orEmpty().joinToString { "${it.name}(${it.length()})" })
        val out = files.maxByOrNull { it.length() }
        if (out == null || out.length() == 0L) {
            j.state = JobState.FAIL; j.message = T("받은 파일이 없음")
            FileLog.w("job fail id=${j.id} reason=no-output"); tmp.deleteRecursively(); JobStore.changed(); resultNotif(j); return
        }

        j.state = JobState.SAVE; j.message = T("저장 중"); JobStore.changed(); updateProgress(j, force = true)
        try {
            val base = j.title?.takeIf { it.isNotBlank() } ?: out.nameWithoutExtension
            val (name, uri) = Saver.save(this, out, base)
            j.savedName = name; j.savedUri = uri
            j.state = JobState.DONE; j.progress = 100f
            j.message = T("동영상/VideoRef/{0} ({1}MB, {2}초)", j.savedName, "%.1f".format(out.length() / 1048576.0), (System.currentTimeMillis() - t0) / 1000)
            FileLog.w("job done id=${j.id} ${j.message}")
        } catch (e: Exception) {
            j.state = JobState.FAIL; j.message = "${T("파일 저장 단계")}: ${T("저장 실패: {0}", e.message ?: e.javaClass.simpleName)}"
            FileLog.e("job save fail id=${j.id} stage=STORAGE", e)
        } finally {
            tmp.deleteRecursively()
        }
        JobStore.changed(); resultNotif(j)
    }

    @Throws(Exception::class)
    private fun runYtdlp(j: Job, tmp: File, checkFormats: Boolean, tail: ArrayDeque<String>) {
        val req = YoutubeDLRequest(j.url)
            .addOption("-o", tmp.absolutePath + "/%(title).150B.%(ext)s")
            .addOption("-f", "bv*+ba/b")
            .addOption("-S", Prefs.formatSort(this))          // 4단계: 호환 우선·최대 화질
            .addOption("--merge-output-format", "mp4")
            .addOption("-N", Prefs.fragments(this))
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--newline")
            .addOption("--no-colors")
        if (checkFormats) req.addOption("--check-formats")
        if (!j.referer.isNullOrBlank()) req.addOption("--referer", j.referer)
        // 홈 주소칸에서 시작하면 UA가 비어 Python 기본 UA가 전송된다. Chrome에서는 열리지만
        // 자동화 클라이언트만 연결을 끊는 사이트를 위해 WebView의 표준 Android Chrome UA를 기본 사용한다.
        val requestUa = j.ua?.takeIf { it.isNotBlank() } ?: android.webkit.WebSettings.getDefaultUserAgent(this)
        req.addOption("--user-agent", requestUa)
        if (Prefs.cookies(this)) cookieFile(j)?.let { req.addOption("--cookies", it.absolutePath) }
        j.dbgRate?.let { req.addOption("--limit-rate", it) }
        FileLog.w("settings ${Prefs.summary(this)} dbgRate=${j.dbgRate} dbgMinFree=${j.dbgMinFree}")
        // 쿠키는 쓰지 않는다(기본 끔 — 설정은 4단계)
        FileLog.w("ytdlp cmd: ${req.buildCommand().joinToString(" ")}")
        var lastLoggedPct = -10f
        val res = YoutubeDL.getInstance().execute(req, j.id, true) { pct, eta, line ->
            val isProgress = line.startsWith("[download]") && line.contains("%")
            if (!isProgress || pct - lastLoggedPct >= 10f || pct >= 100f) {
                FileLog.w("ytdlp| $line"); if (isProgress) lastLoggedPct = pct
            }
            if (line.isNotBlank()) synchronized(tail) { tail.addLast(line); if (tail.size > 15) tail.removeFirst() }
            if (pct >= 0) j.progress = pct
            j.eta = eta
            j.message = when {
                line.startsWith("[Merger]") || line.startsWith("[FixupM3u8]") || line.startsWith("[VideoConvertor]") -> T("합치는 중")
                isProgress -> T("받는 중")
                else -> j.message
            }
            updateProgress(j)
        }
        FileLog.w("ytdlp exit=${res.exitCode} elapsed=${res.elapsedTime}ms checkFormats=$checkFormats")
    }

    /**
     * 4단계: 내장 브라우저(웹뷰)의 로그인 정보를 yt-dlp 가 읽는 cookies.txt 로 옮긴다. 설정에서 켰을 때만.
     * 웹뷰는 이름=값만 알려주므로 영상 주소·참조주소의 호스트 기준으로 하루짜리로 적는다. 값은 기록에 남기지 않는다(개수만).
     */
    private fun cookieFile(j: Job): File? {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val hosts = listOfNotNull(j.url, j.referer).mapNotNull { android.net.Uri.parse(it).host }.distinct()
            val exp = System.currentTimeMillis() / 1000 + 86400
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            var n = 0
            for (h in hosts) {
                val raw = cm.getCookie("https://$h/") ?: continue
                raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
                    sb.append("$h\tFALSE\t/\tFALSE\t$exp\t${it.substringBefore('=')}\t${it.substringAfter('=')}\n"); n++
                }
            }
            val f = File(cacheDir, "cookies_${j.id}.txt")
            f.writeText(sb.toString())
            FileLog.w("cookies export hosts=$hosts count=$n")
            f
        } catch (e: Exception) { FileLog.e("cookies export fail", e); null }
    }

    private data class FailureDiagnosis(val stage: String, val message: String)

    /** 실제 yt-dlp 오류 문구에 근거해 실패 단계를 표시한다. 미확인 원인은 원문을 보존한다. */
    private fun diagnoseFailure(raw: String, all: String): FailureDiagnosis {
        val r = (raw + "\n" + all).lowercase()
        val status = Regex("(?:http error|http status|status code)\\s*[:=]?\\s*(\\d{3})").find(r)?.groupValues?.get(1)
            ?: Regex("\\b(401|403|404|408|410|429|5\\d\\d)\\s+(?:forbidden|unauthorized|not found|too many|server|error)").find(r)?.groupValues?.get(1)
        return when {
            "drm" in r -> FailureDiagnosis("접근 제한", T("보호된 영상(DRM)이라 받을 수 없습니다"))
            // v0.4.5: 도우인 등 "Fresh cookies (not necessarily logged in) are needed" — 로그인이 아니라 방문 기록(쿠키)이 필요
            "fresh cookies" in r -> FailureDiagnosis("접근 제한", T("이 사이트는 방문 기록(쿠키)이 있어야 받아집니다. '브라우저에서 찾기'로 열어 영상을 재생한 뒤 영상 위 '받기'를 눌러 주세요."))
            "logged-in" in r || "login" in r || "sign in" in r || "cookies" in r -> FailureDiagnosis("접근 제한", T("이 사이트는 로그인해야 받을 수 있습니다"))
            status != null -> FailureDiagnosis("HTTP 단계", when (status) {
                "401", "403", "429" -> T("서버가 요청을 거부했습니다 (HTTP {0}). 로그인·접근 정책 또는 요청 제한을 확인하세요.", status)
                "404", "410" -> T("요청한 페이지나 영상 리소스가 없습니다 (HTTP {0}).", status)
                else -> T("서버가 오류 응답을 반환했습니다 (HTTP {0}).", status)
            })
            "http error 403" in r -> FailureDiagnosis("HTTP 단계", T("사이트가 접근을 막았습니다 (403). '브라우저'로 열어 받아 보세요."))
            "http error 404" in r -> FailureDiagnosis("HTTP 단계", T("영상을 찾을 수 없습니다 (404)"))
            "private" in r -> FailureDiagnosis("접근 제한", T("비공개 영상입니다"))
            // 받기 프로그램이 모르는 사이트(일반 처리로 실패) -> 브라우저 방법 안내
            "unsupported url" in r || ("[generic]" in r && ("no video formats" in r || "unable to extract" in r)) -> FailureDiagnosis("영상 접근/추출 단계", T("이 사이트는 주소만으로는 받을 수 없습니다. '브라우저'로 열어 영상을 재생한 뒤 영상 위 '받기'를 눌러 주세요."))
            "unable to resolve" in r || "name or service not known" in r || "nodename nor servname" in r || "no address associated" in r ->
                FailureDiagnosis("DNS 단계", T("주소를 찾을 수 없습니다. 주소가 맞는지 확인해 주세요."))
            "ssl" in r || "tls" in r || "certificate verify failed" in r || "handshake" in r -> FailureDiagnosis("TLS 단계", T("사이트의 TLS 보안 연결을 맺지 못했습니다. 인증서 또는 TLS 협상 오류를 확인하세요."))
            "connection reset" in r -> FailureDiagnosis("TCP 단계", T("TCP 연결이 상대편에서 재설정되었습니다. 서버 또는 네트워크가 연결을 종료했습니다."))
            "connection refused" in r -> FailureDiagnosis("TCP 단계", T("TCP 연결이 거부되었습니다. 서버 주소와 포트의 응답 여부를 확인하세요."))
            "network is unreachable" in r -> FailureDiagnosis("TCP 단계", T("네트워크 경로에 도달할 수 없습니다."))
            "timed out" in r || "read timeout" in r -> FailureDiagnosis("연결 대기 단계", T("서버 응답 제한 시간이 지났습니다. DNS·TCP·TLS·HTTP 중 정확한 단계는 로그만으로 특정되지 않았습니다."))
            "no space left" in r || "permission denied" in r || "read-only file system" in r || "input/output error" in r -> FailureDiagnosis("파일 저장 단계", T("임시 파일을 기록할 수 없습니다. 저장 공간과 앱 저장소 권한을 확인하세요."))
            else -> FailureDiagnosis("다운로드 단계 (원인 미분류)", raw.take(200))
        }
    }

    // ---- 알림 ----
    private fun openAppIntent() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotif(j: Job?): Notification {
        val b = Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent())
            .setOngoing(true).setOnlyAlertOnce(true)
        if (j == null) return b.setContentTitle("just downloader").setContentText(T("준비 중")).build()
        val left = queue.size
        b.setContentTitle(j.title?.takeIf { it.isNotBlank() } ?: j.url.take(60))
        val pct = j.progress
        val etaTxt = if (j.eta > 0) " · " + T("{0} 남음", "${j.eta / 60}:${"%02d".format(j.eta % 60)}") else ""
        b.setContentText("${j.message}${if (pct >= 0) " ${"%.1f".format(pct)}%" else ""}$etaTxt${if (left > 0) " · " + T("대기 {0}", left) else ""}")
        if (pct >= 0 && j.state == JobState.RUN) b.setProgress(100, pct.toInt(), false) else b.setProgress(0, 0, true)
        val ci = PendingIntent.getService(this, j.id.hashCode(),
            Intent(this, DownloadService::class.java).setAction(ACT_CANCEL).putExtra("id", j.id), PendingIntent.FLAG_IMMUTABLE)
        b.addAction(Notification.Action.Builder(null, T("취소"), ci).build())
        return b.build()
    }

    private fun updateProgress(j: Job, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        nm.notify(NID_PROGRESS, progressNotif(j))
        JobStore.changed()
    }

    private fun resultNotif(j: Job) {
        val title = when (j.state) { JobState.DONE -> T("저장됨"); JobState.CANCEL -> T("취소됨"); else -> T("받기 실패") }
        val n = Notification.Builder(this, CH_RESULT)
            .setSmallIcon(if (j.state == JobState.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(j.message)
            .setStyle(Notification.BigTextStyle().bigText(j.message))
            .setContentIntent(openAppIntent()).setAutoCancel(true).build()
        nm.notify(resultNid++, n)
    }

    override fun onDestroy() {
        FileLog.w("service destroy")
        current?.let { YoutubeDL.getInstance().destroyProcessById(it.id) }
        super.onDestroy()
    }
}
