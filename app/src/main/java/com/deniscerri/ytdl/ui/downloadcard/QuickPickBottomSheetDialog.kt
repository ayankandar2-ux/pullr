package com.deniscerri.ytdl.ui.downloadcard

import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.FormatViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.InstantPreviewFetcher
import com.deniscerri.ytdl.util.Extensions.isYoutubeURL
import com.deniscerri.ytdl.util.Extensions.loadThumbnail
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact "quick pick" download sheet shown when a link is shared into the app.
 * Offers Fast / Classic MP3 (audio) and Fast 480p / High quality 720p (video)
 * presets, with a "More formats" escape hatch to the full format list and an
 * "Advanced options" escape hatch to the detailed [DownloadBottomSheetDialog].
 */
class QuickPickBottomSheetDialog : BottomSheetDialogFragment() {

    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var formatViewModel: FormatViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var result: ResultItem
    private var initialType: DownloadType = DownloadType.video
    private var ignoreDuplicates: Boolean = false

    private lateinit var view: View

    private enum class Preset { MUSIC_FAST, MUSIC_MP3, VIDEO_FAST, VIDEO_HQ }
    private var selectedPreset: Preset = Preset.VIDEO_FAST

    private var musicFastFormat: Format? = null
    private var videoFastFormat: Format? = null
    private var videoHqFormat: Format? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        formatViewModel = ViewModelProvider(requireActivity())[FormatViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        if (res == null) {
            dismiss()
            return
        }
        result = res
        initialType = (arguments?.getSerializable("type") as? DownloadType) ?: DownloadType.video
        ignoreDuplicates = arguments?.getBoolean("ignore_duplicates") == true
        selectedPreset = if (initialType == DownloadType.audio) Preset.MUSIC_FAST else Preset.VIDEO_FAST
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        view = inflater.inflate(R.layout.quick_pick_bottom_sheet, container, false)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val shimmer = view.findViewById<ShimmerFrameLayout>(R.id.quick_pick_shimmer)
        val content = view.findViewById<LinearLayout>(R.id.quick_pick_content)
        val videoPreviewRow = view.findViewById<LinearLayout>(R.id.quick_pick_video_preview)
        val videoPreviewThumbnail = view.findViewById<android.widget.ImageView>(R.id.quick_pick_video_thumbnail)
        val videoPreviewTitle = view.findViewById<TextView>(R.id.quick_pick_video_title)

        val optionMusicFast = view.findViewById<LinearLayout>(R.id.option_music_fast)
        val optionMusicMp3 = view.findViewById<LinearLayout>(R.id.option_music_mp3)
        val optionVideoFast = view.findViewById<LinearLayout>(R.id.option_video_fast)
        val optionVideoHq = view.findViewById<LinearLayout>(R.id.option_video_hq)

        val radioMusicFast = view.findViewById<RadioButton>(R.id.option_music_fast_radio)
        val radioMusicMp3 = view.findViewById<RadioButton>(R.id.option_music_mp3_radio)
        val radioVideoFast = view.findViewById<RadioButton>(R.id.option_video_fast_radio)
        val radioVideoHq = view.findViewById<RadioButton>(R.id.option_video_hq_radio)

        val sizeMusicFast = view.findViewById<TextView>(R.id.option_music_fast_size)
        val sizeMusicMp3 = view.findViewById<TextView>(R.id.option_music_mp3_size)
        val sizeVideoFast = view.findViewById<TextView>(R.id.option_video_fast_size)
        val sizeVideoHq = view.findViewById<TextView>(R.id.option_video_hq_size)

        val moreFormatsRow = view.findViewById<LinearLayout>(R.id.more_formats_row)
        val moreFormatsCount = view.findViewById<TextView>(R.id.more_formats_count)
        val advancedBtn = view.findViewById<TextView>(R.id.quick_pick_advanced)
        val downloadBtn = view.findViewById<MaterialButton>(R.id.quick_pick_button)

        // Instant preview: a single lightweight oEmbed request (title + thumbnail, no yt-dlp
        // process) so the sheet shows something real right away, while the full format list
        // (which genuinely needs yt-dlp and takes longer) keeps loading underneath.
        if (result.title.isNotBlank()) {
            videoPreviewTitle.text = result.title
            videoPreviewThumbnail.loadThumbnail(false, result.thumb)
            videoPreviewRow.visibility = View.VISIBLE
        } else if (result.url.isYoutubeURL()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val preview = InstantPreviewFetcher.fetchYoutubeOembed(result.url)
                if (preview != null && isAdded) {
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        videoPreviewTitle.text = preview.title
                        preview.thumbnailUrl?.let { videoPreviewThumbnail.loadThumbnail(false, it) }
                        videoPreviewRow.visibility = View.VISIBLE
                    }
                }
            }
        }

        fun refreshSelection() {
            radioMusicFast.isChecked = selectedPreset == Preset.MUSIC_FAST
            radioMusicMp3.isChecked = selectedPreset == Preset.MUSIC_MP3
            radioVideoFast.isChecked = selectedPreset == Preset.VIDEO_FAST
            radioVideoHq.isChecked = selectedPreset == Preset.VIDEO_HQ
        }
        refreshSelection()

        optionMusicFast.setOnClickListener { selectedPreset = Preset.MUSIC_FAST; refreshSelection() }
        optionMusicMp3.setOnClickListener { selectedPreset = Preset.MUSIC_MP3; refreshSelection() }
        optionVideoFast.setOnClickListener { selectedPreset = Preset.VIDEO_FAST; refreshSelection() }
        optionVideoHq.setOnClickListener { selectedPreset = Preset.VIDEO_HQ; refreshSelection() }

        fun showFormats(allFormats: List<Format>) {
            val audioFormats = allFormats.filter { it.vcodec.isBlank() || it.vcodec == "none" }
            val videoFormats = allFormats.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }

            musicFastFormat = runCatching {
                if (audioFormats.isNotEmpty()) downloadViewModel.getFormat(allFormats, DownloadType.audio) else null
            }.getOrNull()

            videoFastFormat = pickVideoFormat(videoFormats, 480)
            videoHqFormat = pickVideoFormat(videoFormats, 720)

            optionMusicFast.isEnabled = musicFastFormat != null
            optionMusicMp3.isEnabled = musicFastFormat != null
            optionVideoFast.isEnabled = videoFastFormat != null
            optionVideoHq.isEnabled = videoHqFormat != null
            optionMusicFast.alpha = if (musicFastFormat != null) 1f else 0.4f
            optionMusicMp3.alpha = if (musicFastFormat != null) 1f else 0.4f
            optionVideoFast.alpha = if (videoFastFormat != null) 1f else 0.4f
            optionVideoHq.alpha = if (videoHqFormat != null) 1f else 0.4f

            sizeMusicFast.text = musicFastFormat?.filesize?.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) } ?: ""
            // MP3 conversion has no exact known size ahead of time (re-encode), so we estimate off the source track.
            sizeMusicMp3.text = musicFastFormat?.filesize?.takeIf { it > 0 }?.let { "\u2248${FileUtil.convertFileSize(it)}" } ?: ""
            sizeVideoFast.text = videoFastFormat?.filesize?.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) } ?: ""
            sizeVideoHq.text = videoHqFormat?.filesize?.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) } ?: ""

            // if the current selection turned out unavailable, fall back to something that is
            if (selectedPreset == Preset.MUSIC_FAST && musicFastFormat == null && videoFastFormat != null) selectedPreset = Preset.VIDEO_FAST
            if ((selectedPreset == Preset.VIDEO_FAST || selectedPreset == Preset.VIDEO_HQ) && videoFastFormat == null && musicFastFormat != null) selectedPreset = Preset.MUSIC_FAST
            refreshSelection()

            moreFormatsCount.text = if (allFormats.isNotEmpty())
                getString(R.string.quick_pick_formats_available, allFormats.size)
            else
                getString(R.string.quick_pick_all)

            shimmer.visibility = View.GONE
            content.visibility = View.VISIBLE
        }

        // If ShareActivity posts a cached result (with formats already) after the sheet
        // opened with a stub, pick it up and skip the yt-dlp fetch entirely.
        viewLifecycleOwner.lifecycleScope.launch {
            downloadCardViewModel.resultItemFlow.collectLatest { updatedResult ->
                if (updatedResult == null || updatedResult.url != result.url) return@collectLatest
                if (updatedResult.formats.isNotEmpty() && result.formats.isEmpty()) {
                    result = updatedResult
                    withContext(Dispatchers.Main) {
                        showFormats(result.formats)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats == null) return@collectLatest
                if (formats.isNotEmpty()) result.formats = formats
                withContext(Dispatchers.Main) {
                    showFormats(result.formats)
                }
                resultViewModel.updateFormatsResultData.emit(null)
            }
        }

        if (result.formats.isNotEmpty()) {
            showFormats(result.formats)
        } else {
            runCatching {
                if (!resultViewModel.updatingFormats.value) {
                    CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                        resultViewModel.updateFormatItemData(result)
                    }
                }
            }
        }

        moreFormatsRow.setOnClickListener {
            val type = if (selectedPreset == Preset.MUSIC_FAST || selectedPreset == Preset.MUSIC_MP3) DownloadType.audio else DownloadType.video
            val item = downloadViewModel.createDownloadItemFromResult(result, result.url, type)
            item.allFormats = result.formats.toMutableList()

            val listener = object : OnFormatClickListener {
                override fun onFormatClick(formatTuple: FormatTuple) {
                    formatTuple.format?.let { format ->
                        if (type == DownloadType.audio) {
                            musicFastFormat = format
                            selectedPreset = Preset.MUSIC_FAST
                            sizeMusicFast.text = format.filesize.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) } ?: ""
                        } else {
                            videoFastFormat = format
                            selectedPreset = Preset.VIDEO_FAST
                            sizeVideoFast.text = format.filesize.takeIf { it > 0 }?.let { FileUtil.convertFileSize(it) } ?: ""
                        }
                        refreshSelection()
                    }
                }
                override fun onFormatsUpdated(allFormats: List<Format>) {
                    result.formats = allFormats
                }
            }

            if (parentFragmentManager.findFragmentByTag("quickPickFormatSheet") == null) {
                formatViewModel.setItem(item, false)
                FormatSelectionBottomSheetDialog(listener).show(parentFragmentManager, "quickPickFormatSheet")
            }
        }

        advancedBtn.setOnClickListener {
            downloadCardViewModel.setResultItem(result)
            downloadCardViewModel.setDownloadItem(null)
            val args = Bundle().apply {
                putSerializable("type", if (selectedPreset == Preset.MUSIC_FAST || selectedPreset == Preset.MUSIC_MP3) DownloadType.audio else DownloadType.video)
                putBoolean("ignore_duplicates", ignoreDuplicates)
            }
            runCatching {
                findNavController().navigate(R.id.action_quickPickBottomSheetDialog_to_downloadBottomSheetDialog, args)
            }
        }

        downloadBtn.setOnClickListener {
            downloadBtn.isEnabled = false
            val item = buildSelectedDownloadItem()
            if (item == null) {
                downloadBtn.isEnabled = true
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val queueResult = withContext(Dispatchers.IO) {
                    downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                }
                if (queueResult.message.isNotBlank() && context != null) {
                    android.widget.Toast.makeText(requireContext(), queueResult.message, android.widget.Toast.LENGTH_LONG).show()
                }
                dismiss()
            }
        }
    }

    private fun pickVideoFormat(videoFormats: List<Format>, maxHeight: Int): Format? {
        if (videoFormats.isEmpty()) return null
        val withinCap = videoFormats.filter { (it.height ?: 0) in 1..maxHeight }
        return withinCap.maxByOrNull { it.height ?: 0 }
            ?: videoFormats.minByOrNull { it.height ?: Int.MAX_VALUE }
    }

    private fun buildSelectedDownloadItem(): DownloadItem? {
        return when (selectedPreset) {
            Preset.MUSIC_FAST -> {
                val format = musicFastFormat ?: return null
                downloadViewModel.createDownloadItemFromResult(result, result.url, DownloadType.audio).apply {
                    this.format = format
                    this.container = "Default"
                }
            }
            Preset.MUSIC_MP3 -> {
                val format = musicFastFormat ?: return null
                downloadViewModel.createDownloadItemFromResult(result, result.url, DownloadType.audio).apply {
                    this.format = format
                    this.container = "mp3"
                }
            }
            Preset.VIDEO_FAST -> {
                val format = videoFastFormat ?: return null
                downloadViewModel.createDownloadItemFromResult(result, result.url, DownloadType.video).apply {
                    this.format = format
                }
            }
            Preset.VIDEO_HQ -> {
                val format = videoHqFormat ?: return null
                downloadViewModel.createDownloadItemFromResult(result, result.url, DownloadType.video).apply {
                    this.format = format
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateFormatsItemData()
        }
        super.onDismiss(dialog)
    }
}
