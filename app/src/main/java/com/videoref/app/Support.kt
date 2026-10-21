package com.videoref.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast

/**
 * '오류 보고' (v0.5.12: '문제 생김'(구글 폼)과 '기록 보내기'를 하나로 합침 — 사용자 요청, 4Dir 방식 참고).
 * 메일 앱을 열면서 받는 주소(SUPPORT_EMAIL) · 제목 · 본문(앱 버전·받기 프로그램·언어·기종·최근 실패 3개) · 기록 파일 첨부까지 채운다.
 * 메일 앱이 없으면 공유 창(카톡 등)으로 같은 내용을 보낸다. 홈 오른쪽 위 버튼과 설정 '도움'에서 같이 쓴다.
 */
object Support {
    /** 받는 메일 주소. 비어 있으면 주소 칸을 비운 채 열린다(사용자가 직접 입력). */
    const val SUPPORT_EMAIL = "support@4dir.net"
    /** v0.5.14: 메일 제목·본문 맨 앞 말머리(받는 쪽에서 4Dir 메일과 구분). v0.5.15: 앱 이름과 맞춤 */
    const val TAG = "[just downloader]"

    fun report(a: Activity) {
        val f = java.io.File(FileLog.path())
        val ver = "just downloader v$APP_VER / ${Engine.versionText(a)} / ${I18n.code}"
        val dev = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val fails = JobStore.snapshot().filter { it.state == JobState.FAIL }.take(3)
            .joinToString("\n") { "- ${it.url.take(200)}\n  ${it.message.take(200)}" }.ifBlank { "-" }
        val body = "$TAG\n" + T("어떤 문제가 있었나요? (영상 주소·증상)") + "\n\n\n\n" +
            "----- " + T("아래 정보는 자동으로 들어갔습니다.") + " -----\n" +
            "$ver\n$dev\n" + T("최근 실패") + ":\n$fails\n" + T("기록 파일 {0}KB 첨부", "%.1f".format(f.length() / 1024.0))
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND).setType("message/rfc822")
                .putExtra(Intent.EXTRA_SUBJECT, "$TAG " + T("오류 보고") + " v$APP_VER")
                .putExtra(Intent.EXTRA_TEXT, body)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (SUPPORT_EMAIL.isNotBlank()) send.putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            FileLog.w("report: open mail to='${SUPPORT_EMAIL}' tag='$TAG' log=${f.length()}B fails=${fails.lines().size} ver=$ver dev=$dev")
            // v0.5.16: 메일 앱만 뜨게(메신저·드라이브 등 제외). 'mailto:' 를 받는 앱 목록을 직접 찾아
            // 1개면 바로 열고, 여러 개면 앱 안 목록 창으로 고르게 한다. (운영체제 거르기(selector)는 이 폰에서 '앱 없음'으로 빠져 안 씀)
            // 메일 앱이 하나도 없으면 예전처럼 공유 창.
            val pm = a.packageManager
            val mails = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")), 0)
                .map { it.activityInfo.packageName }.distinct()
                .filter { pm.queryIntentActivities(Intent(send).setPackage(it), 0).isNotEmpty() }
            FileLog.w("report: mail apps=${mails.joinToString(",")}")
            when {
                mails.isEmpty() -> { FileLog.w("report: no mail app -> share chooser"); a.startActivity(Intent.createChooser(send, T("오류 보고"))) }
                mails.size == 1 -> { FileLog.w("report: open ${mails[0]}"); a.startActivity(Intent(send).setPackage(mails[0])) }
                else -> {
                    val names = mails.map { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                    // v0.5.17: 줄마다 앱 아이콘 + 이름(사용자 요청 — 공유 창처럼 아이콘이 보이게)
                    val icons = mails.map { pkg -> runCatching { pm.getApplicationIcon(pkg) }.getOrNull() }
                    val adapter = object : android.widget.BaseAdapter() {
                        override fun getCount() = mails.size
                        override fun getItem(p: Int) = mails[p]
                        override fun getItemId(p: Int) = p.toLong()
                        override fun getView(p: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                            val row = android.widget.LinearLayout(a).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                setPadding(Ui.dp(a, 24), Ui.dp(a, 10), Ui.dp(a, 24), Ui.dp(a, 10))
                            }
                            row.addView(android.widget.ImageView(a).apply { setImageDrawable(icons[p]) },
                                android.widget.LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)))
                            row.addView(android.widget.TextView(a).apply {
                                text = names[p]; setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                                setPadding(Ui.dp(a, 18), 0, 0, 0)
                            })
                            return row
                        }
                    }
                    androidx.appcompat.app.AlertDialog.Builder(a).setTitle(T("오류 보고"))
                        .setAdapter(adapter) { _, w ->
                            FileLog.w("report: picked ${mails[w]}")
                            runCatching { a.startActivity(Intent(send).setPackage(mails[w])) }
                                .onFailure { FileLog.e("report open fail", it); Toast.makeText(a, T("보내기 실패: {0}", it.message), Toast.LENGTH_SHORT).show() }
                        }.show()
                }
            }
        } catch (e: Exception) {
            FileLog.e("report open fail", e)
            Toast.makeText(a, T("보내기 실패: {0}", e.message), Toast.LENGTH_SHORT).show()
        }
    }
}
