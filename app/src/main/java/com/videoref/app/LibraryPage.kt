package com.videoref.app

// '받은 영상' 탭 (v0.4.0). 휴대폰 동영상/VideoRef 폴더의 실제 파일을 읽어 보여준다 -> 앱을 껐다 켜도 남는다.
// 큰 미리보기 카드(제목 · 길이 · 크기 · 받은 날짜 · 재생), 거르기 칩(전체/받는 중/실패), 정렬(최신순/이름순),
// 길게 누르면 열기/공유/삭제(삭제는 확인창). 받는 중·실패는 앱이 켜져 있는 동안의 작업 목록(JobStore)에서.

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.google.android.material.R as M

class LibraryPage(private val a: AppCompatActivity) {
    data class Item(val uri: Uri, val name: String, val durMs: Long, val size: Long, val added: Long)

    private enum class Filter(val label: String) { ALL("전체"), RUNNING("받는 중"), FAILED("실패") }

    private fun dp(v: Int) = Ui.dp(a, v)
    private val io = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(40)
    private var filter = Filter.ALL
    // v0.4.3: 정렬은 메뉴에서 고른다(사용자 요청). 0=최신순 1=오래된순 2=이름순 3=큰 파일순 4=긴 영상순
    private val SORTS = listOf(T("최신순"), T("오래된순"), T("이름순"), T("큰 파일순"), T("긴 영상순"))
    private var sortMode = 0
    private var items = listOf<Item>()
    private val selectedUris = linkedSetOf<String>()
    private var selectionMode = false
    private lateinit var rv: RecyclerView
    private lateinit var empty: TextView
    private lateinit var sortBtn: MaterialButton
    private lateinit var selectBtn: MaterialButton
    private lateinit var deleteSelectedBtn: MaterialButton
    private val adapter = Adapter()
    val view: View = build()

    private fun build(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(8), dp(4)) }
        head.addView(TextView(a).apply { text = T("받은 영상"); setTextAppearance(M.style.TextAppearance_Material3_HeadlineSmall); Ui.fit(this, 16, 24) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        selectBtn = MaterialButton(a, null, M.attr.materialButtonOutlinedStyle).apply {
            text = T("선택 삭제"); minWidth = 0; setPadding(dp(10), 0, dp(10), 0); Ui.fit(this, 10, 14)
            setOnClickListener {
                selectionMode = !selectionMode
                if (!selectionMode) selectedUris.clear()
                show()
            }
        }
        head.addView(selectBtn, LinearLayout.LayoutParams(0, dp(40), 0.75f).apply { marginEnd = dp(4) })   // v0.5.12: 폭을 정해 두고 글자를 줄임(긴 번역이 제목을 밀어냈음)
        deleteSelectedBtn = MaterialButton(a).apply {
            setIconResource(R.drawable.ic_delete); iconSize = dp(18); minWidth = 0; setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { confirmDeleteSelected() }
        }
        head.addView(deleteSelectedBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(4) })
        sortBtn = MaterialButton(a, null, M.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_sort); contentDescription = T("정렬")
            setOnClickListener { v ->
                val pm = android.widget.PopupMenu(a, v)
                SORTS.forEachIndexed { i, s -> pm.menu.add(0, i, i, s).isCheckable = true; if (i == sortMode) pm.menu.findItem(i).isChecked = true }
                pm.menu.setGroupCheckable(0, true, true)
                pm.setOnMenuItemClickListener { mi -> sortMode = mi.itemId; FileLog.w("library sort ${SORTS[sortMode]}"); show(); true }
                pm.show()
            }
        }
        head.addView(sortBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(head)

        val chips = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true; setPadding(dp(16), 0, dp(16), dp(4)) }
        Filter.values().forEach { f ->
            chips.addView(Chip(a, null, M.attr.chipStyle).apply {
                text = T(f.label); isCheckable = true; isChecked = f == filter; id = View.generateViewId(); tag = f
                setOnClickListener {
                    filter = f
                    if (filter != Filter.ALL) { selectionMode = false; selectedUris.clear() }
                    FileLog.w("library filter $f"); refresh()
                }
            })
        }
        col.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val frame = FrameLayout(a)
        rv = RecyclerView(a).apply { layoutManager = LinearLayoutManager(a); adapter = this@LibraryPage.adapter; clipToPadding = false; setPadding(dp(16), dp(4), dp(16), dp(16)) }
        frame.addView(rv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        empty = TextView(a).apply {
            gravity = Gravity.CENTER; setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
            setTextColor(Ui.color(a, M.attr.colorOnSurfaceVariant)); setPadding(dp(24), dp(48), dp(24), dp(24))
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_movie, 0, 0); compoundDrawablePadding = dp(8)
            compoundDrawableTintList = Ui.tint(a, M.attr.colorOutline)
        }
        frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    /** 저장소에서 다시 읽기(백그라운드) -> 화면 갱신. */
    fun refresh() {
        if (filter != Filter.ALL) { show(); return }
        if (io.isShutdown) return
        io.execute {
            val t0 = System.currentTimeMillis()
            val list = query()
            a.runOnUiThread {
                items = list
                selectedUris.retainAll(list.mapTo(HashSet()) { it.uri.toString() })
                show()
            }
            FileLog.w("library query n=${list.size} ${System.currentTimeMillis() - t0}ms")
        }
    }

    /** 저장소의 동영상/VideoRef 파일(최신순). 홈의 최근 목록도 이걸 쓴다(백그라운드에서 부를 것). */
    fun query(): List<Item> {
        val out = ArrayList<Item>()
        val cols = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED)
        try {
            a.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(Saver.REL_DIR), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(Item(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getLong(4)))
                }
            }
        } catch (e: Exception) { FileLog.e("library query fail", e) }
        return out
    }

    private fun show() {
        updateSelectionActions()
        val n: Int
        when (filter) {
            Filter.ALL -> {
                n = items.size
                val sorted = when (sortMode) {
                    1 -> items.sortedBy { it.added }; 2 -> items.sortedBy { it.name.lowercase() }
                    3 -> items.sortedByDescending { it.size }; 4 -> items.sortedByDescending { it.durMs }
                    else -> items.sortedByDescending { it.added }
                }
                adapter.set(sorted, null)
            }
            Filter.RUNNING -> { val js = JobStore.snapshot().filter { it.state == JobState.RUN || it.state == JobState.WAIT || it.state == JobState.SAVE }; n = js.size; adapter.set(emptyList(), js) }
            Filter.FAILED -> { val js = JobStore.snapshot().filter { it.state == JobState.FAIL }; n = js.size; adapter.set(emptyList(), js) }
        }
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        empty.text = when (filter) { Filter.ALL -> T("아직 받은 영상이 없습니다"); Filter.RUNNING -> T("받는 중인 영상이 없습니다"); Filter.FAILED -> T("실패한 받기가 없습니다") }
    }

    private fun updateSelectionActions() {
        if (!::selectBtn.isInitialized) return
        selectBtn.visibility = if (filter == Filter.ALL) View.VISIBLE else View.GONE
        selectBtn.text = T(if (selectionMode) "취소" else "선택 삭제")
        deleteSelectedBtn.visibility = if (selectionMode && filter == Filter.ALL) View.VISIBLE else View.GONE
        deleteSelectedBtn.text = T("삭제 {0}", selectedUris.size)
        deleteSelectedBtn.isEnabled = selectedUris.isNotEmpty()
        sortBtn.visibility = if (selectionMode) View.GONE else View.VISIBLE
    }

    private fun toggleSelection(item: Item) {
        val key = item.uri.toString()
        if (!selectedUris.add(key)) selectedUris.remove(key)
        show()
    }

    // ---- 목록 ----
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var files = listOf<Item>(); var jobs: List<Job>? = null
        fun set(f: List<Item>, j: List<Job>?) { files = f; jobs = j; notifyDataSetChanged() }
        override fun getItemCount() = jobs?.size ?: files.size
        override fun getItemViewType(position: Int) = if (jobs != null) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = if (type == 0) FileVH(fileCard()) else JobVH(jobRow())
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) { if (h is FileVH) h.bind(files[pos]) else (h as JobVH).bind(jobs!![pos]) }
    }

    private inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewWithTag("img"); val title: TextView = v.findViewWithTag("title")
        val left: TextView = v.findViewWithTag("left"); val right: TextView = v.findViewWithTag("right"); val play: MaterialButton = v.findViewWithTag("play")
        val check: TextView = v.findViewWithTag("check")
        fun bind(it: Item) {
            title.text = it.name.substringBeforeLast('.')
            left.text = listOfNotNull(dur(it.durMs), "%.1fMB".format(it.size / 1048576.0)).joinToString(" · ")
            right.text = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, I18n.locale()).format(Date(it.added * 1000))
            val selected = it.uri.toString() in selectedUris
            check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            check.text = if (selected) "✓" else ""
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) Ui.color(a, M.attr.colorPrimary) else Color.argb(150, 20, 23, 32))
                setStroke(dp(1), Color.WHITE)
            }
            play.visibility = if (selectionMode) View.GONE else View.VISIBLE
            itemView.setOnClickListener { _ -> if (selectionMode) toggleSelection(it) else open(it) }
            play.setOnClickListener { _ -> open(it) }
            itemView.setOnLongClickListener { _ -> menu(it); true }
            val key = it.uri.toString()
            img.tag = key
            val cached = thumbs.get(key)
            if (cached != null) img.setImageBitmap(cached) else {
                img.setImageDrawable(null)
                if (io.isShutdown) return
        io.execute {
                    val b = try { a.contentResolver.loadThumbnail(it.uri, Size(640, 360), null) } catch (e: Exception) { null }
                    if (b != null) thumbs.put(key, b)
                    a.runOnUiThread { if (img.tag == key && b != null) img.setImageBitmap(b) }
                }
            }
        }
    }

    private inner class JobVH(v: View) : RecyclerView.ViewHolder(v) {
        val t1: TextView = v.findViewWithTag("t1"); val t2: TextView = v.findViewWithTag("t2")
        fun bind(j: Job) {
            t1.text = j.savedName.ifBlank { j.title ?: j.url }
            val pct = if (j.state == JobState.RUN && j.progress >= 0) " ${"%.0f".format(j.progress)}%" else ""
            t2.text = if (j.state == JobState.FAIL) j.message else "${j.state.label}$pct"
            t2.setTextColor(Ui.color(a, if (j.state == JobState.FAIL) M.attr.colorError else M.attr.colorPrimary))
        }
    }

    private fun fileCard(): View {
        val card = MaterialCardView(a).apply { radius = dp(18).toFloat(); cardElevation = 0f; layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        val f = FrameLayout(a)
        val img = ImageView(a).apply { tag = "img"; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(40, 40, 48)) }
        val w = a.resources.displayMetrics.widthPixels - dp(32)
        f.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, w * 9 / 16))
        // 아래쪽 어둡게(글자 잘 보이게)
        f.addView(View(a).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT)) },
            FrameLayout.LayoutParams(MATCH_PARENT, w * 9 / 16 / 2, Gravity.BOTTOM))
        val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(72), dp(12)) }
        txt.addView(TextView(a).apply { tag = "title"; setTextAppearance(M.style.TextAppearance_Material3_TitleMedium); setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val meta = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        meta.addView(TextView(a).apply { tag = "left"; setTextAppearance(M.style.TextAppearance_Material3_LabelMedium); setTextColor(Color.argb(230, 255, 255, 255)) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        meta.addView(TextView(a).apply { tag = "right"; setTextAppearance(M.style.TextAppearance_Material3_LabelMedium); setTextColor(Color.argb(230, 255, 255, 255)) })
        txt.addView(meta)
        f.addView(txt, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        f.addView(MaterialButton(a, null, M.attr.materialIconButtonFilledTonalStyle).apply { tag = "play"; setIconResource(R.drawable.ic_play); contentDescription = T("재생"); iconSize = dp(26) },
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
        f.addView(TextView(a).apply {
            tag = "check"; gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.WHITE); visibility = View.GONE
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        card.addView(f)
        return card
    }

    private fun jobRow(): View {
        val card = MaterialCardView(a, null, M.attr.materialCardViewOutlinedStyle).apply { radius = dp(14).toFloat(); layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) } }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        col.addView(TextView(a).apply { tag = "t1"; setTextAppearance(M.style.TextAppearance_Material3_TitleSmall); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        col.addView(TextView(a).apply { tag = "t2"; setTextAppearance(M.style.TextAppearance_Material3_BodySmall) })
        card.addView(col)
        return card
    }

    private fun dur(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun open(it: Item) {
        FileLog.w("library open ${it.uri} ${it.name}")
        try { a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { Toast.makeText(a, T("열 수 있는 앱이 없습니다"), Toast.LENGTH_SHORT).show() }
    }

    private fun menu(it: Item) {
        FileLog.w("library menu ${it.name}")
        MaterialAlertDialogBuilder(a).setTitle(it.name)
            .setItems(arrayOf(T("열기"), T("공유"), T("삭제"))) { _, w ->
                when (w) {
                    0 -> open(it)
                    1 -> {
                        FileLog.w("library share ${it.uri}")
                        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, it.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), T("영상 공유")))
                    }
                    2 -> MaterialAlertDialogBuilder(a).setTitle(T("삭제할까요?")).setIcon(R.drawable.ic_delete)
                        .setMessage(it.name + "\n" + T("휴대폰에서 이 영상 파일을 지웁니다. 되돌릴 수 없습니다."))
                        .setPositiveButton(T("삭제")) { _, _ -> delete(it) }.setNegativeButton(T("취소"), null).show()
                }
            }.show()
    }

    private fun delete(it: Item) {
        try {
            val n = a.contentResolver.delete(it.uri, null, null)
            FileLog.w("library delete ${it.uri} name=${it.name} rows=$n")
            Toast.makeText(a, if (n > 0) T("삭제했습니다") else T("삭제하지 못했습니다"), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // 이 앱이 만들지 않은 파일(재설치 전 파일 등): 안드로이드 확인창으로 지우기
            FileLog.w("library delete needs system confirm ${e.javaClass.simpleName}")
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(a.contentResolver, listOf(it.uri))
                a.startIntentSenderForResult(pi.intentSender, 77, null, 0, 0, 0)
            }
        } catch (e: Exception) { FileLog.e("library delete fail", e) }
        refresh()
    }

    private fun confirmDeleteSelected() {
        val selected = items.filter { it.uri.toString() in selectedUris }
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(a).setTitle(T("선택한 영상 삭제"))
            .setMessage(T("선택한 영상 {0}개를 휴대폰에서 삭제합니다. 되돌릴 수 없습니다.", selected.size))
            .setPositiveButton(T("삭제")) { _, _ -> deleteSelected(selected) }
            .setNegativeButton(T("취소"), null).show()
    }

    private fun deleteSelected(selected: List<Item>) {
        io.execute {
            var deleted = 0
            val approval = ArrayList<Uri>()
            selected.forEach { item ->
                try {
                    val n = a.contentResolver.delete(item.uri, null, null)
                    deleted += n
                    FileLog.w("library bulk delete ${item.uri} name=${item.name} rows=$n")
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= 30) approval.add(item.uri)
                    else FileLog.e("library bulk delete denied ${item.name}", e)
                } catch (e: Exception) { FileLog.e("library bulk delete fail ${item.name}", e) }
            }
            a.runOnUiThread {
                selectionMode = false; selectedUris.clear(); show()
                if (approval.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        val pi = MediaStore.createDeleteRequest(a.contentResolver, approval)
                        a.startIntentSenderForResult(pi.intentSender, 77, null, 0, 0, 0)
                    } catch (e: Exception) { FileLog.e("library bulk delete confirmation failed", e) }
                }
                val message = when {
                    approval.isNotEmpty() -> T("{0}개 삭제 완료. 나머지는 시스템 확인이 필요합니다.", deleted)
                    deleted > 0 -> T("선택한 영상 {0}개 삭제 완료", deleted)
                    else -> T("삭제하지 못했습니다")
                }
                Toast.makeText(a, message, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    fun close() { io.shutdownNow() }
}
