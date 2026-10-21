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
    fun start(a: Activity, url: String, referer: String?, title: String?, ua: String?, force: Boolean, onStarted: () -> Unit = {}) {
        val unmetered = isUnmetered(a)
        val wifiOnly = Prefs.wifiOnly(a)
        FileLog.w("start request url=$url referer=$referer title=$title unmetered=$unmetered wifiOnly=$wifiOnly force=$force")
        if (Edition.blocked(url, referer)) { ytBlocked(a, url); return }
        val go = { DownloadService.add(a, url, referer, title, ua); Toast.makeText(a, T("받기 시작"), Toast.LENGTH_SHORT).show(); onStarted() }
        if (unmetered || force || !wifiOnly) { go(); return }
        MaterialAlertDialogBuilder(a).setTitle(T("와이파이가 아닙니다")).setIcon(R.drawable.ic_download)
            .setMessage(T("모바일 데이터로 받으면 요금이 나올 수 있습니다. 그래도 받을까요?"))
            .setPositiveButton(T("받기")) { _, _ -> FileLog.w("metered confirm yes"); go() }
            .setNegativeButton(T("취소")) { _, _ -> FileLog.w("metered confirm no") }.show()
    }

    /** v0.6.0: 플레이판에서 유튜브 받기를 막았을 때 안내 */
    fun ytBlocked(a: Activity, url: String) {
        FileLog.w("BLOCK youtube (play edition) url=${url.take(200)}")
        MaterialAlertDialogBuilder(a).setTitle(T("받을 수 없는 사이트"))
            .setMessage(T("구글 플레이 정책에 따라 이 앱에서는 유튜브 영상을 받을 수 없습니다."))
            .setPositiveButton(T("확인"), null).show()
    }
}
