package com.videoref.app

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 다국어 구조 v0.5.1 — 기존 10개 언어 설정과 변환 방식을 유지.
 * 원문 = 코드의 한국어. T("한국어") -> assets/lang/<코드>.json 의 번역(키 = 한국어 원문). 없으면 한국어 그대로.
 * 언어: ko·en·zh_CN·zh_TW·ja·es·de·fr·pt_BR·ru (10개). 기본 = 폰 언어, 목록 밖이면 en. 설정에서 바꾸면 화면을 다시 만든다.
 * 번역 파일 규칙: {0} {1} 자리표시와 \n 줄바꿈을 그대로 둔다.
 */
object I18n {
    val LANGS = listOf("ko" to "한국어", "en" to "English", "zh_CN" to "简体中文", "zh_TW" to "繁體中文", "ja" to "日本語",
        "es" to "Español", "de" to "Deutsch", "fr" to "Français", "pt_BR" to "Português (Brasil)", "ru" to "Русский")

    @Volatile var code = "ko"; private set
    private var map: Map<String, String> = emptyMap()
    private val missing = HashSet<String>()

    /** 폰 언어 -> 목록의 코드(없으면 en) */
    fun deviceCode(): String {
        val l = Locale.getDefault()
        return when {
            l.language == "zh" && (l.country in listOf("TW", "HK", "MO") || l.script == "Hant") -> "zh_TW"
            l.language == "zh" -> "zh_CN"
            l.language == "pt" -> "pt_BR"
            LANGS.any { it.first == l.language } -> l.language
            else -> "en"
        }
    }

    fun load(ctx: Context) {
        val chosen = Prefs.lang(ctx).ifBlank { deviceCode() }
        code = chosen
        map = if (chosen == "ko") emptyMap() else try {
            val o = JSONObject(ctx.assets.open("lang/$chosen.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { o.getString(it) }
        } catch (e: Exception) { FileLog.e("lang load fail $chosen", e); emptyMap() }
        FileLog.w("lang=$chosen (pref='${Prefs.lang(ctx)}' device=${Locale.getDefault()}) keys=${map.size}")
    }

    /** v0.5.12: 날짜 등 형식도 앱 언어로(폰 언어가 아니라) — 받은 영상 날짜가 앱 언어와 달리 한국어 형식으로 나왔음 */
    fun locale(): Locale = when (code) { "zh_CN" -> Locale.SIMPLIFIED_CHINESE; "zh_TW" -> Locale.TRADITIONAL_CHINESE; "pt_BR" -> Locale("pt", "BR"); else -> Locale(code) }

    fun t(s: String, vararg a: Any?): String {
        var r = map[s] ?: s.also { if (code != "ko" && missing.add(s)) FileLog.w("lang missing [$code] $s") }
        a.forEachIndexed { i, v -> r = r.replace("{$i}", v.toString()) }
        return r
    }
}

/** 화면 글자 번역(원문 = 한국어). */
fun T(s: String, vararg a: Any?) = I18n.t(s, *a)
