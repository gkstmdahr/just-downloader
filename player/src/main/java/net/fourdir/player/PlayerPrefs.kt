package net.fourdir.player

// 재생기 설정·이어 보기 위치 v0.2.0 (SharedPreferences "fourdir_player")
//   pos:<주소>     = 마지막 위치(ms). 5초 이전·끝 5초 안이면 지움. 300개 넘으면 오래된 것부터 지움.
//   subSize / subBg = 자막 크기 단계(0~3) / 자막 배경(0 없음 1 반투명)
//   autoPip        = 홈 단추로 나가면 작은 창(기본 켬)
//   bgPlay         = 화면을 나가도 소리 계속(기본 끔)

import android.content.Context

class PlayerPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fourdir_player", Context.MODE_PRIVATE)

    fun pos(key: String): Long = sp.getLong("pos:$key", 0L)

    fun savePos(key: String, pos: Long, dur: Long) {
        val e = sp.edit()
        if (pos > 5000 && (dur <= 0 || pos < dur - 5000)) e.putLong("pos:$key", pos).putLong("at:$key", System.currentTimeMillis())
        else e.remove("pos:$key").remove("at:$key")
        e.apply()
        trim()
    }

    private fun trim() {
        val at = sp.all.filterKeys { it.startsWith("at:") }
        if (at.size <= 300) return
        val e = sp.edit()
        at.entries.sortedBy { it.value as? Long ?: 0L }.take(at.size - 300).forEach { e.remove(it.key).remove("pos:" + it.key.removePrefix("at:")) }
        e.apply()
    }

    var subSize: Int
        get() = sp.getInt("subSize", 1)
        set(v) = sp.edit().putInt("subSize", v).apply()
    var subBg: Int
        get() = sp.getInt("subBg", 0)
        set(v) = sp.edit().putInt("subBg", v).apply()
    var autoPip: Boolean
        get() = sp.getBoolean("autoPip", true)
        set(v) = sp.edit().putBoolean("autoPip", v).apply()
    var bgPlay: Boolean
        get() = sp.getBoolean("bgPlay", false)
        set(v) = sp.edit().putBoolean("bgPlay", v).apply()
}
