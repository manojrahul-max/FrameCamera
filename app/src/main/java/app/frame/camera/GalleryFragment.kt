package app.frame.camera

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GalleryFragment : Fragment(R.layout.fragment_gallery) {

    private class Media(val uri: Uri, val id: Long, val name: String, val added: Long, val video: Boolean) {
        val key: String get() = (if (video) "v" else "p") + id
    }

    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var segPhotos: TextView
    private lateinit var segVideos: TextView
    private lateinit var viewButton: ImageView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val thumbCache = LruCache<String, Bitmap>(90)

    private var showVideos = false
    private var gridMode = true
    private var sortMode = 0
    private var items: List<Media> = emptyList()
    private val selected = LinkedHashSet<String>()

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                forgetSelectedFavorites()
                clearSelection()
                load()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences("frame", Context.MODE_PRIVATE)

        list = view.findViewById(R.id.mediaList)
        emptyText = view.findViewById(R.id.emptyText)
        segPhotos = view.findViewById(R.id.segPhotos)
        segVideos = view.findViewById(R.id.segVideos)
        viewButton = view.findViewById(R.id.viewButton)
        selectionBar = view.findViewById(R.id.selectionBar)
        selectionCount = view.findViewById(R.id.selectionCount)

        segPhotos.setOnClickListener { switchSection(false) }
        segVideos.setOnClickListener { switchSection(true) }
        viewButton.setOnClickListener {
            gridMode = !gridMode
            applyLayout()
        }
        view.findViewById<View>(R.id.sortButton).setOnClickListener { chooseSort() }
        view.findViewById<View>(R.id.actClose).setOnClickListener { clearSelection() }
        view.findViewById<View>(R.id.actFavorite).setOnClickListener { favoriteSelected() }
        view.findViewById<View>(R.id.actShare).setOnClickListener { shareSelected() }
        view.findViewById<View>(R.id.actDelete).setOnClickListener { deleteSelected() }

        updateSegments()
        list.doOnSizeReady { applyLayout() }
    }

    private fun View.doOnSizeReady(block: () -> Unit) {
        if (width > 0) {
            block()
        } else {
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int
                ) {
                    if (v.width > 0) {
                        v.removeOnLayoutChangeListener(this)
                        block()
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    // ----------------------------------------------------------------- data

    private fun load() {
        val resolver = requireContext().contentResolver
        val video = showVideos
        executor.execute {
            val result = query(resolver, video)
            handler.post {
                if (!isAdded) return@post
                items = sorted(result)
                selected.retainAll(items.map { it.key }.toSet())
                refresh()
            }
        }
    }

    private fun query(resolver: android.content.ContentResolver, video: Boolean): List<Media> {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val path = if (video) "Movies/Frame%" else "Pictures/Frame%"
        val out = ArrayList<Media>()
        try {
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(path),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(
                        Media(
                            ContentUris.withAppendedId(collection, id), id,
                            c.getString(nameCol) ?: "", c.getLong(dateCol), video
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // An unreadable library shows as empty.
        }
        return out
    }

    private fun favorites(): Set<String> = prefs.getStringSet(KEY_FAV, emptySet()) ?: emptySet()

    private fun isFavorite(m: Media) = favorites().contains(m.key)

    private fun saveFavorites(set: Set<String>) {
        prefs.edit().putStringSet(KEY_FAV, HashSet(set)).apply()
    }

    private fun sorted(source: List<Media>): List<Media> = when (sortMode) {
        1 -> source.sortedBy { it.added }
        2 -> {
            val fav = favorites()
            source.sortedWith(compareByDescending<Media> { fav.contains(it.key) }.thenByDescending { it.added })
        }
        else -> source.sortedByDescending { it.added }
    }

    // ------------------------------------------------------------------- UI

    private fun switchSection(videos: Boolean) {
        if (showVideos == videos) return
        showVideos = videos
        clearSelection()
        updateSegments()
        load()
    }

    private fun updateSegments() {
        val on = ContextCompat.getColor(requireContext(), R.color.bg)
        val off = ContextCompat.getColor(requireContext(), R.color.ink_dim)
        segPhotos.setBackgroundResource(if (showVideos) 0 else R.drawable.seg_on)
        segVideos.setBackgroundResource(if (showVideos) R.drawable.seg_on else 0)
        segPhotos.setTextColor(if (showVideos) off else on)
        segVideos.setTextColor(if (showVideos) on else off)
    }

    private fun applyLayout() {
        list.layoutManager =
            if (gridMode) GridLayoutManager(requireContext(), 3) else LinearLayoutManager(requireContext())
        list.adapter = MediaAdapter()
        viewButton.setImageResource(if (gridMode) R.drawable.ic_list else R.drawable.ic_gallery)
        refresh()
    }

    private fun refresh() {
        list.adapter?.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        selectionBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = "${selected.size} selected"
    }

    private fun chooseSort() {
        AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(arrayOf("Newest first", "Oldest first", "Favorites first"), sortMode) { dialog, which ->
                sortMode = which
                items = sorted(items)
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    private fun clearSelection() {
        selected.clear()
        refresh()
    }

    private fun selectedItems(): List<Media> = items.filter { selected.contains(it.key) }

    private fun onTap(item: Media) {
        if (selected.isNotEmpty()) {
            toggle(item)
        } else {
            startActivity(ViewerActivity.intent(requireContext(), item.uri, item.video))
        }
    }

    private fun toggle(item: Media) {
        if (!selected.add(item.key)) selected.remove(item.key)
        refresh()
    }

    // -------------------------------------------------------------- actions

    private fun favoriteSelected() {
        val chosen = selectedItems()
        if (chosen.isEmpty()) return
        val fav = HashSet(favorites())
        val allFav = chosen.all { fav.contains(it.key) }
        for (m in chosen) if (allFav) fav.remove(m.key) else fav.add(m.key)
        saveFavorites(fav)
        clearSelection()
        items = sorted(items)
        refresh()
    }

    private fun shareSelected() {
        val chosen = selectedItems()
        if (chosen.isEmpty()) return
        val uris = ArrayList(chosen.map { it.uri })
        val mime = if (showVideos) "video/*" else "image/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun deleteSelected() {
        val chosen = selectedItems()
        if (chosen.isEmpty()) return
        val resolver = requireContext().contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android shows its own confirmation, and works for files from any earlier install.
            val request = MediaStore.createDeleteRequest(resolver, chosen.map { it.uri })
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete ${chosen.size} item(s)?")
                .setMessage("This removes them from your phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    var failed = 0
                    for (m in chosen) {
                        try {
                            resolver.delete(m.uri, null, null)
                        } catch (e: SecurityException) {
                            failed++
                        }
                    }
                    if (failed > 0) Toast.makeText(requireContext(), "Could not delete $failed item(s)", Toast.LENGTH_SHORT).show()
                    forgetSelectedFavorites()
                    clearSelection()
                    load()
                }
                .show()
        }
    }

    private fun forgetSelectedFavorites() {
        val fav = HashSet(favorites())
        fav.removeAll(selected)
        saveFavorites(fav)
    }

    // -------------------------------------------------------------- adapter

    private inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val playBadge: View = v.findViewById(R.id.playBadge)
            val favBadge: View = v.findViewById(R.id.favBadge)
            val caption: TextView = v.findViewById(R.id.caption)
            val selectOverlay: View = v.findViewById(R.id.selectOverlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            val d = resources.displayMetrics.density
            val height = if (gridMode) {
                val usable = list.width - list.paddingLeft - list.paddingRight
                usable / 3 - (8 * d).toInt()
            } else {
                (200 * d).toInt()
            }
            v.layoutParams.height = height
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            h.playBadge.visibility = if (item.video) View.VISIBLE else View.GONE
            h.favBadge.visibility = if (isFavorite(item)) View.VISIBLE else View.GONE
            h.selectOverlay.visibility = if (selected.contains(item.key)) View.VISIBLE else View.GONE
            if (gridMode) {
                h.caption.visibility = View.GONE
            } else {
                h.caption.visibility = View.VISIBLE
                h.caption.text = item.name + "   " +
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.added * 1000L))
            }
            h.itemView.setOnClickListener { onTap(item) }
            h.itemView.setOnLongClickListener {
                toggle(item)
                true
            }

            h.thumb.tag = item.key
            val cached = thumbCache.get(item.key)
            if (cached != null) {
                h.thumb.setImageBitmap(cached)
                return
            }
            h.thumb.setImageDrawable(null)
            val resolver = requireContext().contentResolver
            executor.execute {
                val bitmap = try {
                    resolver.loadThumbnail(item.uri, Size(360, 360), null)
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    thumbCache.put(item.key, bitmap)
                    handler.post {
                        if (h.thumb.tag == item.key) h.thumb.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_FAV = "favorites"
    }
}
