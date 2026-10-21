package com.videoref.app

// 설정 화면 내용 (v0.5.0: 하단 탭 '설정'과 SettingsActivity 가 같이 쓴다). 바꾸는 즉시 저장, 다음 받기부터 적용.
// 묶음: 화면 · 언어 · 인터넷 · 화질 · 속도 · 로그인 · 받기 프로그램 · 도움(문제 생김 · 기록 · 앱 정보)

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as M

class SettingsPage(private val a: AppCompatActivity, withTitle: Boolean) {
    private fun dp(v: Int) = Ui.dp(a, v)
    private fun c(id: Int) = a.getColor(id)
    private lateinit var verText: TextView
    val view: View

    init {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), if (withTitle) dp(16) else 0, dp(20), dp(32)) }
        if (withTitle) col.addView(TextView(a).apply { text = T("설정"); setTextAppearance(M.style.TextAppearance_Material3_HeadlineSmall); setTextColor(c(R.color.vr_text)) })

        card(col, T("화면")).apply {
            toggleRow(this, T("어두운 화면(다크 모드)"), listOf(T("폰 설정 따름") to 0, T("밝게") to 1, T("어둡게") to 2), Prefs.theme(a)) {
                Prefs.set(a, "theme", it); Prefs.applyTheme(a)
            }
            val cur = I18n.LANGS.firstOrNull { it.first == Prefs.lang(a) }?.second ?: T("폰 언어 따름")
            linkRow(this, R.drawable.ic_language, T("언어") + " (Language)", cur) { chooseLang() }
        }
        card(col, T("인터넷")).apply {
            switchRow(this, T("와이파이에서만 받기"), T("꺼 두면 모바일 데이터에서도 묻지 않고 받습니다."), Prefs.wifiOnly(a)) { Prefs.set(a, "wifiOnly", it) }
        }
        card(col, T("화질")).apply {
            switchRow(this, T("호환 우선"), T("편집 프로그램·다른 기기에서 잘 열리는 형식(H.264)을 먼저 고릅니다. 끄면 가장 좋은 화질(AV1 등)을 고릅니다."),
                Prefs.compat(a)) { Prefs.set(a, "compat", it) }
            toggleRow(this, T("최대 화질"), listOf(T("제한 없음") to 0, "1080p" to 1080, "720p" to 720), Prefs.maxRes(a)) { Prefs.set(a, "maxRes", it) }
        }
        card(col, T("속도")).apply {
            toggleRow(this, T("조각 동시 받기 수"), listOf("2" to 2, "4" to 4, T("8 (기본)") to 8), Prefs.fragments(a)) { Prefs.set(a, "fragments", it) }
            note(this, T("올려도 빨라지지 않는 사이트가 많고, 너무 많으면 사이트가 막을 수 있습니다."))
        }
        card(col, T("로그인")).apply {
            val sw = switchRow(this, T("브라우저 로그인 정보 같이 보내기"), T("내장 브라우저에서 로그인한 사이트의 영상을 받을 때만 켜세요."), Prefs.cookies(a)) { }
            sw.setOnCheckedChangeListener { b, on ->
                if (!on) { Prefs.set(a, "cookies", false); return@setOnCheckedChangeListener }
                MaterialAlertDialogBuilder(a).setTitle(T("주의"))
                    .setMessage(T("로그인한 계정으로 받게 됩니다. 많이 받으면 그 계정이 정지될 수 있습니다(실제 사례 있음). 켤까요?"))
                    .setPositiveButton(T("켜기")) { _, _ -> Prefs.set(a, "cookies", true) }
                    .setNegativeButton(T("취소")) { _, _ -> b.isChecked = false }
                    .setCancelable(false).show()
            }
        }
        card(col, T("받기 프로그램")).apply {
            verText = TextView(a).apply { setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(c(R.color.vr_text)) }
            addView(verText)
            refreshVersion()
            // v0.6.0: 플레이판은 받기 프로그램을 앱 업데이트로만 바꾼다(버튼 없음)
            if (Edition.PLAY) note(this, T("사이트가 바뀌면 받기가 안 될 수 있습니다. 받기 프로그램은 앱을 업데이트할 때 같이 바뀝니다."))
            else {
                note(this, T("사이트가 바뀌면 받기가 안 될 수 있습니다. 7일마다 자동으로 확인하고, 여기서 바로 확인할 수도 있습니다."))
                addView(MaterialButton(a, null, M.attr.materialButtonOutlinedStyle).apply {
                    text = T("받기 프로그램 최신으로"); setIconResource(R.drawable.ic_refresh); setOnClickListener { updateEngine() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        card(col, T("도움")).apply {
            // v0.5.12: 문제 생김 + 기록 보내기 -> 오류 보고 하나로(메일에 기록 파일 자동 첨부)
            linkRow(this, R.drawable.ic_error, T("오류 보고"), T("안 되는 점과 기록 파일을 메일로 보냅니다")) { Support.report(a) }
            // v0.6.0: 사이트판만 후원(플레이판은 앱 안 외부 결제 안내 금지)
            if (!Edition.PLAY) linkRow(this, R.drawable.ic_heart, T("후원하기"), T("개발을 응원해 주세요 (ko-fi)")) {
                FileLog.w("donate open ${Edition.DONATE_URL}")
                try { a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Edition.DONATE_URL))) }
                catch (e: Exception) { FileLog.e("donate open fail", e); Toast.makeText(a, T("보내기 실패: {0}", e.message), Toast.LENGTH_SHORT).show() }
            }
            linkRow(this, R.drawable.ic_info, T("앱 정보"), "just downloader v$APP_VER") { }
        }
        view = ScrollView(a).apply { addView(col); setBackgroundColor(c(R.color.vr_bg)) }
    }

    fun refreshVersion() { verText.text = T("앱 v{0} · {1}", APP_VER, Engine.versionText(a)) }

    private fun updateEngine() {
        Toast.makeText(a, T("받기 프로그램 확인 중…"), Toast.LENGTH_SHORT).show()
        Thread({
            val msg = try { Engine.ensure(a); Engine.update(a, "button") } catch (e: Exception) { FileLog.e("engine init fail(button)", e); T("준비 실패: {0}", e.message) }
            a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); refreshVersion() }
        }, "engine-update").start()
    }

    /** 언어 고르기 -> 저장 -> 앱 화면을 처음부터 다시 만든다(문구를 다시 읽게). */
    private fun chooseLang() {
        val items = listOf("" to T("폰 언어 따름")) + I18n.LANGS
        val cur = items.indexOfFirst { it.first == Prefs.lang(a) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(a).setTitle(T("언어") + " (Language)")
            .setSingleChoiceItems(items.map { it.second }.toTypedArray(), cur) { d, w ->
                d.dismiss()
                Prefs.setLang(a, items[w].first); I18n.load(a)
                a.startActivity(Intent(a, MainActivity::class.java).putExtra("tab", "settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }.show()
    }

    // ---- 부품 ----
    private fun card(col: LinearLayout, title: String): LinearLayout {
        col.addView(TextView(a).apply {
            text = title; setTextAppearance(M.style.TextAppearance_Material3_LabelLarge); setTextColor(c(R.color.vr_primary))
            setPadding(dp(4), dp(18), 0, dp(6))
        })
        val card = MaterialCardView(a).apply {
            radius = dp(22).toFloat(); cardElevation = 0f; setCardBackgroundColor(c(R.color.vr_card)); strokeColor = c(R.color.vr_stroke); strokeWidth = dp(1)
        }
        val inner = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14)) }
        card.addView(inner)
        col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return inner
    }

    private fun note(p: LinearLayout, t: String) = p.addView(TextView(a).apply {
        text = t; setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)); setPadding(0, dp(2), 0, 0)
    })

    private fun linkRow(p: LinearLayout, icon: Int, title: String, sub: String, onClick: () -> Unit) {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); isClickable = true; isFocusable = true
            val tv = android.util.TypedValue(); a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        r.addView(ImageView(a).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(c(R.color.vr_primary)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val t = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        t.addView(TextView(a).apply { text = title; setTextAppearance(M.style.TextAppearance_Material3_BodyLarge); setTextColor(c(R.color.vr_text)) })
        t.addView(TextView(a).apply { text = sub; setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)) })
        r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(ImageView(a).apply { setImageResource(R.drawable.ic_chevron); imageTintList = android.content.res.ColorStateList.valueOf(c(R.color.vr_text2)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        p.addView(r)
    }

    private fun switchRow(p: LinearLayout, t: String, desc: String, on: Boolean, cb: (Boolean) -> Unit): MaterialSwitch {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(a).apply { text = t; setTextAppearance(M.style.TextAppearance_Material3_BodyLarge); setTextColor(c(R.color.vr_text)) })
        txt.addView(TextView(a).apply { text = desc; setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)) })
        row.addView(txt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(a).apply { isChecked = on; setOnCheckedChangeListener { _, v -> cb(v) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        p.addView(row)
        return sw
    }

    private fun toggleRow(p: LinearLayout, t: String, opts: List<Pair<String, Int>>, cur: Int, cb: (Int) -> Unit) {
        p.addView(TextView(a).apply { text = t; setTextAppearance(M.style.TextAppearance_Material3_BodyLarge); setTextColor(c(R.color.vr_text)); setPadding(0, dp(10), 0, dp(6)) })
        val g = MaterialButtonToggleGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        opts.forEach { (label, v) ->
            g.addView(MaterialButton(a, null, M.attr.materialButtonOutlinedStyle).apply { text = label; id = View.generateViewId(); tag = v; setPadding(dp(4), paddingTop, dp(4), paddingBottom); Ui.fit(this, 9, 14) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        for (k in 0 until g.childCount) if (g.getChildAt(k).tag == cur) g.check(g.getChildAt(k).id)
        g.addOnButtonCheckedListener { grp, id, checked -> if (checked) cb(grp.findViewById<View>(id).tag as Int) }
        p.addView(g, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}
