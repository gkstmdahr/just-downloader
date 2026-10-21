package com.videoref.app

import android.app.Activity
import android.content.Intent
import android.view.Menu
import com.google.android.material.bottomnavigation.BottomNavigationView

/** 하단 탭 [홈][받은 영상][설정] — 메인 화면과 브라우저(v0.5.0: 브라우저에서도 하단 탭 고정)가 같이 쓴다. */
object Nav {
    const val HOME = 1; const val LIB = 2; const val SET = 3

    fun make(a: Activity): BottomNavigationView = BottomNavigationView(a).apply {
        menu.add(Menu.NONE, HOME, 0, T("홈")).setIcon(R.drawable.ic_home)
        menu.add(Menu.NONE, LIB, 1, T("받은 영상")).setIcon(R.drawable.ic_download)
        menu.add(Menu.NONE, SET, 2, T("설정")).setIcon(R.drawable.ic_settings)
        labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
        setBackgroundColor(a.getColor(R.color.vr_nav))
        Ui.applyInsets(this, top = false, ime = false)
    }

    fun tabName(id: Int) = when (id) { LIB -> "library"; SET -> "settings"; else -> "home" }

    /** 브라우저 등 다른 화면에서 탭을 누르면 메인 화면의 그 탭으로 간다. */
    fun go(a: Activity, id: Int) {
        FileLog.w("nav from ${a.javaClass.simpleName} -> ${tabName(id)}")
        a.startActivity(Intent(a, MainActivity::class.java).putExtra("tab", tabName(id)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
