package net.fourdir.player

// 재생기 묶음 v0.2.0 — 이 모듈이 바깥(앱)에서 받는 것은 이 파일이 전부다.
//   log       : 기록 남기기(앱은 videoref.log 에 'player|' 로 남김)
//   text      : 화면 문구 번역(키 = 한국어 원문)
//   converter : 재생 못 하는 파일을 MP4 로 바꾸는 도구(앱은 내장 ffmpeg). 없으면 '재생할 수 없음' 안내만.
//   actions   : 더보기 메뉴에 붙일 앱 기능(예: 자르기·공유·소리만 저장). 없으면 메뉴에 안 나옴.
//   picturesDir : 화면 캡처 저장 폴더(사진 아래 상대 경로)
// v0.2.0: 목록 재생(이전/다음) — open(ctx, items, index)

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object PlayerHost {
    @Volatile var log: (String) -> Unit = { android.util.Log.i("FourdirPlayer", it) }
    @Volatile var text: (String) -> String = { it }
    @Volatile var converter: Converter? = null
    @Volatile var actions: List<Action> = emptyList()
    @Volatile var picturesDir: String = "Pictures/Player"

    interface Converter {
        /**
         * 배경 스레드에서 부른다(오래 걸림).
         * @param audioOnly true = 영상은 재생되고 소리만 안 되는 경우 -> 영상은 그대로 두고 소리만 바꿔도 됨
         * @param progress 0..1 (길이를 모르면 안 부름)
         * @return 재생 가능한 파일(재생 화면이 닫힐 때 지움), 실패·취소면 null
         */
        fun toPlayable(ctx: Context, src: Uri, audioOnly: Boolean, progress: (Float) -> Unit, cancelled: () -> Boolean): File?
    }

    /** 더보기 메뉴의 앱 기능. label 은 메뉴를 열 때마다 읽음(언어 바뀜 대비). run 은 재생 화면(Activity)·지금 파일 주소·제목을 받는다 */
    class Action(val label: () -> String, val run: (Activity, Uri, String?) -> Unit)

    /** 재생 목록 한 칸 */
    class Item(val uri: Uri, val title: String?)

    /** 재생 화면 열기. uri = content:// 또는 file:// */
    fun open(ctx: Context, uri: Uri, title: String? = null) = open(ctx, listOf(Item(uri, title)), 0)

    /** 목록 재생: index 부터, 이전/다음 단추로 넘김 */
    fun open(ctx: Context, items: List<Item>, index: Int) {
        if (items.isEmpty()) return
        log("open n=${items.size} index=$index uri=${items.getOrNull(index)?.uri}")
        pending = items
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).setData(items[index.coerceIn(items.indices)].uri)
            .putExtra(PlayerActivity.EXTRA_TITLE, items[index.coerceIn(items.indices)].title).putExtra(PlayerActivity.EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** 화면에 넘길 목록(인텐트에 넣기엔 클 수 있어 같은 프로세스 안에서 넘김). 화면이 가져가면 비움 */
    @Volatile internal var pending: List<Item>? = null
}
