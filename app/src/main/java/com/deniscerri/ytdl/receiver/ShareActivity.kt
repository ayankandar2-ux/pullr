package com.deniscerri.ytdl.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.models.ResultItem
import com.deniscerri.ytdl.database.viewmodel.CookieViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.database.viewmodel.HistoryViewModel
import com.deniscerri.ytdl.database.viewmodel.ResultViewModel
import com.deniscerri.ytdl.ui.BaseActivity
import com.deniscerri.ytdl.util.Extensions.extractURL
import com.deniscerri.ytdl.util.ThemeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.Delegates


class ShareActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var navController: NavController
    private var quickDownload by Delegates.notNull<Boolean>()


    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme first (reads SharedPreferences only - fast)
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }

        // Show the window IMMEDIATELY - transparent background, no overlay tricks
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.activity_share)

        // Wire up nav + show the sheet instantly (stub result, no DB wait)
        context = baseContext
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        // Only init the card VM synchronously - it's tiny, no DB ops
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]

        val intent = intent
        handleIntents(intent)

        // Init the rest of the VMs in the background AFTER the sheet is visible
        lifecycleScope.launch(Dispatchers.IO) {
            resultViewModel = ViewModelProvider(this@ShareActivity)[ResultViewModel::class.java]
            historyViewModel = ViewModelProvider(this@ShareActivity)[HistoryViewModel::class.java]
            downloadViewModel = ViewModelProvider(this@ShareActivity)[DownloadViewModel::class.java]
            cookieViewModel = ViewModelProvider(this@ShareActivity)[CookieViewModel::class.java]
            cookieViewModel.updateCookiesFile()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        askPermissions()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        navController.addOnDestinationChangedListener(object: NavController.OnDestinationChangedListener{
            @SuppressLint("RestrictedApi")
            override fun onDestinationChanged(
                controller: NavController,
                destination: NavDestination,
                arguments: Bundle?
            ) {
                navController.removeOnDestinationChangedListener(this)
                CoroutineScope(SupervisorJob()).launch {
                    navController.currentBackStack.collectLatest {
                        if (it.isEmpty()){
                            this@ShareActivity.finish()
                        }
                    }
                }
            }
        })

        val action = intent.action
        Log.e("aa", intent.toString())
        if (Intent.ACTION_SEND == action || Intent.ACTION_VIEW == action) {
            if (intent.getStringExtra(Intent.EXTRA_TEXT) == null && Intent.ACTION_SEND == action){
                intent.setClass(this, MainActivity::class.java)
                startActivity(intent)
                finishAffinity()
                return
            }

            runCatching { supportFragmentManager.popBackStack() }

            quickDownload = intent.getBooleanExtra("quick_download",
                sharedPreferences.getBoolean("quick_download", false) ||
                sharedPreferences.getString("preferred_download_type", "video") == "command")

            val data = when(action){
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)!!
                else -> intent.dataString!!
            }

            val inputQuery = data.extractURL()
            val type = intent.getStringExtra("TYPE")
            val ai = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
            val background = intent.getBooleanExtra("BACKGROUND",
                ai.metaData?.getBoolean("quick_run_background", false) == true)

            if (sharedPreferences.getBoolean("download_card", true) && !background) {
                // ── STEP 1: show the sheet IMMEDIATELY with a zero-cost stub ──
                // downloadCardViewModel is the only VM initialized at this point.
                // Everything else happens after the sheet is on screen.
                val stubResult = ResultItem(
                    0, inputQuery, "", "", "", "", "", "",
                    arrayListOf(), "", arrayListOf(), "", null,
                    System.currentTimeMillis()
                )
                val downloadType = DownloadType.valueOf(
                    type ?: sharedPreferences.getString("preferred_download_type", "video")!!
                        .let { if (it == "auto") "video" else it }
                )
                downloadCardViewModel.setResultItem(stubResult)
                downloadCardViewModel.setDownloadItem(null)
                val bundle = Bundle()
                bundle.putSerializable("type", downloadType)
                navController.setGraph(R.navigation.share_nav_graph, bundle)

                // ── STEP 2: do DB/VM work in background after sheet is visible ──
                lifecycleScope.launch(Dispatchers.IO) {
                    // VMs may not be ready yet - wait briefly if needed
                    var waited = 0
                    while (!::downloadViewModel.isInitialized && waited < 2000) {
                        kotlinx.coroutines.delay(50)
                        waited += 50
                    }
                    if (!::downloadViewModel.isInitialized) return@launch

                    val existingResults = resultViewModel.getAllByURL(inputQuery)
                    if (existingResults.size == 1) {
                        // Cached result with formats — push it so sheet skips yt-dlp fetch
                        val cached = existingResults.first()
                        withContext(Dispatchers.Main) {
                            downloadCardViewModel.setResultItem(cached)
                        }
                    } else {
                        resultViewModel.deleteAll()
                    }
                }
            } else {
                // Background/quick download — needs VMs, wait for them
                lifecycleScope.launch(Dispatchers.IO) {
                    var waited = 0
                    while (!::downloadViewModel.isInitialized && waited < 3000) {
                        kotlinx.coroutines.delay(50)
                        waited += 50
                    }
                    if (!::downloadViewModel.isInitialized) return@launch

                    val downloadType = DownloadType.valueOf(
                        type ?: downloadViewModel.getDownloadType(url = inputQuery).toString()
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ShareActivity,
                            "${getString(R.string.downloading)} $inputQuery",
                            Toast.LENGTH_SHORT).show()
                    }
                    val downloadItem = downloadViewModel.createDownloadItemFromResult(
                        result = downloadViewModel.createEmptyResultItem(inputQuery),
                        givenType = downloadType
                    )
                    downloadViewModel.queueDownloads(listOf(downloadItem))
                    withContext(Dispatchers.Main) { this@ShareActivity.finish() }
                }
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        startActivity(Intent(this, MainActivity::class.java))
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }
}
