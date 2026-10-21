package com.videoref.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import androidx.annotation.AttrRes

/** 화면 공통 도우미 (4단계 UI 개편). */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /**
     * v0.5.12: 글자가 길면 줄바꿈하지 말고 한 줄 안에서 글자를 줄인다(minSp~maxSp).
     * 언어를 바꾸면 버튼 글자(Download·Descargar·Herunterladen 등)가 두 줄로 꺾이거나 "Wie a…"처럼 잘렸음(2026-10-22 실측).
     */
    fun fit(v: android.widget.TextView, minSp: Int, maxSp: Int) {
        v.maxLines = 1; v.ellipsize = null
        v.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }

    /** 테마 색 읽기(동적 색 포함). */
    fun color(c: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        c.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) c.getColor(tv.resourceId) else tv.data
    }

    fun tint(c: Context, @AttrRes attr: Int) = ColorStateList.valueOf(color(c, attr))

    /** 안드로이드 15+ 화면 끝까지 그리기 대응: 상태바·내비바(·키보드) 높이만큼 안쪽으로. */
    fun applyInsets(v: View, top: Boolean = true, bottom: Boolean = true, ime: Boolean = true) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        v.setOnApplyWindowInsetsListener { view, ins ->
            val it: Int; val ib: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val m = WindowInsets.Type.systemBars() or (if (ime) WindowInsets.Type.ime() else 0)
                val x = ins.getInsets(m); it = x.top; ib = x.bottom
            } else { @Suppress("DEPRECATION") run { it = ins.systemWindowInsetTop; ib = ins.systemWindowInsetBottom } }
            view.setPadding(l, t + (if (top) it else 0), r, b + (if (bottom) ib else 0)); ins
        }
    }

    fun Activity.dp(v: Int) = Ui.dp(this, v)
}
