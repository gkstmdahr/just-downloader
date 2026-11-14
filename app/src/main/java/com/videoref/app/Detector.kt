package com.videoref.app

import android.net.Uri

/** 영상 후보 종류. 목록(m3u8/mpd)을 맨 위에 — 받기는 목록 주소로 해야 전체가 받아진다(1단계 실측). */
enum class Kind(val label: String, val order: Int) { LIST("목록", 0), FILE("파일", 1), PIECE("조각", 2) }

class Cand(val key: String, var url: String, val kind: Kind, var referer: String?, val src: String) {
    var hits = 1
    val seen = System.currentTimeMillis()
    // v0.6.4: 인스타·페이스북 CDN 스트림 정보(efg). 같은 asset 의 영상·소리를 짝지어 합친다.
    var asset: String? = null
    var audio = false
    var bitrate = 0L
}

/** v0.6.4: 메타(인스타·페이스북) CDN 주소의 efg(base64 JSON) 해석 — 2026-10-28 실측: vencode_tag(…_audio 면 소리), xpv_asset_id(같은 영상 묶음), bitrate */
object MetaCdn {
    private val BYTE_Q = Regex("""([?&])(bytestart|byteend)=\d*""", RegexOption.IGNORE_CASE)

    /** bytestart/byteend 를 빼면 CDN 이 스트림 전체를 준다(실측: 720p 8.4MB·소리 0.4MB 전체). */
    fun full(url: String): String = url.replace(BYTE_Q, "$1").replace("?&", "?").replace("&&", "&").trimEnd('&', '?')

    data class Info(val asset: String, val audio: Boolean, val bitrate: Long)

    fun info(url: String): Info? = try {
        var efg = (Uri.parse(url).getQueryParameter("efg") ?: return null).replace('-', '+').replace('_', '/').trimEnd('=')
        while (efg.length % 4 != 0) efg += "="
        val js = org.json.JSONObject(String(android.util.Base64.decode(efg, android.util.Base64.DEFAULT)))
        val asset = js.optLong("xpv_asset_id", 0L).takeIf { it > 0 }?.toString() ?: js.optString("video_id").takeIf { it.isNotBlank() && it != "null" }
        if (asset == null) null else Info(asset, js.optString("vencode_tag").contains("audio", true), js.optLong("bitrate", 0L))
    } catch (_: Exception) { null }
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
        /** v0.6.4: 인스타·페이스북 CDN 은 영상·소리를 따로 두고 주소에 bytestart/byteend 를 붙여 조금씩 가져간다 -> 그 주소로 받으면 앞부분 조각만 남는다(2026-10-28 실측: 188바이트~3MB 깨진 파일). */
        private val BYTE_RANGE_Q = Regex("""[?&](bytestart|byteend)=""", RegexOption.IGNORE_CASE)

        /**
         * @return null 이면 영상 아님
         * v0.4.6: 확장자 없는 영상 주소(도우인 등 '…/video/tos/…?mime_type=video_mp4')도 잡는다 —
         *   ① src=tag: 영상 태그가 실제로 재생하는 주소 ② 영상 태그의 첫 요청 모양(Range: bytes=0-) ③ 주소에 mime_type=video
         */
        fun classify(url: String, range: String?, src: String = "req"): Kind? {
            if (!url.startsWith("http")) return null              // blob:, data: 는 받을 수 없음
            val path = Uri.parse(url).path ?: return null
            val m = EXT.find(path)
            if (m == null && NOT_VIDEO.containsMatchIn(path)) return null
            if (BYTE_RANGE_Q.containsMatchIn(url)) return Kind.PIECE
            if (m == null) {
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
    fun add(url0: String, range: String?, referer: String?, src: String): Cand? {
        var kind = classify(url0, range, src) ?: return null
        // v0.6.4: 메타 CDN 조각 주소는 정보(efg)가 있으면 '전체 스트림'으로 바꿔 파일 후보로 쓴다(없으면 조각 그대로)
        val meta = if (kind == Kind.PIECE && BYTE_RANGE_Q.containsMatchIn(url0)) MetaCdn.info(url0) else null
        val url = if (meta != null) MetaCdn.full(url0) else url0
        if (meta != null) kind = Kind.FILE
        val key = keyOf(url)
        val old = map[key]
        if (old != null) {
            old.hits++
            old.url = url                                       // 서명 주소는 최신 것이 유효
            if (old.referer == null && referer != null) old.referer = referer
            return null
        }
        val c = Cand(key, url, kind, referer, src)
        if (meta != null) { c.asset = meta.asset; c.audio = meta.audio; c.bitrate = meta.bitrate }
        map[key] = c
        return c
    }

    /** v0.6.4: 같은 묶음(asset)의 가장 좋은 소리 스트림 */
    @Synchronized
    fun audioFor(c: Cand): Cand? = c.asset?.let { a -> map.values.filter { it.asset == a && it.audio }.maxByOrNull { it.bitrate } }

    /**
     * 영상 위 받기 버튼용: 그 프레임에서 나온 후보 중 가장 좋은 것(목록 > 파일, 같으면 많이 불린 것).
     * 프레임에서 나온 게 없으면 전체에서 고른다. 조각은 고르지 않는다.
     */
    @Synchronized
    fun bestFor(frameHref: String?): Cand? {
        val host = frameHref?.let { Uri.parse(it).host }
        val all = map.values.filter { it.kind != Kind.PIECE && !it.audio }   // v0.6.4: 소리만 있는 스트림은 단독으로 고르지 않음
        val mine = if (host == null) emptyList() else all.filter { Uri.parse(it.referer ?: "").host == host || Uri.parse(it.url).host == host }
        return mine.ifEmpty { all }.sortedWith(compareBy<Cand>({ it.kind.order }, { -it.bitrate }, { -it.hits })).firstOrNull()
    }

    @Synchronized fun clear() = map.clear()
    @Synchronized fun sorted(): List<Cand> = map.values.sortedWith(compareBy<Cand>({ it.kind.order }, { it.seen }))
    @Synchronized fun count(kind: Kind) = map.values.count { it.kind == kind }
}
