package com.adblocker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.adblocker.app.databinding.ActivityMainBinding
import com.adblocker.app.diagnostics.DiagnosticLog
import com.adblocker.app.diagnostics.GlobalSettings
import com.adblocker.app.podcast.PodcastAddictSkipService
import com.adblocker.app.podcast.PodcastMediaListenerService
import com.adblocker.app.podcast.PodcastSettingsRepository
import com.adblocker.app.podcast.PodcastStatsRepository
import com.adblocker.app.podcast.SkipDurationLimits
import com.adblocker.app.spotify.SpotifyAdSkipService
import com.adblocker.app.tiktok.TikTokFilterService
import com.adblocker.app.spotify.SettingsRepository as SpotifySettingsRepository
import com.adblocker.app.spotify.StatsRepository as SpotifyStatsRepository
import com.adblocker.app.tiktok.SettingsRepository as TikTokSettingsRepository
import com.adblocker.app.tiktok.StatsRepository as TikTokStatsRepository
import java.io.File

/**
 * One Activity for all three modules, switched between via a tab bar rather than three
 * separate screens/apps - see PRD.md's "unified at the UI layer" decision. Each tab's
 * settings/stats logic is otherwise a close port of that module's original standalone
 * MainActivity; only the Diagnostics tab (one shared log/toggle for all three modules)
 * is genuinely new here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var tiktokSettings: TikTokSettingsRepository
    private lateinit var tiktokStats: TikTokStatsRepository
    private lateinit var spotifySettings: SpotifySettingsRepository
    private lateinit var spotifyStats: SpotifyStatsRepository
    private lateinit var podcastSettings: PodcastSettingsRepository
    private lateinit var podcastStats: PodcastStatsRepository
    private lateinit var globalSettings: GlobalSettings
    private lateinit var diagnosticLog: DiagnosticLog

    private var isTikTokSelectModeActive = false
    private val tiktokSelectedCreators = mutableSetOf<String>()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshAllStats()
            refreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tiktokSettings = TikTokSettingsRepository(this)
        tiktokStats = TikTokStatsRepository(this)
        spotifySettings = SpotifySettingsRepository(this)
        spotifyStats = SpotifyStatsRepository(this)
        podcastSettings = PodcastSettingsRepository(this)
        podcastStats = PodcastStatsRepository(this)
        globalSettings = GlobalSettings(this)
        diagnosticLog = DiagnosticLog(this) { globalSettings.isDiagnosticLoggingEnabled }

        requestStoragePermissionIfNeeded()
        setupTabBar()
        setupTikTokTab()
        setupSpotifyTab()
        setupPodcastTab()
        setupDiagnosticsTab()

        renderAllTikTokLists()
        renderAllSpotifyLists()
        renderAllPodcastLists()
        refreshAllStats()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatuses()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // ===================== Tab switching =====================

    private fun setupTabBar() {
        binding.tabButtonTikTok.setOnClickListener { showTab(Tab.TIKTOK) }
        binding.tabButtonSpotify.setOnClickListener { showTab(Tab.SPOTIFY) }
        binding.tabButtonPodcast.setOnClickListener { showTab(Tab.PODCAST) }
        binding.tabButtonDiagnostics.setOnClickListener { showTab(Tab.DIAGNOSTICS) }
    }

    private enum class Tab { TIKTOK, SPOTIFY, PODCAST, DIAGNOSTICS }

    private fun showTab(tab: Tab) {
        binding.tabTikTok.visibility = if (tab == Tab.TIKTOK) View.VISIBLE else View.GONE
        binding.tabSpotify.visibility = if (tab == Tab.SPOTIFY) View.VISIBLE else View.GONE
        binding.tabPodcast.visibility = if (tab == Tab.PODCAST) View.VISIBLE else View.GONE
        binding.tabDiagnostics.visibility = if (tab == Tab.DIAGNOSTICS) View.VISIBLE else View.GONE

        val selected = "#7c4dff".toColorInt()
        val unselected = "#3a3a3a".toColorInt()
        binding.tabButtonTikTok.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == Tab.TIKTOK) selected else unselected)
        binding.tabButtonSpotify.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == Tab.SPOTIFY) selected else unselected)
        binding.tabButtonPodcast.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == Tab.PODCAST) selected else unselected)
        binding.tabButtonDiagnostics.backgroundTintList = android.content.res.ColorStateList.valueOf(if (tab == Tab.DIAGNOSTICS) selected else unselected)
    }

    private fun String.toColorInt(): Int = android.graphics.Color.parseColor(this)

    // ===================== TikTok tab =====================

    private fun requestStoragePermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_STORAGE_PERMISSION)
        }
    }

    private fun setupTikTokTab() {
        binding.tiktokOpenAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.tiktokAdSkipSwitch.isChecked = tiktokSettings.isAdSkipEnabled
        binding.tiktokAdSkipSwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isAdSkipEnabled = isChecked }
        binding.tiktokBlockedCreatorSkipSwitch.isChecked = tiktokSettings.isBlockedCreatorSkipEnabled
        binding.tiktokBlockedCreatorSkipSwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isBlockedCreatorSkipEnabled = isChecked }
        binding.tiktokRealBlockSwitch.isChecked = tiktokSettings.isRealBlockAutomationEnabled
        binding.tiktokRealBlockSwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isRealBlockAutomationEnabled = isChecked }
        binding.tiktokOverlaySwitch.isChecked = tiktokSettings.isOverlayEnabled
        binding.tiktokOverlaySwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isOverlayEnabled = isChecked }
        binding.tiktokLiveStreamSkipSwitch.isChecked = tiktokSettings.isLiveStreamSkipEnabled
        binding.tiktokLiveStreamSkipSwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isLiveStreamSkipEnabled = isChecked }
        binding.tiktokSubjectFilterSwitch.isChecked = tiktokSettings.isSubjectBoostEnabled
        binding.tiktokSubjectFilterSwitch.setOnCheckedChangeListener { _, isChecked -> tiktokSettings.isSubjectBoostEnabled = isChecked }

        binding.tiktokAddBlockedCreatorButton.setOnClickListener {
            val handle = binding.tiktokBlockedCreatorInput.text.toString().trim()
            if (handle.isEmpty()) return@setOnClickListener
            tiktokSettings.addBlockedCreator(handle)
            binding.tiktokBlockedCreatorInput.setText("")
            renderTikTokBlockedCreators()
        }
        binding.tiktokToggleSelectModeButton.setOnClickListener { toggleTikTokSelectMode() }
        binding.tiktokRemoveSelectedButton.setOnClickListener { removeSelectedTikTokCreators() }

        binding.tiktokAddAdKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokAdKeywordInput) { tiktokSettings.addAdKeyword(it) }
            renderTikTokAdKeywords()
        }
        binding.tiktokAddSubjectKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokSubjectKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::subjectKeywords, it) }
            renderTikTokSubjectKeywords()
        }
        binding.tiktokAddLikeOptionKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokLikeOptionKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::likeOptionKeywords, it) }
            renderTikTokLikeOptionKeywords()
        }
        binding.tiktokAddTargetPackageButton.setOnClickListener {
            val pkg = binding.tiktokTargetPackageInput.text.toString().trim()
            if (pkg.isNotEmpty() && pkg !in tiktokSettings.targetPackages) {
                tiktokSettings.targetPackages = tiktokSettings.targetPackages + pkg
            }
            binding.tiktokTargetPackageInput.setText("")
            renderTikTokTargetPackages()
        }
        binding.tiktokAddMoreOptionsKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokMoreOptionsKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::moreOptionsKeywords, it) }
            renderTikTokMoreOptionsKeywords()
        }
        binding.tiktokAddBlockOptionKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokBlockOptionKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::blockOptionKeywords, it) }
            renderTikTokBlockOptionKeywords()
        }
        binding.tiktokAddBlockConfirmKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokBlockConfirmKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::blockConfirmKeywords, it) }
            renderTikTokBlockConfirmKeywords()
        }
        binding.tiktokAddDownloadOptionKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokDownloadOptionKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::downloadOptionKeywords, it) }
            renderTikTokDownloadOptionKeywords()
        }
        binding.tiktokAddLiveMoreOptionsKeywordButton.setOnClickListener {
            addAndClear(binding.tiktokLiveMoreOptionsKeywordInput) { tiktokSettings.addKeyword(tiktokSettings::liveMoreOptionsKeywords, it) }
            renderTikTokLiveMoreOptionsKeywords()
        }
        binding.tiktokClearStatsButton.setOnClickListener {
            tiktokStats.clear()
            refreshAllStats()
        }
    }

    private fun toggleTikTokSelectMode() {
        isTikTokSelectModeActive = !isTikTokSelectModeActive
        tiktokSelectedCreators.clear()
        binding.tiktokRemoveSelectedButton.visibility = if (isTikTokSelectModeActive) View.VISIBLE else View.GONE
        binding.tiktokToggleSelectModeButton.text = if (isTikTokSelectModeActive) "Cancel" else "Select"
        renderTikTokBlockedCreators()
    }

    private fun removeSelectedTikTokCreators() {
        if (tiktokSelectedCreators.isEmpty()) return
        tiktokSettings.blockedCreators = tiktokSettings.blockedCreators.filterNot { it in tiktokSelectedCreators }
        tiktokSelectedCreators.clear()
        toggleTikTokSelectMode()
    }

    private fun renderAllTikTokLists() {
        renderTikTokBlockedCreators()
        renderTikTokAdKeywords()
        renderTikTokSubjectKeywords()
        renderTikTokTargetPackages()
        renderTikTokMoreOptionsKeywords()
        renderTikTokBlockOptionKeywords()
        renderTikTokBlockConfirmKeywords()
        renderTikTokDownloadOptionKeywords()
        renderTikTokLiveMoreOptionsKeywords()
        renderTikTokLikeOptionKeywords()
    }

    private fun renderTikTokBlockedCreators() {
        val container = binding.tiktokBlockedCreatorsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (normalizedHandle in tiktokSettings.blockedCreators) {
            val row = inflater.inflate(R.layout.list_item_row, container, false)
            row.findViewById<TextView>(R.id.rowText).text = normalizedHandle
            val checkbox = row.findViewById<CheckBox>(R.id.rowCheckbox)
            val removeButton = row.findViewById<Button>(R.id.rowRemoveButton)
            if (isTikTokSelectModeActive) {
                checkbox.visibility = View.VISIBLE
                checkbox.isChecked = normalizedHandle in tiktokSelectedCreators
                checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) tiktokSelectedCreators.add(normalizedHandle) else tiktokSelectedCreators.remove(normalizedHandle)
                }
                removeButton.visibility = View.GONE
            } else {
                checkbox.visibility = View.GONE
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    tiktokSettings.removeBlockedCreator(normalizedHandle)
                    renderTikTokBlockedCreators()
                }
            }
            container.addView(row)
        }
    }

    private fun renderTikTokAdKeywords() {
        renderList(binding.tiktokAdKeywordsContainer, tiktokSettings.adKeywords) { keyword ->
            tiktokSettings.removeAdKeyword(keyword)
            renderTikTokAdKeywords()
        }
    }

    private fun renderTikTokSubjectKeywords() {
        renderList(binding.tiktokSubjectKeywordsContainer, tiktokSettings.subjectKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::subjectKeywords, keyword)
            renderTikTokSubjectKeywords()
        }
    }

    private fun renderTikTokTargetPackages() {
        renderList(binding.tiktokTargetPackagesContainer, tiktokSettings.targetPackages) { pkg ->
            tiktokSettings.targetPackages = tiktokSettings.targetPackages.filterNot { it == pkg }
            renderTikTokTargetPackages()
        }
    }

    private fun renderTikTokMoreOptionsKeywords() {
        renderList(binding.tiktokMoreOptionsKeywordsContainer, tiktokSettings.moreOptionsKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::moreOptionsKeywords, keyword)
            renderTikTokMoreOptionsKeywords()
        }
    }

    private fun renderTikTokBlockOptionKeywords() {
        renderList(binding.tiktokBlockOptionKeywordsContainer, tiktokSettings.blockOptionKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::blockOptionKeywords, keyword)
            renderTikTokBlockOptionKeywords()
        }
    }

    private fun renderTikTokBlockConfirmKeywords() {
        renderList(binding.tiktokBlockConfirmKeywordsContainer, tiktokSettings.blockConfirmKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::blockConfirmKeywords, keyword)
            renderTikTokBlockConfirmKeywords()
        }
    }

    private fun renderTikTokDownloadOptionKeywords() {
        renderList(binding.tiktokDownloadOptionKeywordsContainer, tiktokSettings.downloadOptionKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::downloadOptionKeywords, keyword)
            renderTikTokDownloadOptionKeywords()
        }
    }

    private fun renderTikTokLiveMoreOptionsKeywords() {
        renderList(binding.tiktokLiveMoreOptionsKeywordsContainer, tiktokSettings.liveMoreOptionsKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::liveMoreOptionsKeywords, keyword)
            renderTikTokLiveMoreOptionsKeywords()
        }
    }

    private fun renderTikTokLikeOptionKeywords() {
        renderList(binding.tiktokLikeOptionKeywordsContainer, tiktokSettings.likeOptionKeywords) { keyword ->
            tiktokSettings.removeKeyword(tiktokSettings::likeOptionKeywords, keyword)
            renderTikTokLikeOptionKeywords()
        }
    }

    // ===================== Spotify tab =====================

    private fun setupSpotifyTab() {
        binding.spotifyOpenAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.spotifyAdSkipSwitch.isChecked = spotifySettings.isAdSkipEnabled
        binding.spotifyAdSkipSwitch.setOnCheckedChangeListener { _, isChecked -> spotifySettings.isAdSkipEnabled = isChecked }
        binding.spotifyOverlaySwitch.isChecked = spotifySettings.isOverlayEnabled
        binding.spotifyOverlaySwitch.setOnCheckedChangeListener { _, isChecked -> spotifySettings.isOverlayEnabled = isChecked }

        binding.spotifyAddAdKeywordButton.setOnClickListener {
            addAndClear(binding.spotifyAdKeywordInput) { spotifySettings.addKeyword(spotifySettings::adKeywords, it) }
            renderSpotifyAdKeywords()
        }
        binding.spotifyAddSkipControlKeywordButton.setOnClickListener {
            addAndClear(binding.spotifySkipControlKeywordInput) { spotifySettings.addKeyword(spotifySettings::skipControlKeywords, it) }
            renderSpotifySkipControlKeywords()
        }
        binding.spotifyAddDownloadControlKeywordButton.setOnClickListener {
            addAndClear(binding.spotifyDownloadControlKeywordInput) { spotifySettings.addKeyword(spotifySettings::downloadControlKeywords, it) }
            renderSpotifyDownloadControlKeywords()
        }
        binding.spotifyAddTargetPackageButton.setOnClickListener {
            val pkg = binding.spotifyTargetPackageInput.text.toString().trim()
            if (pkg.isNotEmpty() && pkg !in spotifySettings.targetPackages) {
                spotifySettings.targetPackages = spotifySettings.targetPackages + pkg
            }
            binding.spotifyTargetPackageInput.setText("")
            renderSpotifyTargetPackages()
        }
        binding.spotifyClearStatsButton.setOnClickListener {
            spotifyStats.clear()
            refreshAllStats()
        }
    }

    private fun renderAllSpotifyLists() {
        renderSpotifyAdKeywords()
        renderSpotifySkipControlKeywords()
        renderSpotifyDownloadControlKeywords()
        renderSpotifyTargetPackages()
    }

    private fun renderSpotifyAdKeywords() {
        renderList(binding.spotifyAdKeywordsContainer, spotifySettings.adKeywords) { keyword ->
            spotifySettings.removeKeyword(spotifySettings::adKeywords, keyword)
            renderSpotifyAdKeywords()
        }
    }

    private fun renderSpotifySkipControlKeywords() {
        renderList(binding.spotifySkipControlKeywordsContainer, spotifySettings.skipControlKeywords) { keyword ->
            spotifySettings.removeKeyword(spotifySettings::skipControlKeywords, keyword)
            renderSpotifySkipControlKeywords()
        }
    }

    private fun renderSpotifyDownloadControlKeywords() {
        renderList(binding.spotifyDownloadControlKeywordsContainer, spotifySettings.downloadControlKeywords) { keyword ->
            spotifySettings.removeKeyword(spotifySettings::downloadControlKeywords, keyword)
            renderSpotifyDownloadControlKeywords()
        }
    }

    private fun renderSpotifyTargetPackages() {
        renderList(binding.spotifyTargetPackagesContainer, spotifySettings.targetPackages) { pkg ->
            spotifySettings.targetPackages = spotifySettings.targetPackages.filterNot { it == pkg }
            renderSpotifyTargetPackages()
        }
    }

    // ===================== Podcast tab =====================

    private fun setupPodcastTab() {
        binding.podcastOpenAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.podcastOpenNotificationSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.podcastSkipButtonSwitch.isChecked = podcastSettings.isSkipButtonEnabled
        binding.podcastSkipButtonSwitch.setOnCheckedChangeListener { _, isChecked -> podcastSettings.isSkipButtonEnabled = isChecked }
        binding.podcastSkipDurationInput.setText(podcastSettings.skipDurationSeconds.toString())

        binding.podcastSaveSkipDurationButton.setOnClickListener {
            val raw = binding.podcastSkipDurationInput.text.toString()
            val parsed = SkipDurationLimits.parseSeconds(raw)
            if (parsed == null) {
                Toast.makeText(
                    this,
                    "Enter a whole number between ${SkipDurationLimits.MIN_SECONDS} and ${SkipDurationLimits.MAX_SECONDS} seconds",
                    Toast.LENGTH_LONG
                ).show()
                binding.podcastSkipDurationInput.setText(podcastSettings.skipDurationSeconds.toString())
                return@setOnClickListener
            }
            podcastSettings.skipDurationSeconds = parsed
            Toast.makeText(this, "Skip duration set to ${parsed}s", Toast.LENGTH_SHORT).show()
        }

        binding.podcastAddTargetPackageButton.setOnClickListener {
            val pkg = binding.podcastTargetPackageInput.text.toString().trim()
            if (pkg.isNotEmpty() && pkg !in podcastSettings.targetPackages) {
                podcastSettings.targetPackages = podcastSettings.targetPackages + pkg
            }
            binding.podcastTargetPackageInput.setText("")
            renderPodcastTargetPackages()
        }
        binding.podcastClearStatsButton.setOnClickListener {
            podcastStats.clear()
            refreshAllStats()
        }
    }

    private fun renderAllPodcastLists() {
        renderPodcastTargetPackages()
    }

    private fun renderPodcastTargetPackages() {
        renderList(binding.podcastTargetPackagesContainer, podcastSettings.targetPackages) { pkg ->
            podcastSettings.targetPackages = podcastSettings.targetPackages.filterNot { it == pkg }
            renderPodcastTargetPackages()
        }
    }

    // ===================== Diagnostics tab =====================

    private fun setupDiagnosticsTab() {
        binding.diagnosticLoggingSwitch.isChecked = globalSettings.isDiagnosticLoggingEnabled
        binding.diagnosticLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            globalSettings.isDiagnosticLoggingEnabled = isChecked
        }
        binding.shareDiagnosticLogButton.setOnClickListener { shareDiagnosticLog() }
        binding.clearDiagnosticLogButton.setOnClickListener {
            diagnosticLog.clear()
            refreshAllStats()
            Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDiagnosticLog() {
        val file = File(diagnosticLog.filePath)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "Diagnostic log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share diagnostic log"))
    }

    // ===================== Shared helpers =====================

    private fun addAndClear(input: android.widget.EditText, add: (String) -> Unit) {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        add(value)
        input.setText("")
    }

    /** Rebuilds [container] from scratch with one list_item_row per entry in [items] -
      * simple over efficient, but these lists are always small, so a RecyclerView would
      * be more machinery than the job calls for. Shared across all three tabs. */
    private fun renderList(container: LinearLayout, items: List<String>, onRemove: (String) -> Unit) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.list_item_row, container, false)
            row.findViewById<TextView>(R.id.rowText).text = item
            row.findViewById<Button>(R.id.rowRemoveButton).setOnClickListener { onRemove(item) }
            container.addView(row)
        }
    }

    private fun refreshAccessibilityStatuses() {
        binding.tiktokStatusText.text = if (isAccessibilityServiceEnabled(TikTokFilterService::class.java)) {
            "Accessibility Service: Enabled - filtering is active"
        } else {
            "Accessibility Service: Not enabled - tap below to turn it on"
        }
        binding.spotifyStatusText.text = if (isAccessibilityServiceEnabled(SpotifyAdSkipService::class.java)) {
            "Accessibility Service: Enabled - ad skip is active"
        } else {
            "Accessibility Service: Not enabled - tap below to turn it on"
        }
        binding.podcastAccessibilityStatusText.text = if (isAccessibilityServiceEnabled(PodcastAddictSkipService::class.java)) {
            "1. Accessibility Service: Enabled"
        } else {
            "1. Accessibility Service: Not enabled - tap below to turn it on"
        }
        binding.podcastNotificationStatusText.text = if (isNotificationListenerEnabled(PodcastMediaListenerService::class.java)) {
            "2. Notification access: Granted"
        } else {
            "2. Notification access: NOT granted - the Skip button will report failures until this is on"
        }
    }

    private fun isAccessibilityServiceEnabled(serviceClass: Class<*>): Boolean {
        val expected = "$packageName/${serviceClass.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(serviceClass: Class<*>): Boolean {
        val expected = "$packageName/${serviceClass.name}"
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshAllStats() {
        binding.tiktokAdsSkippedText.text = "Ads skipped: ${tiktokStats.adsSkipped}"
        binding.tiktokCreatorsSkippedText.text = "Creators skipped: ${tiktokStats.creatorsSkipped}"
        binding.tiktokAudioExtractedText.text = "Audio saved: ${tiktokStats.audioExtractionsCompleted}"
        binding.tiktokSubjectBoostLikesText.text = "Subject Boost auto-likes: ${tiktokStats.subjectBoostLikes}"
        val tiktokLog = tiktokStats.recentLog()
        binding.tiktokActivityLogText.text = if (tiktokLog.isEmpty()) "No activity yet" else tiktokLog.joinToString("\n")

        binding.spotifyAdsDetectedText.text = "Ads detected: ${spotifyStats.adsDetected}"
        binding.spotifySkipTappedText.text = "Skip tapped: ${spotifyStats.skipTapped}"
        binding.spotifySkipBlockedText.text = "Skip blocked (disabled by Spotify): ${spotifyStats.skipBlocked}"
        binding.spotifySkipControlNotFoundText.text = "Skip control not found: ${spotifyStats.skipControlNotFound}"
        binding.spotifyDownloadTappedText.text = "Download tapped: ${spotifyStats.downloadTapped}"
        binding.spotifyDownloadControlNotFoundText.text = "Download control not found: ${spotifyStats.downloadControlNotFound}"
        val spotifyLog = spotifyStats.recentLog()
        binding.spotifyActivityLogText.text = if (spotifyLog.isEmpty()) "No activity yet" else spotifyLog.joinToString("\n")

        binding.podcastSkipsPerformedText.text = "Skips performed: ${podcastStats.skipsPerformed}"
        binding.podcastSkipsFailedText.text = "Skips failed: ${podcastStats.skipsFailed}"
        val podcastLog = podcastStats.recentLog()
        binding.podcastActivityLogText.text = if (podcastLog.isEmpty()) "No activity yet" else podcastLog.joinToString("\n")

        val kilobytes = diagnosticLog.sizeBytes / 1024.0
        binding.diagnosticLogSizeText.text = "Diagnostic log: %.1f KB".format(kilobytes)
    }

    companion object {
        private const val STATS_REFRESH_INTERVAL_MILLIS = 3_000L
        private const val REQUEST_STORAGE_PERMISSION = 100
    }
}
