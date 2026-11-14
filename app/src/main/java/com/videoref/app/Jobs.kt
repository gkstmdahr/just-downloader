package com.videoref.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

enum class JobState(val label: String) { WAIT("대기"), RUN("받는 중"), SAVE("저장 중"), DONE("완료"), FAIL("실패"), CANCEL("취소됨") }

/** 받기 작업 하나. title/referer 는 3단계(내장 브라우저 감지)에서 채워 넘긴다 — 지금은 주소만. */
/** v0.6.4: audio = 영상과 따로 오는 소리 스트림 주소(인스타 등). 있으면 둘을 받아 합친다. */
class Job(val id: String, val url: String, val referer: String?, val title: String?, val ua: String? = null, val audio: String? = null) {
    @Volatile var state = JobState.WAIT
    @Volatile var progress = -1f
    @Volatile var eta = -1L
    @Volatile var message = ""
    @Volatile var savedName = ""
    @Volatile var savedUri: android.net.Uri? = null      // 4단계 UI: 미리보기 사진·눌러서 열기
    @Volatile var notifId = 0                             // v0.6.6: 결과 알림 번호(0 = 안 띄움) — 브라우저 자동 전환 때 지우기용
    @Volatile var audioOnly = false                       // v0.6.6: 소리만 받기(MP3 -> 음악/VideoRef)
    // 시험용(시험용 빌드에서만 채워짐): 속도 제한, 남은 공간 기준 바꾸기
    var dbgRate: String? = null
    var dbgMinFree = -1L
}

/** 화면과 서비스가 같은 프로세스에서 공유하는 작업 목록. 바뀌면 화면에 알린다. */
object JobStore {
    private val seq = AtomicInteger()
    val jobs = mutableListOf<Job>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(url: String, referer: String?, title: String?, ua: String? = null, audio: String? = null): Job {
        val j = Job("job${System.currentTimeMillis() % 100000}_${seq.incrementAndGet()}", url, referer, title, ua, audio)
        jobs.add(0, j)
        while (jobs.size > 30) jobs.removeAt(jobs.size - 1)
        changed()
        return j
    }

    @Synchronized fun find(id: String) = jobs.firstOrNull { it.id == id }
    @Synchronized fun snapshot() = jobs.toList()

    /** v0.6.5: 홈 목록에서 끝난(실패·취소) 작업 지우기 — 받는 중인 건 지우지 않는다. @return 지운 개수 */
    @Synchronized fun remove(id: String): Int { val n = jobs.count { it.id == id && it.ended() }; jobs.removeAll { it.id == id && it.ended() }; changed(); return n }
    @Synchronized fun clearEnded(): Int { val n = jobs.count { it.ended() }; jobs.removeAll { it.ended() }; changed(); return n }
    private fun Job.ended() = state == JobState.FAIL || state == JobState.CANCEL
    /** v0.6.6: 소리만 받은 완료 작업은 '최근'(영상 목록)에 안 나오므로 작업 카드로 남김 -> X 로 목록에서만 지움(파일은 그대로) */
    @Synchronized fun removeDone(id: String): Int { val n = jobs.count { it.id == id && it.state == JobState.DONE }; jobs.removeAll { it.id == id && it.state == JobState.DONE }; changed(); return n }

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }
}
