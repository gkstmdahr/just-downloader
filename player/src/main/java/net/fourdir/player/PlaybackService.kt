package net.fourdir.player

// v0.2.1: 새 영상을 열어 세션이 바뀐 뒤 서비스가 멈출 때 앱이 꺼지던 것 수정(이 서비스가 붙인 세션만 뗌)
// 뒤에서 재생 v0.2.1 — 설정 '화면을 나가도 소리 계속'이 켜져 있을 때만 재생 화면이 시작한다.
// 재생기·세션은 재생 화면(PlayerActivity)이 만들고 Core 에 넣어 둔다. 이 서비스는 그 세션을 맡아 알림·잠금화면 조작을 띄울 뿐
// (재생 중이면 Media3 가 알림과 함께 포그라운드로 올림). 재생 화면이 닫히면 서비스도 멈춘다.

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {
    object Core { @Volatile var session: MediaSession? = null }

    override fun onCreate() {
        super.onCreate()
        Core.session?.let { addSession(it) }
        PlayerHost.log("bg service create session=${Core.session != null}")
    }

    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? = Core.session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // 최근 앱 목록에서 밀어 닫으면 재생도 끝냄
        PlayerHost.log("bg service task removed -> stop")
        Core.session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        sessions.toList().forEach { s -> try { removeSession(s) } catch (e: IllegalArgumentException) { PlayerHost.log("bg service remove fail ${e.message}") } }
        PlayerHost.log("bg service destroy")
        super.onDestroy()
    }
}
