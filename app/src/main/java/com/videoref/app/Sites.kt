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
        "tiktok.com", "facebook.com", "fb.watch", "x.com", "twitter.com", "reddit.com",
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

    /**
     * v0.6.4: 주소만으로는 폰에서 못 받는 사이트 -> 받기 대신 앱 브라우저로 연다(재생 후 영상 위 받기는 됨).
     * 인스타: yt-dlp 가 브라우저 흉내 모듈(curl_cffi) 없으면 'empty media response' (2026-10-28 실측: PC exe 성공 / 폰 실패, 같은 2026.10.26판)
     */
    // v0.6.5: 틱톡·페이스북·레딧도 같은 이유로 주소 받기 실패(2026-10-28 실측) -> 실패 뒤 전환을 기다리지 않고 처음부터 브라우저로
    // v0.6.6: 패트리온도 주소 받기는 403(JSON metadata, 2026-10-29 실측: PC exe 성공 / 폰 실패), 앱 브라우저로는 받힘(76.1MB)
    private val BROWSER_FIRST = listOf("instagram.com", "tiktok.com", "facebook.com", "fb.watch", "reddit.com", "redd.it", "patreon.com")

    private fun host(url: String) = Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")

    fun isDirect(url: String): Boolean {
        val h = host(url) ?: return false
        return DIRECT.any { h == it || h.endsWith(".$it") }
    }

    /**
     * v0.6.5: 영상 주소(CDN)가 브라우저 접속 정보(쿠키) 없으면 거절하는 사이트 -> 설정과 상관없이 그 사이트 쿠키만 넘긴다.
     * 틱톡: 앱 브라우저에서 잡은 v16-webapp… 주소를 쿠키 없이 받으면 403 (2026-10-28 실측)
     */
    private val NEED_COOKIES = listOf("tiktok.com")

    fun needsCookies(url: String?): Boolean {
        val h = host(url ?: return false) ?: return false
        return NEED_COOKIES.any { h == it || h.endsWith(".$it") }
    }

    fun browserFirst(url: String): Boolean {
        val h = host(url) ?: return false
        return BROWSER_FIRST.any { h == it || h.endsWith(".$it") }
    }
}
