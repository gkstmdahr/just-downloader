package com.videoref.app

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 받기 시작 공통 처리: 와이파이가 아니면 확인창(설정 '와이파이에서만 받기' 켜짐일 때) -> 서비스에 넘김. */
object Net {
    fun isUnmetered(a: Activity): Boolean {
        if (Prefs.fakeMetered) return false
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** @param onStarted 실제로 받기를 넘겼을 때만 부름(확인창에서 취소하면 안 부름) — 메인 화면 주소칸 비우기용 */
    fun start(a: Activity, url: String, referer: String?, title: String?, ua: String?, force: Boolean, audio: String? = null, onStarted: () -> Unit = {}) {
        val unmetered = isUnmetered(a)
        val wifiOnly = Prefs.wifiOnly(a)
        FileLog.w("start request audioOnly=$audioOnly url=$url audio=${audio?.take(160)} referer=$referer title=$title unmetered=$unmetered wifiOnly=$wifiOnly force=$force")
        if (Edition.blocked(url, referer)) { ytBlocked(a, url); return }
        val ao = audioOnly
        val go = { DownloadService.add(a, url, referer, title, ua, audio, ao); Toast.makeText(a, T(if (ao) "소리만 받기 시작" else "받기 시작"), Toast.LENGTH_SHORT).show(); onStarted() }
        if (unmetered || force || !wifiOnly || meteredOk) { if (meteredOk && !unmetered) FileLog.w("metered skip (already allowed this run)"); go(); return }
        // v0.6.5: 받을 때마다 묻던 것 -> 한 번 '받기' 하면 앱을 닫을 때까지 안 물음 + '다시 묻지 않기'(설정 끔)
        MaterialAlertDialogBuilder(a).setTitle(T("와이파이가 아닙니다")).setIcon(R.drawable.ic_download)
            .setMessage(T("모바일 데이터로 받으면 요금이 나올 수 있습니다. 그래도 받을까요?") + "\n\n" + T("받기를 누르면 앱을 닫을 때까지 다시 묻지 않습니다."))
            .setPositiveButton(T("받기")) { _, _ -> FileLog.w("metered confirm yes (allow this run)"); meteredOk = true; go() }
            .setNeutralButton(T("다시 묻지 않기")) { _, _ ->
                FileLog.w("metered confirm never (wifiOnly -> false)"); Prefs.set(a, "wifiOnly", false)
                Toast.makeText(a, T("설정에서 다시 켤 수 있습니다."), Toast.LENGTH_LONG).show(); go()
            }
            .setNegativeButton(T("취소")) { _, _ -> FileLog.w("metered confirm no") }.show()
    }

    /** v0.7.2: 여러 주소를 한 번에 대기열로. 와이파이 확인은 한 번만(start 와 같은 규칙) */
    fun startMany(a: Activity, urls: List<String>, onStarted: () -> Unit = {}) {
        val unmetered = isUnmetered(a)
        val wifiOnly = Prefs.wifiOnly(a)
        FileLog.w("start many n=${urls.size} audioOnly=$audioOnly unmetered=$unmetered wifiOnly=$wifiOnly")
        val ao = audioOnly
        val go = {
            urls.forEach { u -> DownloadService.add(a, u, null, null, null, null, ao) }
            Toast.makeText(a, T("{0}개 받기 시작", urls.size), Toast.LENGTH_SHORT).show(); onStarted()
        }
        if (unmetered || !wifiOnly || meteredOk) { go(); return }
        MaterialAlertDialogBuilder(a).setTitle(T("와이파이가 아닙니다")).setIcon(R.drawable.ic_download)
            .setMessage(T("모바일 데이터로 받으면 요금이 나올 수 있습니다. 그래도 받을까요?") + "\n\n" + T("받기를 누르면 앱을 닫을 때까지 다시 묻지 않습니다."))
            .setPositiveButton(T("받기")) { _, _ -> FileLog.w("metered confirm yes (batch)"); meteredOk = true; go() }
            .setNegativeButton(T("취소")) { _, _ -> FileLog.w("metered confirm no (batch)") }.show()
    }

    /** v0.6.6: 홈 [영상][소리만] 전환. 앱 프로세스가 끝나면 '영상'으로 돌아감(잊고 MP3만 받는 실수 막기). 브라우저 받기도 이 값을 따름 */
    @Volatile var audioOnly = false

    /** 이번 실행 중 모바일 데이터 받기를 이미 허락했는지(앱 프로세스가 끝나면 초기화) */
    @Volatile private var meteredOk = false

    /** v0.6.0: 플레이판에서 유튜브 받기를 막았을 때 안내 */
    fun ytBlocked(a: Activity, url: String) {
        FileLog.w("BLOCK youtube (play edition) url=${url.take(200)}")
        MaterialAlertDialogBuilder(a).setTitle(T("받을 수 없는 사이트"))
            .setMessage(T("구글 플레이 정책에 따라 이 앱에서는 유튜브 영상을 받을 수 없습니다."))
            .setPositiveButton(T("확인"), null).show()
    }
}
