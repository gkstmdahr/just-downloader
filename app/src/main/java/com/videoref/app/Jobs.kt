package com.videoref.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

enum class JobState(val label: String) { WAIT("대기"), RUN("받는 중"), SAVE("저장 중"), DONE("완료"), FAIL("실패"), CANCEL("취소됨") }

/** 받기 작업 하나. title/referer 는 3단계(내장 브라우저 감지)에서 채워 넘긴다 — 지금은 주소만. */
class Job(val id: String, val url: String, val referer: String?, val title: String?, val ua: String? = null) {
    @Volatile var state = JobState.WAIT
    @Volatile var progress = -1f
    @Volatile var eta = -1L
    @Volatile var message = ""
    @Volatile var savedName = ""
    @Volatile var savedUri: android.net.Uri? = null      // 4단계 UI: 미리보기 사진·눌러서 열기
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
    fun add(url: String, referer: String?, title: String?, ua: String? = null): Job {
        val j = Job("job${System.currentTimeMillis() % 100000}_${seq.incrementAndGet()}", url, referer, title, ua)
        jobs.add(0, j)
        while (jobs.size > 30) jobs.removeAt(jobs.size - 1)
        changed()
        return j
    }

    @Synchronized fun find(id: String) = jobs.firstOrNull { it.id == id }
    @Synchronized fun snapshot() = jobs.toList()

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    fun changed() {
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it() } }
    }
}
