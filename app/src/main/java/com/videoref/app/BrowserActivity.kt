package com.videoref.app

// 내장 브라우저 + 영상 감지 (3단계 / v0.3.0 머티리얼 개편 + 영상 위 받기 버튼).
//  - 모든 요청 가로채기(다른 출처 iframe 포함) -> 영상 후보
//  - 모든 프레임에 detect.js 주입 -> '다시 찾기'로 이미 불러온 주소 목록, 영상 위 '⬇ 받기' 버튼 -> grab 메시지
//  - 화면: 위 [뒤로][주소칸][새로고침] / 얇은 로딩 막대 / 웹 / 오른쪽 아래 떠 있는 '영상 n' 버튼 -> 아래에서 올라오는 후보 목록
// 후보를 받으면 그 주소 + 참조주소 + 본 페이지 제목 + 브라우저 UA.
// adb 조작(시험용 빌드만): --es url <주소> | --ez clear true | --ez scan true | --ez sheet true | --ez dump true |
//                         --ei pick <번호> | --ez pickpage true | --ez grab true | --ez force true

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import com.google.android.material.R as M

class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var loadBar: LinearProgressIndicator
    private var sheet: BottomSheetDialog? = null
    private var sheetList: LinearLayout? = null
    private val det = Detector()
    private val frames = LinkedHashMap<String, JavaScriptReplyProxy>()   // 프레임 주소 -> 응답 통로
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pageUrl = ""
    @Volatile private var pageTitle = ""
    private var ua = ""
    private var refreshQueued = false
    private var retryUrl = ""
    private var retryCount = 0
    private var mainFrameFailed = false
    private val maxMainFrameRetries = 2

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLog.w("browser create")
        buildUi()
        setupWeb()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.vr_bg)) }
        Ui.applyInsets(root, bottom = false)

        // 위: 뒤로 · 주소칸(둥근 상자) · 새로고침   (v0.5.0: 홈 버튼 대신 아래 하단 탭 고정)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        bar.addView(iconBtn(R.drawable.ic_back, T("뒤로")) { onBackPressedDispatcher.onBackPressed() })
        urlBox = EditText(this).apply {
            hint = T("주소 또는 검색어"); textSize = 15f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true); ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.vr_text)); setHintTextColor(getColor(R.color.vr_text2))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(getColor(R.color.vr_card)); setStroke(dp(1), getColor(R.color.vr_stroke))
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.vr_text2))
            setOnEditorActionListener { _, a, e -> if (a == EditorInfo.IME_ACTION_GO || e?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false }
        }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(iconBtn(R.drawable.ic_refresh, T("새로고침")) {
            FileLog.w("browser reload (manual; retry counter reset)")
            retryUrl = web.url ?: pageUrl; retryCount = 0; mainFrameFailed = false; web.reload()
        })
        root.addView(bar)
        loadBar = LinearProgressIndicator(this).apply { max = 100; visibility = View.INVISIBLE }
        root.addView(loadBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 웹 + 떠 있는 '영상 n' 버튼
        val frame = FrameLayout(this)
        web = WebView(this)
        // v0.5.13 보안: 진짜 손가락 터치 시각을 기록 — 페이지 스크립트가 보낸 가짜 받기 신호(grab)를 걸러내는 데 쓴다
        web.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) lastTouch = android.os.SystemClock.uptimeMillis()
            false
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fab = ExtendedFloatingActionButton(this).apply {
            text = T("영상 {0}", 0); setIconResource(R.drawable.ic_movie)
            contentDescription = T("영상 후보 열기")
            setOnClickListener { showSheet() }
        }
        frame.addView(fab, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // v0.5.0: 하단 탭 고정(사용자 요청 — 브라우저에선 하단 메뉴가 사라져 홈으로 가려면 뒤로를 여러 번 눌러야 했음).
        //   브라우저는 탭이 아니라서 아무것도 선택 안 된 모양으로 두고, 누르면 메인 화면의 그 탭으로 간다.
        val nav = Nav.make(this)
        nav.menu.setGroupCheckable(0, false, true)
        nav.setOnItemSelectedListener { Nav.go(this, it.itemId); finish(); false }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }
    private fun iconBtn(icon: Int, desc: String, onClick: () -> Unit) = MaterialButton(this, null, M.attr.materialIconButtonStyle).apply {
        setIconResource(icon); contentDescription = desc; iconSize = dp(24); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        ua = web.settings.userAgentString
        mobileUa = ua
        val okListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val okScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val wv = WebViewCompat.getCurrentWebViewPackage(this)
        FileLog.w("browser webview=${wv?.versionName} listener=$okListener docStartScript=$okScript")
        if (okListener) {
            WebViewCompat.addWebMessageListener(web, "VRBridge", setOf("*")) { _, msg: WebMessageCompat, origin: Uri, isMain, reply ->
                onFrameMessage(msg.data ?: "", origin.toString(), isMain, reply)
            }
        }
        if (okScript) {
            // v0.5.0: 영상 위 버튼 글자도 앱 언어로 — 스크립트 앞에 번역 문구를 넣어 준다
            // v0.6.0: noyt = 플레이판 -> 유튜브 페이지에는 받기 버튼을 안 띄움(detect.js ensureRoot)
            val pre = "window.__vrL=" + JSONObject().put("get", T("받기")).put("ok", T("보냄")).put("noyt", Edition.PLAY).toString() + ";\n"
            WebViewCompat.addDocumentStartJavaScript(web, pre + assets.open("detect.js").bufferedReader().use { it.readText() }, setOf("*"))
        }
        if (!okListener || !okScript) Toast.makeText(this, T("이 폰의 웹뷰는 프레임 찾기를 지원하지 않습니다(요청 감시만 동작)"), Toast.LENGTH_LONG).show()

        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title.orEmpty() }
            override fun onProgressChanged(view: WebView, p: Int) {
                loadBar.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                loadBar.setProgressCompat(p, true)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                val url = r.url.toString()
                val h = r.requestHeaders
                // 새 페이지로 넘어가면 후보·프레임 초기화. onPageStarted 보다 하위 요청이 먼저 올 수 있어(1단계 실측) 본 문서 요청 시점에 한다.
                if (r.isForMainFrame && r.method == "GET") {
                    if (retryUrl != url) { retryUrl = url; retryCount = 0 }
                    det.clear(); synchronized(frames) { frames.clear() }; pageUrl = url; pageTitle = ""
                    FileLog.w("browser main-frame request $url -> cands cleared"); queueRefresh()
                }
                val c = det.add(url, h["Range"] ?: h["range"], h["Referer"] ?: h["referer"] ?: pageUrl.ifEmpty { null }, "req")
                if (c != null) { FileLog.w("CAND add kind=${c.kind} src=req main=${r.isForMainFrame} url=$url referer=${c.referer}"); queueRefresh() }
                return null
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (retryUrl != url) { retryUrl = url; retryCount = 0 }
                mainFrameFailed = false
                pageUrl = url; if (!urlBox.hasFocus()) urlBox.setText(url); FileLog.w("browser page start $url")
            }
            // v0.4.5: 앱 실행 주소(snssdk1128://, intent:// 등)로 넘기는 사이트(도우인·스레드 등) — 웹뷰가 못 열어 '웹페이지를 사용할 수 없음'이 됐다.
            //   http(s) 가 아니면 이동하지 않고 지금 페이지에 머문다. intent:// 에 대체 웹 주소(browser_fallback_url)가 있으면 그걸 연다.
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val s = u.scheme?.lowercase().orEmpty()
                if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob" || s == "javascript") return false
                val raw = u.toString()
                val fb = if (s == "intent") Regex("""S\.browser_fallback_url=([^;]+)""").find(raw)?.groupValues?.get(1)?.let { Uri.decode(it) } else null
                FileLog.w("browser blocked app link scheme=$s main=${req.isForMainFrame} fallback=$fb url=${raw.take(200)}")
                if (fb != null && fb.startsWith("http") && req.isForMainFrame) view.loadUrl(fb)
                return true
            }
            // v0.3.4(B): 페이지를 못 열면 기록에 오류 번호를 남기고 쉬운 말로 알린다(웹뷰 기본 화면엔 net::ERR_… 만 나옴)
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                FileLog.w("browser load error main=${req.isForMainFrame} code=${err.errorCode} desc=${err.description} url=${req.url}")
                if (!req.isForMainFrame) return
                val d = err.description?.toString().orEmpty()
                mainFrameFailed = true
                if (d.contains("CONNECTION_RESET", ignoreCase = true) && retryCount < maxMainFrameRetries) {
                    retryCount++
                    val attempt = retryCount
                    val failedUrl = req.url.toString()
                    val delay = if (attempt == 1) 1500L else 3500L
                    FileLog.w("browser auto retry scheduled attempt=$attempt/$maxMainFrameRetries delayMs=$delay url=$failedUrl")
                    main.postDelayed({
                        if (!isFinishing && pageUrl == failedUrl && retryUrl == failedUrl) {
                            FileLog.w("browser auto retry now attempt=$attempt/$maxMainFrameRetries url=$failedUrl")
                            web.reload()
                        } else FileLog.w("browser auto retry skipped; page changed or activity closed")
                    }, delay)
                    return
                }
                val msg = when {
                    d.contains("CONNECTION_RESET", ignoreCase = true) -> T("사이트가 연결을 끊었습니다. 이 앱에서는 열 수 없는 사이트일 수 있습니다.")
                    d.contains("NAME_NOT_RESOLVED") -> T("주소를 찾을 수 없습니다. 주소가 맞는지 확인해 주세요.")
                    d.contains("INTERNET_DISCONNECTED") -> T("인터넷에 연결되어 있지 않습니다.")
                    d.contains("TIMED_OUT") -> T("사이트가 응답하지 않습니다.")
                    else -> T("페이지를 열 수 없습니다 ({0})", d)
                }
                Toast.makeText(this@BrowserActivity, msg, Toast.LENGTH_LONG).show()
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageTitle = view.title.orEmpty(); FileLog.w("browser page finished $url title=$pageTitle cands=${det.sorted().size}")
                if (!mainFrameFailed && url == retryUrl) retryCount = 0
            }
        }
    }

    private fun onFrameMessage(data: String, origin: String, isMain: Boolean, reply: JavaScriptReplyProxy) {
        try {
            val o = JSONObject(data)
            val href = o.optString("href")
            synchronized(frames) { frames[href] = reply }
            when (o.optString("t")) {
                "hello" -> FileLog.w("FRAME hello main=$isMain origin=$origin href=$href")
                "scan" -> {
                    val res = o.optJSONArray("res"); val vids = o.optJSONArray("vids")
                    var added = 0
                    for (i in 0 until (res?.length() ?: 0)) {
                        val u = res!!.getString(i)
                        det.add(u, null, href, "scan")?.let { added++; FileLog.w("CAND add kind=${it.kind} src=scan main=$isMain url=$u referer=$href") }
                    }
                    for (i in 0 until (vids?.length() ?: 0)) {
                        val u = vids!!.getString(i)
                        det.add(u, null, href, "tag")?.let { added++; FileLog.w("CAND add kind=${it.kind} src=tag main=$isMain url=$u referer=$href") }
                    }
                    FileLog.w("FRAME scan reason=${o.optString("reason")} main=$isMain href=$href res=${res?.length() ?: 0} tags=${vids?.length() ?: 0} newCands=$added")
                    queueRefresh()
                }
                "grab" -> onGrab(href, o.optString("src"), o.optInt("w"), o.optInt("h"), isMain)
            }
        } catch (e: Exception) { FileLog.e("frame msg parse data=${data.take(200)}", e) }
    }

    /** 영상 위 '⬇ 받기' 버튼: 그 프레임의 가장 좋은 후보 -> 없으면 영상 태그 주소(http) -> 없으면 페이지로 받기. */
    @Volatile private var lastTouch = 0L
    @Volatile private var adbGrabUntil = 0L

    private fun onGrab(frameHref: String, src: String, w: Int, h: Int, isMain: Boolean) {
        // v0.5.13 보안: 받기 신호는 웹페이지 누구나 보낼 수 있다 -> 직전(2초 안)에 사용자가 화면을 실제로 터치했을 때만 받는다.
        //   (시험 모드의 adb grab 명령은 예외: adbGrabUntil)
        val now = android.os.SystemClock.uptimeMillis()
        val sinceTouch = now - lastTouch
        if (sinceTouch > 2000 && now > adbGrabUntil) {
            FileLog.w("SECURITY grab ignored (no real touch for ${sinceTouch}ms) frame=$frameHref src=${src.take(150)}")
            return
        }
        val best = det.bestFor(frameHref)
        FileLog.w("GRAB frame=$frameHref main=$isMain src=$src video=${w}x$h best=${best?.kind}:${best?.url}")
        when {
            best != null -> pick(best, false)
            src.startsWith("http") -> { FileLog.w("GRAB -> tag src"); Net.start(this, src, frameHref, pageTitle.ifBlank { null }, ua, false) }
            else -> { FileLog.w("GRAB -> page"); pickPage(false) }
        }
    }

    private fun scanFrames(why: String) {
        val fs = synchronized(frames) { frames.toList() }
        FileLog.w("SCAN request why=$why frames=${fs.size}")
        fs.forEach { (href, p) -> try { p.postMessage("scan") } catch (e: Exception) { FileLog.w("SCAN post fail $href ${e.message}") } }
        if (why == "button") Toast.makeText(this, T("화면 안 영상 {0}곳에 다시 물어봤습니다", fs.size), Toast.LENGTH_SHORT).show()
    }

    private fun go(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        u = when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.contains('.') && !u.contains(' ') -> "https://$u"
            else -> "https://www.google.com/search?q=" + Uri.encode(u)
        }
        synchronized(frames) { frames.clear() }
        retryUrl = u; retryCount = 0; mainFrameFailed = false
        det.clear(); refresh()
        FileLog.w("browser GO $u")
        urlBox.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBox.windowToken, 0)
        web.loadUrl(u)
    }

    private fun queueRefresh() {
        synchronized(this) { if (refreshQueued) return; refreshQueued = true }
        main.postDelayed({ synchronized(this) { refreshQueued = false }; refresh() }, 300)
    }

    private fun refresh() {
        val all = det.sorted()
        val shown = all.filter { it.kind != Kind.PIECE }
        fab.text = T("영상 {0}", shown.size)
        if (shown.isEmpty()) fab.shrink() else fab.extend()
        sheetList?.let { fillSheet(it, shown, all.size - shown.size) }
    }

    // ---- 아래에서 올라오는 후보 목록 ----
    private fun showSheet() {
        val d = BottomSheetDialog(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(24)) }
        col.addView(TextView(this).apply { text = T("이 페이지의 영상"); setTextAppearance(M.style.TextAppearance_Material3_TitleLarge) })
        col.addView(TextView(this).apply {
            text = pageTitle.ifBlank { pageUrl }; setTextAppearance(M.style.TextAppearance_Material3_BodySmall)
            setTextColor(Ui.color(context, M.attr.colorOnSurfaceVariant)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(8)) }
        row.addView(MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
            text = T("다시 찾기"); setIconResource(R.drawable.ic_refresh); Ui.fit(this, 10, 14); setOnClickListener { scanFrames("button") }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(MaterialButton(this).apply {
            text = T("페이지로 받기"); setIconResource(R.drawable.ic_page); Ui.fit(this, 10, 14); setOnClickListener { d.dismiss(); pickPage(false) }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row)
        // v0.4.6: PC 화면으로 보기 — 모바일 페이지가 영상을 안 틀고 앱 설치만 권할 때(도우인 등)
        col.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = T("PC 화면으로 보기 (모바일 페이지에서 영상이 안 나올 때)"); isChecked = desktop
            setOnCheckedChangeListener { _, on -> d.dismiss(); setDesktop(on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val lst = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(lst)
        sheetList = lst
        d.setContentView(ScrollView(this).apply { addView(col) })
        d.setOnDismissListener { sheetList = null; sheet = null }
        sheet = d
        refresh()
        d.show()
        FileLog.w("sheet open cands=${det.sorted().size}")
    }

    private fun fillSheet(lst: LinearLayout, shown: List<Cand>, pieces: Int) {
        lst.removeAllViews()
        if (shown.isEmpty()) {
            lst.addView(TextView(this).apply {
                text = (if (pieces > 0) T("조각 주소만 보입니다({0}).", pieces) + " " else T("아직 찾은 영상이 없습니다.") + " ") +
                    T(if (Edition.PLAY) "영상을 재생한 뒤 '다시 찾기'를 누르거나, 인스타처럼 잘 알려진 사이트는 '페이지로 받기'를 쓰세요."
                      else "영상을 재생한 뒤 '다시 찾기'를 누르거나, 유튜브처럼 잘 알려진 사이트는 '페이지로 받기'를 쓰세요.")
                setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(Ui.color(context, M.attr.colorOnSurfaceVariant))
                setPadding(0, dp(12), 0, dp(12))
            }); return
        }
        shown.forEachIndexed { i, c ->
            val card = MaterialCardView(this, null, M.attr.materialCardViewOutlinedStyle).apply { radius = dp(14).toFloat() }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8)) }
            val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val u = Uri.parse(c.url)
            val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            chipRow.addView(Chip(this).apply {
                text = if (c.kind == Kind.LIST) T("전체(목록)") else T(c.kind.label)
                isClickable = false; chipMinHeight = dp(28).toFloat(); textSize = 12f
                if (c.kind == Kind.LIST) chipBackgroundColor = Ui.tint(context, M.attr.colorPrimaryContainer)
            })
            chipRow.addView(TextView(this).apply {
                text = "  " + T("{0}번 · {1}회", i + 1, c.hits); setTextAppearance(M.style.TextAppearance_Material3_LabelSmall)
                setTextColor(Ui.color(context, M.attr.colorOnSurfaceVariant))
            })
            mid.addView(chipRow)
            mid.addView(TextView(this).apply { text = u.lastPathSegment ?: c.url; setTextAppearance(M.style.TextAppearance_Material3_TitleSmall); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
            mid.addView(TextView(this).apply { text = u.host ?: ""; setTextAppearance(M.style.TextAppearance_Material3_BodySmall); setTextColor(Ui.color(context, M.attr.colorOnSurfaceVariant)) })
            r.addView(mid, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(MaterialButton(this).apply { text = T("받기"); setIconResource(R.drawable.ic_download); Ui.fit(this, 10, 14); setOnClickListener { sheet?.dismiss(); pick(c, false) } })
            card.addView(r)
            lst.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private var desktop = false
    private var mobileUa = ""

    /** PC 화면 보기 켜기/끄기: 브라우저 정보(UA)를 PC 크롬으로 바꾸고 다시 불러온다. 받기에도 같은 UA 를 넘긴다. */
    private fun setDesktop(on: Boolean) {
        if (on == desktop) return
        desktop = on
        val ver = Regex("""Chrome/([\d.]+)""").find(mobileUa)?.groupValues?.get(1) ?: "130.0.0.0"
        ua = if (on) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36" else mobileUa
        web.settings.userAgentString = ua
        web.settings.useWideViewPort = on; web.settings.loadWithOverviewMode = on
        FileLog.w("browser desktop=$on ua=$ua")
        Toast.makeText(this, if (on) T("PC 화면으로 봅니다") else T("모바일 화면으로 봅니다"), Toast.LENGTH_SHORT).show()
        // 모바일 전용 주소(m.)로 옮겨졌으면 원래 주소로
        val back = Sites.normalizePage(pageUrl.ifBlank { urlBox.text.toString() }).replace("://m.", "://www.")
        if (back.startsWith("http")) { det.clear(); synchronized(frames) { frames.clear() }; web.loadUrl(back) }
    }

    private fun pick(c: Cand, force: Boolean) {
        val title = pageTitle.ifBlank { null }
        FileLog.w("PICK kind=${c.kind} src=${c.src} url=${c.url} referer=${c.referer} title=$title")
        Net.start(this, c.url, c.referer, title, ua, force)
    }

    /** 페이지 주소 통째로 yt-dlp 에 (사이트를 yt-dlp 가 아는 경우 가장 확실). 제목은 yt-dlp 가 정한 것을 쓴다. */
    private fun pickPage(force: Boolean) {
        val u0 = pageUrl.ifBlank { urlBox.text.toString() }
        val u = Sites.normalizePage(u0)
        if (u != u0) FileLog.w("PICK page normalized $u0 -> $u")
        if (!u.startsWith("http")) { Toast.makeText(this, T("페이지가 없습니다"), Toast.LENGTH_SHORT).show(); return }
        FileLog.w("PICK page url=$u pageTitle=$pageTitle")
        Net.start(this, u, null, null, ua, force)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // v0.5.13 보안: 주소 열기는 앱 안에서 연 것(비밀값)이나 시험 모드일 때만 — 다른 앱이 이 브라우저로 아무 페이지나 열게 하지 못하게
        i.getStringExtra("url")?.let {
            if (Prefs.fromApp(i) || Prefs.testMode(this)) go(it)
            else FileLog.w("SECURITY external browser url ignored ${it.take(200)}")
        }
        // 아래 자동 조작(고르기·받기 등)은 시험용 빌드에서만 받는다 — 다른 앱이 몰래 받기를 시키지 못하게
        if (!Prefs.testMode(this)) return
        // v0.3.3: 최근 앱 목록에서 다시 열 때 처음 명령(받기 등)이 다시 실행되지 않게
        if (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) { FileLog.w("browser intent from recents -> auto actions ignored"); return }
        val force = i.getBooleanExtra("force", false)
        if (i.getBooleanExtra("clear", false)) { det.clear(); refresh(); FileLog.w("CAND clear (adb)") }
        if (i.getBooleanExtra("scan", false)) scanFrames("adb")
        if (i.getBooleanExtra("sheet", false) && sheet == null) showSheet()
        if (i.getBooleanExtra("dump", false)) {
            val all = det.sorted()
            FileLog.w("CAND dump n=${all.size} title=$pageTitle page=$pageUrl")
            all.forEachIndexed { k, c -> FileLog.w("  CAND[$k] ${c.kind} hits=${c.hits} src=${c.src} referer=${c.referer} url=${c.url}") }
        }
        if (i.hasExtra("desktop")) setDesktop(i.getBooleanExtra("desktop", false))
        if (i.getBooleanExtra("grab", false)) {
            val fs = synchronized(frames) { frames.toList() }
            FileLog.w("GRAB request (adb) frames=${fs.size}"); adbGrabUntil = android.os.SystemClock.uptimeMillis() + 5000
            fs.forEach { (href, p) -> try { p.postMessage("grab") } catch (e: Exception) { FileLog.w("GRAB post fail $href ${e.message}") } }
        }
        val pickIdx = i.getIntExtra("pick", -1)
        if (pickIdx >= 0) {
            val shown = det.sorted().filter { it.kind != Kind.PIECE }
            if (pickIdx < shown.size) pick(shown[pickIdx], force) else FileLog.w("PICK index $pickIdx out of ${shown.size}")
        }
        if (i.getBooleanExtra("pickpage", false)) pickPage(force)
    }

    override fun onDestroy() {
        FileLog.w("browser destroy")
        sheet?.dismiss()
        web.stopLoading(); web.destroy()
        super.onDestroy()
    }
}
