package com.videoref.app

// 설정 화면 단독 창 (v0.5.0: 내용은 SettingsPage — 하단 탭 '설정'과 같음). 지금은 하단 탭을 주로 쓰고, 이 창은 예전 진입로용으로 남겨 둠.

import android.os.Bundle
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.w("settings open ${Prefs.summary(this)}")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.vr_bg)) }
        Ui.applyInsets(root)
        root.addView(MaterialToolbar(this).apply {
            title = T("설정"); setNavigationIcon(R.drawable.ic_back); setNavigationContentDescription(T("뒤로"))
            setNavigationOnClickListener { finish() }
        })
        root.addView(SettingsPage(this, false).view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
