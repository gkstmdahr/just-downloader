package com.videoref.app

import android.net.Uri

/** 영상 후보 종류. 목록(m3u8/mpd)을 맨 위에 — 받기는 목록 주소로 해야 전체가 받아진다(1단계 실측). */
enum class Kind(val label: String, val order: Int) { LIST("목록", 0), FILE("파일", 1), PIECE("조각", 2) }

class Cand(val key: String, var url: String, val kind: Kind, var referer: String?, val src: String) {
    var hits = 1
    val seen = System.currentTimeMillis()
}

/**
 * 내장 브라우저에서 본 영상 후보 모음. 같은 자원(주소의 ? 앞부분이 같음)은 하나로 묶고 횟수만 센다.
 * 출처: req = 요청 가로채기(헤더 있음), scan = 프레임이 알려준 '이미 불러온 목록', tag = video 태그 주소
 */
class Detector {
    companion object {
        private val EXT = Regex("""\.(m3u8|mpd|mp4|m4s|m4v|ts|webm|mov|mkv)$""", RegexOption.IGNORE_CASE)
        private val PIECE_HINT = Regex("""(segment|/seg[-_]?\d|frag|chunk|/range/|init\.mp4|\.m4s$|\.ts$)""", RegexOption.IGNORE_CASE)

        private val NOT_VIDEO = Regex("""\.(jpe?g|png|gif|webp|avif|svg|ico|js|mjs|css|woff2?|ttf|otf|json|html?|txt|xml|mp3|m4a|aac|ogg|opus|wav|flac|pdf|zip)$""", RegexOption.IGNORE_CASE)

        /**
         * @return null 이면 영상 아님
         * v0.4.6: 확장자 없는 영상 주소(도우인 등 '…/video/tos/…?mime_type=video_mp4')도 잡는다 —
         *   ① src=tag: 영상 태그가 실제로 재생하는 주소 ② 영상 태그의 첫 요청 모양(Range: bytes=0-) ③ 주소에 mime_type=video
         */
        fun classify(url: String, range: String?, src: String = "req"): Kind? {
            if (!url.startsWith("http")) return null              // blob:, data: 는 받을 수 없음
            val path = Uri.parse(url).path ?: return null
            val m = EXT.find(path)
            if (m == null) {
                if (NOT_VIDEO.containsMatchIn(path)) return null
                val mediaLike = src == "tag" || range == "bytes=0-" || url.contains("mime_type=video", true)
                return if (mediaLike) Kind.FILE else null
            }
            return when (m.groupValues[1].lowercase()) {
                "m3u8", "mpd" -> Kind.LIST
                else -> if (PIECE_HINT.containsMatchIn(path) || (range != null && !range.startsWith("bytes=0-"))) Kind.PIECE else Kind.FILE
            }
        }

        fun keyOf(url: String): String = url.substringBefore('?').substringBefore('#')
    }

    private val map = LinkedHashMap<String, Cand>()

    /** @return 새로 생긴 후보면 그 후보, 이미 있던 거면 null */
    @Synchronized
    fun add(url: String, range: String?, referer: String?, src: String): Cand? {
        val kind = classify(url, range, src) ?: return null
        val key = keyOf(url)
        val old = map[key]
        if (old != null) {
            old.hits++
            old.url = url                                       // 서명 주소는 최신 것이 유효
            if (old.referer == null && referer != null) old.referer = referer
            return null
        }
        val c = Cand(key, url, kind, referer, src)
        map[key] = c
        return c
    }

    /**
     * 영상 위 받기 버튼용: 그 프레임에서 나온 후보 중 가장 좋은 것(목록 > 파일, 같으면 많이 불린 것).
     * 프레임에서 나온 게 없으면 전체에서 고른다. 조각은 고르지 않는다.
     */
    @Synchronized
    fun bestFor(frameHref: String?): Cand? {
        val host = frameHref?.let { Uri.parse(it).host }
        val all = map.values.filter { it.kind != Kind.PIECE }
        val mine = if (host == null) emptyList() else all.filter { Uri.parse(it.referer ?: "").host == host || Uri.parse(it.url).host == host }
        return mine.ifEmpty { all }.sortedWith(compareBy<Cand>({ it.kind.order }, { -it.hits })).firstOrNull()
    }

    @Synchronized fun clear() = map.clear()
    @Synchronized fun sorted(): List<Cand> = map.values.sortedWith(compareBy<Cand>({ it.kind.order }, { it.seen }))
    @Synchronized fun count(kind: Kind) = map.values.count { it.kind == kind }
}
