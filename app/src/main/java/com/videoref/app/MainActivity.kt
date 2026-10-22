package com.videoref.app

// 메인 (v0.5.0 시안 반영): 하단 탭 [홈][받은 영상][설정]
//  홈: 왼쪽 위 앱 아이콘 · 오른쪽 위 둥근 버튼(문제 생김·설정)
//      큰 카드 = 제목·설명 / [링크 아이콘 · 주소칸 · 붙여넣기] / [받기(파란 큰 버튼)][브라우저][붙여넣고 받기]
//      주소 아래 미리보기 카드(아는 사이트) / '최근'(시계 아이콘): 받는 중 작업 + 최근 받은 파일 카드(썸네일·길이·제목·형식·용량·시각·재생)
//  받은 영상: LibraryPage   설정: SettingsPage
// 작업 카드는 통째로 다시 그리지 않고 값만 바꾼다(화면이 계속 바뀌면 자동 점검 도구가 화면을 못 읽음 — 4단계 실측).
// adb 조작(실측용): --es url <주소> --ez go true [--ez force true] [--es referer ..] [--es title ..] [--es tab home|library|settings]
//   시험용 빌드만: --ez fakeMetered true|false, --es dbg_rate 1M|0, --el dbg_minfree <바이트>|-1, --ez logdlg true, --ei theme 0|1|2, --es lang <코드>

import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {
    private lateinit var urlBox: EditText
    private lateinit var jobList: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var recentHead: View
    private lateinit var emptyText: TextView
    private lateinit var nav: BottomNavigationView
    private lateinit var homePage: View
    private lateinit var library: LibraryPage
    private lateinit var settings: SettingsPage
    // 미리보기 카드
    private lateinit var pvCard: MaterialCardView
    private lateinit var pvImg: ImageView
    private lateinit var pvTitle: TextView
    private lateinit var pvSub: TextView
    private lateinit var pvBar: LinearProgressIndicator
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var pvGen = 0
    private var pvUrl = ""
    private val cards = LinkedHashMap<String, Holder>()
    private val observedJobStates = HashMap<String, JobState>()
    private val autoBrowserFallbacks = HashSet<String>()
    private var lastDoneCount = -1
    private val onJobs: () -> Unit = {
        val snapshot = JobStore.snapshot()
        val fallback = snapshot.firstOrNull { j ->
            j.state == JobState.FAIL && observedJobStates[j.id] != JobState.FAIL && j.id !in autoBrowserFallbacks &&
                isPageUrl(j.url) && (j.message.contains("TCP", true) || j.message.contains("HTTP", true) || j.message.contains("TLS", true) || j.message.contains("추출"))
        }
        snapshot.forEach { observedJobStates[it.id] = it.state }
        renderJobs()
        val done = snapshot.count { it.state == JobState.DONE }
        if (done != lastDoneCount) { lastDoneCount = done; loadRecent(); if (nav.selectedItemId == Nav.LIB) library.refresh() }
        if (fallback != null && !isFinishing && nav.selectedItemId == Nav.HOME) {
            autoBrowserFallbacks.add(fallback.id)
            FileLog.w("auto browser fallback after page download failure id=${fallback.id} url=${fallback.url} message=${fallback.message}")
            Toast.makeText(this, T("브라우저로 엽니다. 영상을 재생한 뒤 영상 위 '받기'를 누르세요"), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", fallback.url).putExtra("k", Prefs.SECRET))
        }
    }

    private class Holder(val card: MaterialCardView, val thumb: ShapeableImageView, val title: TextView, val status: TextView,
                         val bar: LinearProgressIndicator, val action: MaterialButton)

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun c(id: Int) = getColor(id)
    private fun tint(id: Int) = ColorStateList.valueOf(getColor(id))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.w("activity create intent=${intent?.action} lang=${I18n.code}")
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            FileLog.w("request POST_NOTIFICATIONS")
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleIntent(intent) else FileLog.w("activity recreated -> intent not re-run")
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); FileLog.w("activity newIntent action=${intent.action}"); handleIntent(intent) }
    override fun onResume() {
        super.onResume()
        JobStore.snapshot().forEach { observedJobStates[it.id] = it.state }
        JobStore.listen(onJobs); renderJobs(); loadRecent(); settings.refreshVersion(); if (nav.selectedItemId == Nav.LIB) library.refresh()
    }
    override fun onPause() { JobStore.unlisten(onJobs); super.onPause() }
    // v0.4.4: 화면이 다시 만들어질 때 예약된 일이 닫힌 대기열에 들어가 죽던 문제 — 예약부터 취소
    override fun onDestroy() { main.removeCallbacksAndMessages(null); io.shutdownNow(); library.close(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77) { FileLog.w("system delete result=$resultCode"); library.refresh(); loadRecent() }
    }

    // ================= 화면 =================
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c(R.color.vr_bg)) }
        Ui.applyInsets(root, bottom = false)
        val pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        homePage = buildHome()
        library = LibraryPage(this)
        settings = SettingsPage(this, true)
        pages.addView(homePage); pages.addView(library.view); pages.addView(settings.view)
        nav = Nav.make(this).apply { setOnItemSelectedListener { applyTab(it.itemId); true } }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        showTab(Nav.HOME)
    }

    /** 코드에서 탭 바꾸기: 선택만 바꾸면 선택 알림이 applyTab 을 부른다(v0.4.1: 알림 안에서 또 바꾸면 서로 계속 불러 앱이 죽었음). */
    private fun showTab(id: Int) { if (nav.selectedItemId != id) nav.selectedItemId = id else applyTab(id) }

    private fun applyTab(id: Int) {
        homePage.visibility = if (id == Nav.HOME) View.VISIBLE else View.GONE
        library.view.visibility = if (id == Nav.LIB) View.VISIBLE else View.GONE
        settings.view.visibility = if (id == Nav.SET) View.VISIBLE else View.GONE
        if (id == Nav.LIB) library.refresh()
        FileLog.w("tab $id")
    }

    private fun roundBtn(icon: Int, desc: String, onClick: () -> Unit) = MaterialButton(this, null, M.attr.materialIconButtonFilledTonalStyle).apply {
        setIconResource(icon); contentDescription = desc; iconSize = dp(24); iconTint = tint(R.color.vr_text)
        backgroundTintList = tint(R.color.vr_card); setOnClickListener { onClick() }
    }

    private fun buildHome(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply { addView(col); setBackgroundColor(c(R.color.vr_bg)) }

        // 위: 앱 아이콘 · (빈칸) · 문제 생김 · 설정
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.app_badge_new); contentDescription = "just downloader" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(roundBtn(R.drawable.ic_log, T("오류 보고")) { Support.report(this) }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(8) })
        top.addView(roundBtn(R.drawable.ic_settings, T("설정")) { showTab(Nav.SET) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        col.addView(top)

        // 큰 카드
        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat(); cardElevation = 0f; setCardBackgroundColor(c(R.color.vr_card2)); strokeColor = c(R.color.vr_stroke); strokeWidth = dp(1)
        }
        val inC = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(18)) }
        inC.addView(TextView(this).apply { text = T("영상 받기"); setTextAppearance(M.style.TextAppearance_Material3_TitleLarge); setTextColor(c(R.color.vr_text)) })
        inC.addView(TextView(this).apply {
            text = T("영상 주소를 넣고 받기, 또는 브라우저에서 영상을 찾으세요."); setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
            setTextColor(c(R.color.vr_text2)); setPadding(0, dp(4), 0, dp(16))
        })
        // [링크 · 주소칸 | 붙여넣기]
        val inRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c(R.color.vr_card)); setStroke(dp(1), c(R.color.vr_stroke)) }
        }
        inRow.addView(ImageView(this).apply { setImageResource(R.drawable.ic_link); imageTintList = tint(R.color.vr_text) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        urlBox = EditText(this).apply {
            hint = T("영상 주소"); textSize = 15f; maxLines = 3; background = null; setTextColor(c(R.color.vr_text)); setHintTextColor(c(R.color.vr_text2))
            setPadding(dp(12), dp(14), dp(8), dp(14)); imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> startDownload(text.toString(), null, null, false); true }
        }
        urlBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { val g = ++pvGen; main.postDelayed({ if (g == pvGen) preview(s?.toString().orEmpty()) }, 700) }
        })
        inRow.addView(urlBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        inRow.addView(View(this).apply { setBackgroundColor(c(R.color.vr_stroke)) }, LinearLayout.LayoutParams(dp(1), dp(28)))
        inRow.addView(MaterialButton(this, null, M.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_paste); contentDescription = T("붙여넣기"); iconTint = tint(R.color.vr_text); setOnClickListener { paste() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        inC.addView(inRow)
        // [받기][브라우저] — 주소 입력칸 옆 클립보드 버튼으로 붙여넣기
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        btns.addView(MaterialButton(this).apply {
            text = T("받기"); setIconResource(R.drawable.ic_download); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconSize = dp(20); iconPadding = dp(4); setPadding(dp(12), paddingTop, dp(12), paddingBottom); Ui.fit(this, 9, 16)
            cornerRadius = dp(26); textSize = 16f; setOnClickListener { startDownload(urlBox.text.toString(), null, null, false) }
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) })
        btns.addView(MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            text = T("브라우저"); setIconResource(R.drawable.ic_globe); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconSize = dp(20); iconPadding = dp(4); cornerRadius = dp(26); Ui.fit(this, 11, 15)
            minWidth = 0; setPadding(dp(4), 0, dp(4), 0); textSize = 14f
            strokeColor = tint(R.color.vr_primary); strokeWidth = dp(2); contentDescription = T("브라우저에서 찾기"); setOnClickListener { openBrowser() }
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        inC.addView(btns)
        card.addView(inC)
        col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        // 미리보기 카드(아는 사이트 주소를 넣으면 채워짐) — 주소 아래
        pvCard = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); cardElevation = 0f; setCardBackgroundColor(c(R.color.vr_card)); strokeColor = c(R.color.vr_stroke); strokeWidth = dp(1); visibility = View.GONE
        }
        val pvCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val w = resources.displayMetrics.widthPixels - dp(40)
        pvImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(c(R.color.vr_primary_soft)) }
        pvCol.addView(pvImg, LinearLayout.LayoutParams(MATCH_PARENT, w * 9 / 16))
        pvBar = LinearProgressIndicator(this).apply { isIndeterminate = true; visibility = View.GONE }
        pvCol.addView(pvBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val pvTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14)) }
        pvTitle = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(c(R.color.vr_text)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        pvSub = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)); setPadding(0, dp(4), 0, 0) }
        pvTxt.addView(pvTitle); pvTxt.addView(pvSub)
        pvCol.addView(pvTxt)
        pvCard.addView(pvCol)
        col.addView(pvCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            // v0.5.0: 안내 짧게 + VPN 한 줄(사용자 요청)
            // v0.6.0: 플레이판은 유튜브를 못 받으니 안내에서 뺌
            // v0.6.3: 플레이판은 다른 서비스 이름도 뺌(스토어 스크린샷 상표 정책)
            text = T(if (Edition.PLAY) "영상 페이지 주소: 넣고 받기" else "유튜브·인스타 등: 주소 넣고 받기") + "\n" + T("그 밖의 사이트: 브라우저로 열어 재생 → 영상 위 받기") + "\n" + T("브라우저에서도 안 되면 VPN이 필요할 수 있습니다.")
            setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(c(R.color.vr_text2)); setPadding(dp(6), dp(12), dp(6), 0)
        })

        // 최근
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(24), 0, dp(10)) }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_clock); imageTintList = tint(R.color.vr_text) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(TextView(this).apply { text = T("최근"); setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(c(R.color.vr_text)); setPadding(dp(10), 0, 0, 0) })
        recentHead = head
        col.addView(head)
        jobList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(jobList)
        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentList)
        emptyText = TextView(this).apply {
            text = T("아직 받은 영상이 없습니다"); gravity = Gravity.CENTER; setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
            setTextColor(c(R.color.vr_text2)); setPadding(0, dp(24), 0, dp(24))
        }
        col.addView(emptyText)
        return scroll
    }

    private fun openBrowser() {
        val u = extractUrl(urlBox.text.toString()) ?: "https://www.google.com"
        FileLog.w("open browser $u")
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", u).putExtra("k", Prefs.SECRET))
    }

    // ---- 미리보기: 받기 프로그램이 아는 사이트만 ----
    private fun preview(raw: String) {
        if (isDestroyed || io.isShutdown) return
        val url = extractUrl(raw.trim())
        if (url == null || !Sites.isDirect(url) || Edition.blocked(url, null)) { pvCard.visibility = View.GONE; pvUrl = ""; return }   // v0.6.0: 플레이판은 유튜브 정보도 안 가져옴
        if (url == pvUrl) return
        pvUrl = url
        val g = pvGen
        pvCard.visibility = View.VISIBLE; pvBar.visibility = View.VISIBLE
        pvImg.setImageDrawable(null); pvTitle.text = T("정보 가져오는 중…"); pvSub.text = url
        FileLog.w("preview start $url")
        io.execute {
            val t0 = System.currentTimeMillis()
            try {
                Engine.ensure(this)
                val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).addOption("--no-playlist").addOption("--skip-download"))
                val bmp = info.thumbnail?.let { t ->
                    try { (URL(t).openConnection() as HttpURLConnection).run { connectTimeout = 8000; readTimeout = 8000; inputStream.use { BitmapFactory.decodeStream(it) } } } catch (e: Exception) { null }
                }
                FileLog.w("preview ok ${System.currentTimeMillis() - t0}ms title=${info.title} uploader=${info.uploader} dur=${info.duration}")
                runOnUiThread {
                    if (g != pvGen && pvUrl != url) return@runOnUiThread
                    pvBar.visibility = View.GONE
                    pvTitle.text = info.title ?: url
                    pvSub.text = listOfNotNull(info.uploader, if (info.duration > 0) dur(info.duration * 1000L) else null).joinToString(" · ")
                    if (bmp != null) pvImg.setImageBitmap(bmp)
                }
            } catch (e: Exception) {
                FileLog.w("preview fail ${System.currentTimeMillis() - t0}ms ${e.message?.take(200)}")
                runOnUiThread { if (pvUrl == url) { pvBar.visibility = View.GONE; pvTitle.text = T("미리보기를 가져오지 못했습니다"); pvSub.text = T("받기는 그대로 해볼 수 있습니다") } }
            }
        }
    }

    // ---- 최근: 카드(시안) — 왼쪽 썸네일(길이 표시) · 제목 · [형식 · 용량 · 시각] · 오른쪽 둥근 재생/취소 ----
    private fun listCard(): Triple<MaterialCardView, LinearLayout, ShapeableImageView> {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); cardElevation = 0f; setCardBackgroundColor(c(R.color.vr_card)); strokeColor = c(R.color.vr_stroke); strokeWidth = dp(1)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val thumb = ShapeableImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCorners(CornerFamily.ROUNDED, dp(12).toFloat()).build()
            setBackgroundColor(c(R.color.vr_primary_soft))
        }
        card.addView(row)
        return Triple(card, row, thumb)
    }

    private fun metaRow(parts: List<Pair<Int, String>>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        parts.forEachIndexed { i, (icon, txt) ->
            if (i > 0) r.addView(TextView(this).apply { text = " · "; setTextColor(c(R.color.vr_text2)) })
            r.addView(ImageView(this).apply { setImageResource(icon); imageTintList = tint(R.color.vr_text2) }, LinearLayout.LayoutParams(dp(14), dp(14)))
            r.addView(TextView(this).apply { text = " $txt"; setTextAppearance(M.style.TextAppearance_Material3_LabelSmall); setTextColor(c(R.color.vr_text2)); maxLines = 1 })
        }
        return r
    }

    private fun playBtn(icon: Int, desc: String) = MaterialButton(this, null, M.attr.materialIconButtonFilledTonalStyle).apply {
        setIconResource(icon); contentDescription = desc; iconSize = dp(26); iconTint = tint(R.color.vr_primary); backgroundTintList = tint(R.color.vr_primary_soft)
    }

    /** 최근 받은 파일 8개(저장소에서). 받는 중 작업은 그 위(jobList)에 따로. */
    private fun loadRecent() {
        if (io.isShutdown) return
        io.execute {
            val items = library.query().take(8)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                recentList.removeAllViews()
                val fmt = SimpleDateFormat("M/d HH:mm", I18n.locale())
                for (it in items) {
                    val (card, row, thumb) = listCard()
                    val tf = FrameLayout(this)
                    tf.addView(thumb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    dur(it.durMs)?.let { d ->
                        tf.addView(TextView(this).apply {
                            text = d; setTextColor(Color.WHITE); textSize = 11f; setPadding(dp(6), dp(1), dp(6), dp(1))
                            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.argb(190, 0, 0, 0)) }
                        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(6), dp(6)) })
                    }
                    row.addView(tf, LinearLayout.LayoutParams(dp(112), dp(66)))
                    val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
                    mid.addView(TextView(this).apply {
                        text = it.name.substringBeforeLast('.'); setTextAppearance(M.style.TextAppearance_Material3_TitleSmall); setTextColor(c(R.color.vr_text))
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    })
                    mid.addView(metaRow(listOf(R.drawable.ic_file to it.name.substringAfterLast('.', "").uppercase(),
                        R.drawable.ic_storage to "%.1fMB".format(it.size / 1048576.0), R.drawable.ic_clock to fmt.format(Date(it.added * 1000)))))
                    row.addView(mid, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    val play = playBtn(R.drawable.ic_play, T("재생"))
                    play.setOnClickListener { _ -> open(it.uri) }
                    row.addView(play, LinearLayout.LayoutParams(dp(52), dp(52)))
                    card.setOnClickListener { _ -> open(it.uri) }
                    recentList.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
                    io.execute {
                        val b = try { contentResolver.loadThumbnail(it.uri, Size(336, 200), null) } catch (e: Exception) { null }
                        if (b != null) runOnUiThread { thumb.setImageBitmap(b) }
                    }
                }
                updateEmpty()
            }
        }
    }

    private fun updateEmpty() {
        val any = recentList.childCount > 0 || jobList.childCount > 0
        emptyText.visibility = if (any) View.GONE else View.VISIBLE
    }

    private fun renderJobs() {
        // 홈에는 아직 끝나지 않았거나 실패·취소된 작업만(완료된 건 아래 '최근 받은 파일'에 나옴)
        val jobs = JobStore.snapshot().filter { it.state != JobState.DONE }.take(5)
        val ids = jobs.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { jobList.removeView(cards.remove(it)!!.card) }
        jobs.forEachIndexed { idx, j ->
            val h = cards.getOrPut(j.id) {
                val (card, row, thumb) = listCard()
                row.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(66)))
                val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
                val title = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_TitleSmall); setTextColor(c(R.color.vr_text)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val status = TextView(this).apply { setTextAppearance(M.style.TextAppearance_Material3_BodySmall); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
                val bar = LinearProgressIndicator(this).apply { trackCornerRadius = dp(3); setPadding(0, dp(6), 0, 0) }
                mid.addView(title); mid.addView(status); mid.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                row.addView(mid, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val action = playBtn(R.drawable.ic_close, T("취소"))
                row.addView(action, LinearLayout.LayoutParams(dp(52), dp(52)))
                val nh = Holder(card, thumb, title, status, bar, action)
                jobList.addView(card, idx.coerceAtMost(jobList.childCount), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
                nh
            }
            bind(h, j)
        }
        updateEmpty()
    }

    private fun bind(h: Holder, j: Job) {
        val name = j.savedName.ifBlank { j.title ?: j.url }
        if (h.title.text != name) h.title.text = name
        val running = j.state == JobState.RUN || j.state == JobState.SAVE || j.state == JobState.WAIT
        val pct = if (j.state == JobState.RUN && j.progress >= 0) " ${"%.0f".format(j.progress)}%" else ""
        val eta = if (j.state == JobState.RUN && j.eta > 0) " · " + T("{0} 남음", "${j.eta / 60}:${"%02d".format(j.eta % 60)}") else ""
        val st = if (j.state == JobState.FAIL) j.message.ifBlank { T("실패") } else "${T(j.state.label)}$pct$eta"
        if (h.status.text != st) h.status.text = st
        h.status.setTextColor(c(if (j.state == JobState.FAIL) R.color.vr_error else if (running) R.color.vr_primary else R.color.vr_text2))
        h.bar.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val ind = j.state != JobState.RUN || j.progress < 0
            if (h.bar.isIndeterminate != ind) { h.bar.visibility = View.INVISIBLE; h.bar.isIndeterminate = ind; h.bar.visibility = View.VISIBLE }
            if (!ind) h.bar.setProgressCompat(j.progress.toInt(), false)
        }
        h.action.visibility = if (running) View.VISIBLE else View.INVISIBLE
        h.action.setOnClickListener { FileLog.w("ui cancel ${j.id}"); DownloadService.cancel(this, j.id) }
        val icon = when (j.state) { JobState.FAIL -> R.drawable.ic_error; JobState.CANCEL -> R.drawable.ic_close; else -> R.drawable.ic_movie }
        h.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
        h.thumb.setImageResource(icon)
        h.thumb.imageTintList = tint(if (j.state == JobState.FAIL) R.color.vr_error else R.color.vr_primary)
    }

    private fun dur(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun open(uri: android.net.Uri) {
        FileLog.w("open video $uri")
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { toast(T("열 수 있는 앱이 없습니다")) }
    }

    // ================= 입력 처리 =================
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        // v0.3.3: 최근 앱 목록에서 다시 열면 안드로이드가 처음 받은 명령(주소·받기·공유)을 그대로 다시 준다 -> 무시
        if (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) { FileLog.w("intent from recents -> ignored (action=${i.action})"); return }
        if (i.action == Intent.ACTION_SEND) {
            val txt = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = extractUrl(txt)
            FileLog.w("share received text=${txt.take(300)} -> url=$url")
            if (url == null) { toast(T("공유받은 글에 주소가 없습니다")); return }
            // v0.3.4(A): 받기 프로그램이 아는 사이트면 바로 받기, 아니면 VideoRef 브라우저로 열기
            if (Sites.isDirect(url)) {
                FileLog.w("share -> direct download (known site)")
                showTab(Nav.HOME); urlBox.setText(url); startDownload(url, null, null, false)
            } else {
                FileLog.w("share -> open in VideoRef browser (unknown site)")
                toast(T("브라우저로 엽니다. 영상을 재생한 뒤 영상 위 '받기'를 누르세요"))
                startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url).putExtra("k", Prefs.SECRET))
            }
            return
        }
        if (Prefs.testMode(this)) {
            if (i.hasExtra("fakeMetered")) Prefs.fakeMetered = i.getBooleanExtra("fakeMetered", false)
            if (i.hasExtra("dbg_rate")) Prefs.dbgRate = i.getStringExtra("dbg_rate")?.takeIf { it.isNotBlank() && it != "0" }
            if (i.hasExtra("dbg_minfree")) Prefs.dbgMinFree = i.getLongExtra("dbg_minfree", -1L)
            FileLog.w("debug extras fakeMetered=${Prefs.fakeMetered} dbgRate=${Prefs.dbgRate} dbgMinFree=${Prefs.dbgMinFree}")
            if (i.getBooleanExtra("logdlg", false)) Support.report(this)
            if (i.hasExtra("theme")) { Prefs.set(this, "theme", i.getIntExtra("theme", 0)); Prefs.applyTheme(this) }   // 0 폰 설정 1 밝게 2 어둡게
            i.getStringExtra("lang")?.let { l ->                                                                      // 시험용 언어 바꾸기("auto" = 폰 언어)
                Prefs.setLang(this, if (l == "auto") "" else l); I18n.load(this)
                startActivity(Intent(this, MainActivity::class.java).putExtra("tab", i.getStringExtra("tab") ?: "home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                return
            }
        }
        // 탭 바꾸기(브라우저 하단 탭에서도 씀) — 화면 전환뿐이라 시험용 빌드가 아니어도 받는다
        when (i.getStringExtra("tab")) { "home" -> showTab(Nav.HOME); "library" -> showTab(Nav.LIB); "settings", "more" -> showTab(Nav.SET) }
        val url = i.getStringExtra("url") ?: return
        // v0.5.13 보안: '주소 + 받기' 명령은 앱 안에서 보낸 것(비밀값)이나 시험 모드일 때만. 다른 앱은 '공유하기'로만 주소를 넘길 수 있다.
        if (!Prefs.fromApp(i) && !Prefs.testMode(this)) { FileLog.w("SECURITY external url command ignored url=${url.take(200)} go=${i.getBooleanExtra("go", false)}"); return }
        showTab(Nav.HOME)
        urlBox.setText(url)
        FileLog.w("intent url=$url go=${i.getBooleanExtra("go", false)} force=${i.getBooleanExtra("force", false)}")
        if (i.getBooleanExtra("go", false))
            startDownload(url, i.getStringExtra("referer"), i.getStringExtra("title"), i.getBooleanExtra("force", false))
    }

    private fun extractUrl(s: String) = Regex("""https?://\S+""").find(s)?.value?.trimEnd(')', ']', '>', '"', '\'', ',', '.')

    private fun isPageUrl(raw: String): Boolean {
        val path = android.net.Uri.parse(raw).path.orEmpty().lowercase(Locale.ROOT)
        return listOf(".mp4", ".m3u8", ".mpd", ".m4v", ".webm", ".mov", ".mkv", ".m4s", ".ts").none { path.endsWith(it) }
    }

    /** @return 주소를 붙여 넣었으면 true */
    private fun paste(): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = extractUrl(t)
        FileLog.w("paste clip=${t.take(200)} -> $url")
        if (url == null) { toast(T("복사한 내용에 주소가 없습니다")); return false }
        urlBox.setText(url); return true
    }

    private fun startDownload(raw: String, referer: String?, title: String?, force: Boolean) {
        val url = extractUrl(raw.trim())
        if (url == null) { toast(T("주소를 넣어 주세요 (http 로 시작)")); return }
        // v0.3.2: 받기를 넘기면 주소칸을 비운다
        Net.start(this, url, referer, title, null, force) { urlBox.setText(""); FileLog.w("url box cleared after start") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
