package net.fourdir.player

// 재생 화면 손짓 v0.2.0 — 재생 화면(PlayerView)을 감싸 손짓만 가로챈다. 그냥 톡 치기는 PlayerView 로 그대로 넘어가 조작 단추가 뜨고 숨는다.
//   두 번 톡: 왼쪽 1/3 뒤로 · 오른쪽 1/3 앞으로 · 가운데 재생/멈춤
//   길게 누르기: 누르는 동안 2배속
//   밀기: 처음 움직인 방향으로 정함 — 좌우 = 이동, 위아래 = 왼쪽 절반 밝기 / 오른쪽 절반 소리
//   두 손가락: 확대·축소
//   위쪽 띠·아래쪽 띠(조작 막대 자리)에서 시작한 밀기는 무시(진행 막대 끌기와 겹치지 않게)
//   잠금 중: 모든 손길을 먹고, 톡 치면 잠금 풀기 단추만 보여 줌

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class TouchFrame(ctx: Context, private val cb: Callbacks) : FrameLayout(ctx) {
    interface Callbacks {
        fun locked(): Boolean
        fun onLockedTap()
        fun onDouble(x: Float, w: Int)
        fun onLong()
        fun onLongEnd()
        fun onDragStart(horizontal: Boolean, x: Float, w: Int)
        fun onDrag(dx: Float, dy: Float, w: Int, h: Int)
        fun onDragEnd()
        fun onScale(f: Float)
        fun onScaleEnd()
    }

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
    private val edgeTop = (56 * resources.displayMetrics.density)
    private val edgeBottom = (110 * resources.displayMetrics.density)
    private var downX = 0f; private var downY = 0f
    private var dragging = false; private var scaling = false; private var longing = false; private var ignore = false

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { if (!ignore) cb.onDouble(e.x, width); return true }
        override fun onLongPress(e: MotionEvent) { if (!dragging && !scaling && !ignore) { longing = true; cb.onLong() } }
    })
    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; if (dragging) { dragging = false; cb.onDragEnd() }; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { cb.onScale(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { cb.onScaleEnd() }
    })

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = if (cb.locked()) true else handle(e)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (cb.locked()) { if (e.actionMasked == MotionEvent.ACTION_UP) cb.onLockedTap(); return true }
        handle(e); return true
    }

    private fun handle(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = e.x; downY = e.y; dragging = false; scaling = false; longing = false
            ignore = e.y < edgeTop || e.y > height - edgeBottom
        }
        sd.onTouchEvent(e)
        gd.onTouchEvent(e)
        if (scaling || e.pointerCount > 1) { if (e.actionMasked == MotionEvent.ACTION_UP) scaling = false; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longing && !ignore) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) { dragging = true; cb.onDragStart(abs(dx) > abs(dy), downX, width) }
                }
                if (dragging) { cb.onDrag(e.x - downX, e.y - downY, width, height); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                if (dragging) cb.onDragEnd()
                if (longing) cb.onLongEnd()
                dragging = false; longing = false
                return was
            }
        }
        return dragging
    }
}
