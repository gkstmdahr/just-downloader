package com.videoref.app

import android.net.Uri

/**
 * v0.3.4: 받기 프로그램(yt-dlp)이 주소만으로 바로 받을 수 있는 대표 사이트.
 * 여기 있으면 공유받을 때 바로 받기, 없으면 VideoRef 브라우저로 열어 재생 후 '⬇ 받기'(iframe 플레이어 사이트용).
 * 목록에 없어도 '받기'는 그대로 쓸 수 있다(실패하면 브라우저를 권하는 문구가 나옴).
 */
object Sites {
    private val DIRECT = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitch.tv",
        "instagram.com", "tiktok.com", "facebook.com", "fb.watch", "x.com", "twitter.com", "reddit.com",
        "bilibili.com", "nicovideo.jp", "tv.naver.com", "naver.me", "kakao.com", "sooplive.co.kr", "afreecatv.com",
        "chzzk.naver.com", "pinterest.com", "patreon.com", "streamable.com", "archive.org", "artstation.com"
    )

    /**
     * v0.4.5: 모바일 사이트가 바꿔 놓은 페이지 주소를 받기 프로그램이 아는 형태로 되돌린다('페이지로 받기'용).
     * 도우인: m.douyin.com/share/video/<번호>, www.iesdouyin.com/share/video/<번호> -> www.douyin.com/video/<번호> (2026-10-21 실측: 앞 형태는 Unsupported URL)
     */
    fun normalizePage(url: String): String {
        Regex("""https?://(?:m\.douyin\.com|(?:www\.)?iesdouyin\.com)/share/video/(\d+)""").find(url)?.let { return "https://www.douyin.com/video/${it.groupValues[1]}" }
        return url
    }

    fun isDirect(url: String): Boolean {
        val h = Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        return DIRECT.any { h == it || h.endsWith(".$it") }
    }
}
