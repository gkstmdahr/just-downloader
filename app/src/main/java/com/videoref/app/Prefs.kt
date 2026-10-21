package com.videoref.app

import android.content.Context
import android.content.pm.ApplicationInfo

/** 4단계 설정값. 기본값은 데스크탑판과 같다(쿠키 끔, 조각 동시 8). */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun wifiOnly(c: Context) = sp(c).getBoolean("wifiOnly", true)          // 와이파이 아니면 확인창
    fun compat(c: Context) = sp(c).getBoolean("compat", true)              // 호환 우선(h264+aac) — 편집툴·구형 기기
    fun maxRes(c: Context) = sp(c).getInt("maxRes", 0)                     // 0=제한 없음, 1080, 720
    fun fragments(c: Context) = sp(c).getInt("fragments", 8)               // 조각 동시 받기 수
    fun cookies(c: Context) = sp(c).getBoolean("cookies", false)           // 브라우저 로그인 정보 같이 보내기
    fun theme(c: Context) = sp(c).getInt("theme", 0)                       // v0.4.3: 0=폰 설정 따름 1=밝게 2=어둡게
    fun lang(c: Context) = sp(c).getString("lang", "") ?: ""              // v0.5.0: ""=폰 언어 따름, 아니면 I18n 코드
    fun setLang(c: Context, v: String) { sp(c).edit().putString("lang", v).apply(); FileLog.w("setting lang=$v") }

    fun applyTheme(c: Context) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (theme(c)) {
            1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    // v0.5.12: '문제 생김'(구글 폼) 방식은 뺐다 — 오류 보고 = 메일 + 기록 파일 첨부(Support.kt)

    fun set(c: Context, key: String, v: Any) {
        val e = sp(c).edit()
        when (v) { is Boolean -> e.putBoolean(key, v); is Int -> e.putInt(key, v) }
        e.apply()
        FileLog.w("setting $key=$v")
    }

    fun summary(c: Context) = "wifiOnly=${wifiOnly(c)} compat=${compat(c)} maxRes=${maxRes(c)} fragments=${fragments(c)} cookies=${cookies(c)}"

    /** yt-dlp 형식 정렬(-S). 호환 우선이면 h264·aac 를 먼저, 최대 화질이 있으면 그 이하에서 가장 큰 것. */
    fun formatSort(c: Context): String {
        val res = maxRes(c).let { if (it > 0) "res:$it" else "res" }
        return if (compat(c)) "vcodec:h264,$res,acodec:m4a" else res
    }

    fun debuggable(c: Context) = (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * v0.5.13 보안: 시험용 명령(주소+받기, 고르기, 언어 바꾸기 등)은 '시험 모드'일 때만 받는다.
     * 시험 모드 = 시험용 빌드 + 앱 전용 폴더에 표식 파일 testmode 가 있을 때. 이 폴더는 다른 앱이 쓸 수 없고,
     * PC 의 adb(USB 디버깅)로만 만든다: adb shell touch /sdcard/Android/data/com.videoref.app/files/testmode
     * (전엔 시험용 빌드이기만 하면 폰의 아무 앱이나 '이 주소 받아' 명령을 보낼 수 있었음)
     */
    fun testMode(c: Context): Boolean {
        val on = debuggable(c) && java.io.File(c.getExternalFilesDir(null), "testmode").exists()
        if (on != lastTestMode) { lastTestMode = on; FileLog.w("testMode=$on") }
        return on
    }
    @Volatile private var lastTestMode: Boolean? = null

    /** 앱 안에서 다른 화면을 열 때 붙이는 비밀값(앱이 켜질 때마다 새로 만듦). 이게 없으면 외부에서 온 명령으로 본다. */
    val SECRET: String = java.util.UUID.randomUUID().toString()
    fun fromApp(i: android.content.Intent?) = i?.getStringExtra("k") == SECRET
    // 시험용(시험용 빌드에서 adb 로만 바뀜)
    @Volatile var fakeMetered = false                                     // 와이파이여도 모바일 데이터처럼
    @Volatile var dbgRate: String? = null                                 // 받기 속도 제한(예 "1M")
    @Volatile var dbgMinFree = -1L                                        // 남은 공간 기준(바이트)
}
