package com.videoref.app

import android.net.Uri

/**
 * v0.6.0: 두 판 — build.gradle.kts 의 productFlavors 로 나뉜다.
 *  - play(구글 플레이판, net.fourdir.justdownloader): 유튜브 받기 막음, 받기 프로그램 자동/수동 업데이트 없음(앱 버전업으로만), 후원 버튼 없음.
 *    (플레이 정책: 유튜브 약관 위반 받기 금지, 플레이 밖에서 실행 코드 받기 금지, 앱 안 외부 결제 안내 금지)
 *  - site(4dir.net·깃허브판, net.fourdir.justdownloader.full): 전체 기능 + ko-fi 후원 버튼.
 */
object Edition {
    val PLAY: Boolean = BuildConfig.IS_PLAY
    val NAME: String = if (PLAY) "play" else "site"
    const val DONATE_URL = "https://ko-fi.com/4dirofficial"

    private val YT = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "youtubekids.com")

    fun isYouTube(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val h = try { Uri.parse(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        return YT.any { h == it || h.endsWith(".$it") }
    }

    /** 플레이판에서 막을 받기인지 — 영상 주소나 그 영상이 있던 페이지(referer)가 유튜브면 막는다. */
    fun blocked(url: String?, referer: String?): Boolean = PLAY && (isYouTube(url) || isYouTube(referer))
}
