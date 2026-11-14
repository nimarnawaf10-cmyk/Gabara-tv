package com.gabara.tv

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        // سنضع هنا إحداثيات قريتك في الخطوة القادمة بعد أن تنسخها من التطبيق
        private const val VILLAGE_LAT = 0.0
        private const val VILLAGE_LNG = 0.0
        private const val MAX_VILLAGE_RADIUS_KM = 5.0

        // سنضع هنا رابط ملف القنوات من GitHub في الخطوة القادمة
        private const val DEFAULT_M3U_URL = ""
    }

    data class TvChannel(
        val id: String,
        val name: String,
        val category: String,
        val url: String,
        val isCustom: Boolean = false
    )

    data class WatchRoom(
        val id: String,
        val title: String,
        val host: String,
        val lat: Double,
        val lng: Double,
        val viewers: Int,
        val isUserCreated: Boolean = false
    )

    private lateinit var locationManager: LocationManager
    private var userLat: Double? = null
    private var userLng: Double? = null
    private var isMockLocationDetected = false

    // 1 = الرئيسية، 2 = القنوات، 3 = مشغل القناة، 4 = الرادار، 5 = غرفة الرادار
    private var currentScreen = 1
    private var exoPlayer: ExoPlayer? = null
    private var isFullscreen = false

    private val channelsList = mutableListOf<TvChannel>()
    private var selectedCategory = "الكل"

    private var sampleRoomsInitialized = false
    private val allRooms = mutableListOf<WatchRoom>()

    private var radarStatusText: TextView? = null
    private var radarView: RadarView? = null
    private var roomsContainer: LinearLayout? = null
    private var enableGpsButton: Button? = null
    private var mockLocationButton: Button? = null

    private var channelsStatusText: TextView? = null
    private var channelsContainer: LinearLayout? = null
    private var categoriesBar: LinearLayout? = null
    private var channelsActionRow: LinearLayout? = null
    private var channelsEnableGpsBtn: Button? = null
    private var copyCoordsBtn: Button? = null
    private var playerStatusLabel: TextView? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
        } else {
            val msg = "⚠️ يجب السماح بصلاحية الموقع للتأكد من أنك داخل القرية (5 كم)"
            radarStatusText?.text = msg
            channelsStatusText?.text = msg
            mockLocationButton?.visibility = View.VISIBLE
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock
            } else {
                @Suppress("DEPRECATION")
                location.isFromMockProvider
            }
            isMockLocationDetected = isMock
            onUserLocationReceived(location.latitude, location.longitude)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        loadChannels()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    3 -> {
                        if (isFullscreen) {
                            toggleFullscreen()
                        } else {
                            releasePlayer()
                            showChannelsScreen()
                        }
                    }
                    5 -> showRadarScreen()
                    2, 4 -> showHomeScreen()
                    else -> finish()
                }
            }
        })

        showHomeScreen()
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        if (isFullscreen) {
            isFullscreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(colorHex: String, radiusDp: Int = 16): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    // ==================== حفظ واسترجاع القنوات ====================
    private fun loadChannels() {
        channelsList.clear()
        channelsList.add(
            TvChannel(
                id = "test_hls",
                name = "🎬 بث تجريبي عالي الجودة (لاختبار المشغل)",
                category = "تجريبي",
                url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                isCustom = false
            )
        )

        val prefs = getSharedPreferences("gabara_prefs", Context.MODE_PRIVATE)
        val savedJson = prefs.getString("custom_channels", null)
        if (!savedJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    channelsList.add(
                        TvChannel(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            name = obj.optString("name", "قناة"),
                            category = obj.optString("category", "منوعات"),
                            url = obj.optString("url", ""),
                            isCustom = true
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }

        val savedM3uUrl = prefs.getString("m3u_url", DEFAULT_M3U_URL) ?: DEFAULT_M3U_URL
        if (savedM3uUrl.isNotBlank()) {
            fetchRemoteM3u(savedM3uUrl, silent = true)
        }
    }

    private fun saveCustomChannels() {
        val arr = JSONArray()
        for (ch in channelsList) {
            if (ch.isCustom) {
                val obj = JSONObject().apply {
                    put("id", ch.id)
                    put("name", ch.name)
                    put("category", ch.category)
                    put("url", ch.url)
                }
                arr.put(obj)
            }
        }
        getSharedPreferences("gabara_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_channels", arr.toString())
            .apply()
    }

    private fun parseM3uContent(content: String): List<TvChannel> {
        val parsed = mutableListOf<TvChannel>()
        val lines = content.lines()
        var currentName = "قناة جديدة"
        var currentCategory = "قنوات عامة"

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
                currentCategory = groupMatch?.groupValues?.get(1)?.trim()?.ifEmpty { "قنوات عامة" } ?: "قنوات عامة"
                val commaIdx = line.lastIndexOf(',')
                currentName = if (commaIdx != -1 && commaIdx + 1 < line.length) {
                    line.substring(commaIdx + 1).trim().ifEmpty { "قناة" }
                } else {
                    "قناة"
                }
            } else if (!line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://"))) {
                parsed.add(
                    TvChannel(
                        id = "${System.currentTimeMillis()}_${parsed.size}",
                        name = currentName,
                        category = currentCategory,
                        url = line,
                        isCustom = true
                    )
                )
                currentName = "قناة"
                currentCategory = "قنوات عامة"
            }
        }
        return parsed
    }

    private fun fetchRemoteM3u(urlStr: String, silent: Boolean = false) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val imported = parseM3uContent(text)
                runOnUiThread {
                    if (imported.isNotEmpty()) {
                        for (item in imported) {
                            if (channelsList.none { it.url == item.url }) {
                                channelsList.add(item)
                            }
                        }
                        saveCustomChannels()
                        if (currentScreen == 2) renderChannelsList()
                        if (!silent) {
                            Toast.makeText(this, "تم استيراد ${imported.size} قناة بنجاح!", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!silent) {
                        Toast.makeText(this, "لم يتم العثور على قنوات صالحة في الرابط", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    runOnUiThread {
                        Toast.makeText(this, "تعذر تحميل القائمة: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    // ==================== الشاشة 1: الرئيسية ====================
    private fun showHomeScreen() {
        currentScreen = 1

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val titleText = TextView(this).apply {
            text = "Gabara TV"
            textSize = 36f
            setTextColor(Color.parseColor("#FACC15"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitleText = TextView(this).apply {
            text = "بث القنوات والمشاهدة الجماعية لأهل القرية\n(حصرياً ضمن نطاق 5 كم فقط)"
            textSize = 16f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }

        val openChannelsBtn = Button(this).apply {
            text = "📺 القنوات التلفزيونية (نطاق 5 كم)"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#E11D48", 16)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
            setOnClickListener { showChannelsScreen() }
        }

        val startRadarButton = Button(this).apply {
            text = "📡 رادار الغرف القريبة (5 كم)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#0284C7", 16)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showRadarScreen() }
        }

        root.addView(titleText)
        root.addView(subtitleText)
        root.addView(openChannelsBtn)
        root.addView(startRadarButton)

        setContentView(root)
    }

    // ==================== الشاشة 2: القنوات مع قفل 5 كم ====================
    private fun showChannelsScreen() {
        currentScreen = 2

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = Button(this).apply {
            text = "رجوع"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showHomeScreen() }
        }

        val headerTitle = TextView(this).apply {
            text = "📺 قنوات القرية"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FACC15"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = Button(this).apply {
            text = "تحقق"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#0284C7", 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { checkLocationAndStart() }
        }

        headerRow.addView(backBtn)
        headerRow.addView(headerTitle)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        channelsStatusText = TextView(this).apply {
            text = "⏳ جاري التحقق من موقعك للتأكد أنك ضمن نطاق 5 كم من القرية..."
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(channelsStatusText)

        channelsEnableGpsBtn = Button(this).apply {
            text = "تفعيل الـ GPS من الإعدادات"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#D97706", 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        root.addView(channelsEnableGpsBtn)

        copyCoordsBtn = Button(this).apply {
            text = "📋 نسخ إحداثياتي الحالية (لوضعها كمركز القرية)"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#334155", 10)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setOnClickListener {
                val lat = userLat ?: return@setOnClickListener
                val lng = userLng ?: return@setOnClickListener
                val txt = String.format(Locale.US, "VILLAGE_LAT = %.6f\nVILLAGE_LNG = %.6f", lat, lng)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VillageCoords", txt))
                Toast.makeText(this@MainActivity, "تم نسخ الإحداثيات:\n$txt", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(copyCoordsBtn)

        channelsActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val addChannelBtn = Button(this).apply {
            text = "+ إضافة قناة"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#16A34A", 12)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { openAddChannelDialog() }
        }

        val importM3uBtn = Button(this).apply {
            text = "🔗 استيراد قائمة M3U"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#7C3AED", 12)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openImportM3uDialog() }
        }

        channelsActionRow?.addView(addChannelBtn)
        channelsActionRow?.addView(importM3uBtn)
        root.addView(channelsActionRow)

        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        categoriesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        catScroll.addView(categoriesBar)
        root.addView(catScroll)

        channelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(channelsContainer)

        scrollView.addView(root)
        setContentView(scrollView)

        checkLocationAndStart()
    }

    private fun updateChannelsAccessState() {
        val lat = userLat
        val lng = userLng

        if (lat == null || lng == null) {
            channelsActionRow?.visibility = View.GONE
            channelsContainer?.removeAllViews()
            return
        }

        if (isMockLocationDetected) {
            channelsStatusText?.text = "🚫 تم اكتشاف موقع وهمي (Fake GPS). أوقفه لتتمكن من المشاهدة."
            channelsStatusText?.setTextColor(Color.parseColor("#F87171"))
            channelsActionRow?.visibility = View.GONE
            channelsContainer?.removeAllViews()
            return
        }

        val isSetupMode = (VILLAGE_LAT == 0.0 && VILLAGE_LNG == 0.0)
        if (isSetupMode) {
            channelsStatusText?.text = String.format(
                Locale.US,
                "⚠️ وضع الإعداد: موقعك الآن (%.5f, %.5f)\nانسخ الإحداثيات وضعها في الكود لتفعيل القفل الصارم 5 كم",
                lat, lng
            )
            channelsStatusText?.setTextColor(Color.parseColor("#FBBF24"))
            copyCoordsBtn?.visibility = View.VISIBLE
            channelsActionRow?.visibility = View.VISIBLE
            renderChannelsList()
            return
        }

        copyCoordsBtn?.visibility = View.GONE
        val villageLoc = Location("").apply {
            latitude = VILLAGE_LAT
            longitude = VILLAGE_LNG
        }
        val userLoc = Location("").apply {
            latitude = lat
            longitude = lng
        }
        val distanceKm = userLoc.distanceTo(villageLoc) / 1000.0

        if (distanceKm <= MAX_VILLAGE_RADIUS_KM) {
            channelsStatusText?.text = String.format(
                Locale.US,
                "✅ أنت داخل نطاق القرية (تبعد %.1f كم من المركز) - البث مفتوح لك",
                distanceKm
            )
            channelsStatusText?.setTextColor(Color.parseColor("#4ADE80"))
            channelsActionRow?.visibility = View.VISIBLE
            renderChannelsList()
        } else {
            channelsStatusText?.text = String.format(
                Locale.US,
                "🔒 عذراً، أنت خارج نطاق القرية (تبعد %.1f كم).\nهذا التطبيق متاح فقط ضمن 5 كم حول القرية.",
                distanceKm
            )
            channelsStatusText?.setTextColor(Color.parseColor("#F87171"))
            channelsActionRow?.visibility = View.GONE
            categoriesBar?.removeAllViews()
            channelsContainer?.removeAllViews()
        }
    }

    private fun renderChannelsList() {
        val catBar = categoriesBar ?: return
        val container = channelsContainer ?: return
        catBar.removeAllViews()
        container.removeAllViews()

        val categories = mutableListOf("الكل")
        for (ch in channelsList) {
            if (!categories.contains(ch.category)) {
                categories.add(ch.category)
            }
        }
        if (!categories.contains(selectedCategory)) selectedCategory = "الكل"

        for (cat in categories) {
            val isSelected = cat == selectedCategory
            val chip = Button(this).apply {
                text = cat
                textSize = 13f
                setTextColor(Color.WHITE)
                background = roundedBg(if (isSelected) "#E11D48" else "#1E293B", 20)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    selectedCategory = cat
                    renderChannelsList()
                }
            }
            catBar.addView(chip)
        }

        val filtered = if (selectedCategory == "الكل") {
            channelsList
        } else {
            channelsList.filter { it.category == selectedCategory }
        }

        for (channel in filtered) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg("#1E293B", 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                text = channel.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }

            val catTv = TextView(this).apply {
                text = "القسم: ${channel.category}"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(4), 0, 0)
            }

            infoCol.addView(nameTv)
            infoCol.addView(catTv)

            val playBtn = Button(this).apply {
                text = "▶ تشغيل"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedBg("#16A34A", 10)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { showPlayerScreen(channel) }
            }

            card.addView(infoCol)
            if (channel.isCustom) {
                val delBtn = Button(this).apply {
                    text = "🗑"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    background = roundedBg("#475569", 10)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(6)
                    }
                    setOnClickListener {
                        channelsList.removeAll { it.id == channel.id }
                        saveCustomChannels()
                        renderChannelsList()
                    }
                }
                card.addView(delBtn)
            }
            card.addView(playBtn)
            container.addView(card)
        }
    }

    private fun openAddChannelDialog() {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }

        val nameInput = EditText(this).apply {
            hint = "اسم القناة (مثلاً: القناة الرياضية)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val catInput = EditText(this).apply {
            hint = "القسم (مثلاً: رياضة / أفلام / أخبار)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val urlInput = EditText(this).apply {
            hint = "رابط البث المباشر (.m3u8 أو .mp4)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        wrapper.addView(nameInput)
        wrapper.addView(catInput)
        wrapper.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("إضافة قناة جديدة")
            .setView(wrapper)
            .setPositiveButton("إضافة") { _, _ ->
                val name = nameInput.text.toString().trim()
                val cat = catInput.text.toString().trim().ifEmpty { "منوعات" }
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Toast.makeText(this, "اكتب اسم القناة والرابط", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                channelsList.add(
                    TvChannel(
                        id = System.currentTimeMillis().toString(),
                        name = name,
                        category = cat,
                        url = url,
                        isCustom = true
                    )
                )
                saveCustomChannels()
                renderChannelsList()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openImportM3uDialog() {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }

        val input = EditText(this).apply {
            hint = "ضع رابط ملف M3U (https://...) أو الصق محتوى M3U هنا"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 3
        }
        wrapper.addView(input)

        AlertDialog.Builder(this)
            .setTitle("استيراد قائمة قنوات M3U")
            .setView(wrapper)
            .setPositiveButton("استيراد") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.startsWith("http://") || txt.startsWith("https://")) {
                    getSharedPreferences("gabara_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("m3u_url", txt)
                        .apply()
                    fetchRemoteM3u(txt, silent = false)
                } else {
                    val imported = parseM3uContent(txt)
                    if (imported.isNotEmpty()) {
                        for (item in imported) {
                            if (channelsList.none { it.url == item.url }) {
                                channelsList.add(item)
                            }
                        }
                        saveCustomChannels()
                        renderChannelsList()
                        Toast.makeText(this, "تم إضافة ${imported.size} قناة", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "النص لا يحتوي على صيغة M3U صحيحة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ==================== الشاشة 3: مشغل الفيديو المباشر ====================
    private fun showPlayerScreen(channel: TvChannel) {
        currentScreen = 3
        releasePlayer()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val backBtn = Button(this).apply {
            text = "← القنوات"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                releasePlayer()
                showChannelsScreen()
            }
        }

        val titleTv = TextView(this).apply {
            text = channel.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val fullBtn = Button(this).apply {
            text = "⛶ تكبير"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedBg("#0284C7", 10)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { toggleFullscreen() }
        }

        topBar.addView(backBtn)
        topBar.addView(titleTv)
        topBar.addView(fullBtn)
        root.addView(topBar)

        val playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            useController = true
        }
        root.addView(playerView)

        val statusTv = TextView(this).apply {
            text = "⏳ جاري الاتصال بالبث..."
            textSize = 13f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        playerStatusLabel = statusTv
        root.addView(statusTv)

        setContentView(root)

        val player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> statusTv.text = "⏳ جاري تحميل البث..."
                        Player.STATE_READY -> {
                            statusTv.text = "🟢 البث يعمل الآن"
                            statusTv.setTextColor(Color.parseColor("#4ADE80"))
                        }
                        Player.STATE_ENDED -> statusTv.text = "انتهى الفيديو"
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    statusTv.text = "❌ تعذر تشغيل هذا الرابط (تأكد من صحة الرابط أو سرعة الإنترنت)"
                    statusTv.setTextColor(Color.parseColor("#F87171"))
                }
            })
            exo.setMediaItem(MediaItem.fromUri(channel.url))
            exo.prepare()
            exo.playWhenReady = true
        }
        exoPlayer = player
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            playerStatusLabel?.visibility = View.GONE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            playerStatusLabel?.visibility = View.VISIBLE
        }
    }

    // ==================== الشاشة 4: رادار 5 كم ====================
    private fun showRadarScreen() {
        currentScreen = 4

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backBtn = Button(this).apply {
            text = "رجوع"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showHomeScreen() }
        }

        val headerTitle = TextView(this).apply {
            text = "📡 رادار 5 كم"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FACC15"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val refreshBtn = Button(this).apply {
            text = "تحديث"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#0284C7", 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { checkLocationAndStart() }
        }

        headerRow.addView(backBtn)
        headerRow.addView(headerTitle)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        radarStatusText = TextView(this).apply {
            text = "⏳ جاري البحث عن إشارة الـ GPS..."
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(radarStatusText)

        enableGpsButton = Button(this).apply {
            text = "تفعيل الـ GPS من الإعدادات"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#D97706", 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
            setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        root.addView(enableGpsButton)

        mockLocationButton = Button(this).apply {
            text = "اختبار بموقع افتراضي مؤقتاً"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            background = roundedBg("#334155", 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
            setOnClickListener {
                onUserLocationReceived(33.3152, 44.3661)
                Toast.makeText(this@MainActivity, "تم تفعيل موقع تجريبي للعرض", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(mockLocationButton)

        val radarSize = dp(250)
        radarView = RadarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(radarSize, radarSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        root.addView(radarView)

        val radarHint = TextView(this).apply {
            text = "الدائرة الكبرى = 5 كم | النقطة الصفراء = موقعك"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(radarHint)

        val createRoomBtn = Button(this).apply {
            text = "+ فتح غرفة في منطقتك"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#16A34A", 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            setOnClickListener { openCreateRoomDialog() }
        }
        root.addView(createRoomBtn)

        val listHeader = TextView(this).apply {
            text = "الغرف النشطة ضمن 5 كم (عرض تجريبي):"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(listHeader)

        roomsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(roomsContainer)

        scrollView.addView(root)
        setContentView(scrollView)

        checkLocationAndStart()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun checkLocationAndStart() {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !netEnabled) {
            val msg = "⚠️ خدمة الموقع (GPS) مغلقة في جهازك"
            radarStatusText?.text = msg
            channelsStatusText?.text = msg
            enableGpsButton?.visibility = View.VISIBLE
            channelsEnableGpsBtn?.visibility = View.VISIBLE
            mockLocationButton?.visibility = View.VISIBLE
            return
        }

        enableGpsButton?.visibility = View.GONE
        channelsEnableGpsBtn?.visibility = View.GONE

        try {
            locationManager.removeUpdates(locationListener)
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 15f, locationListener)
            }
            if (netEnabled) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 15f, locationListener)
            }

            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (p in providers) {
                val loc = locationManager.getLastKnownLocation(p)
                if (loc != null && (bestLocation == null || loc.time > bestLocation.time)) {
                    bestLocation = loc
                }
            }

            if (bestLocation != null) {
                val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bestLocation.isMock
                } else {
                    @Suppress("DEPRECATION")
                    bestLocation.isFromMockProvider
                }
                isMockLocationDetected = isMock
                onUserLocationReceived(bestLocation.latitude, bestLocation.longitude)
            } else {
                radarStatusText?.text = "📡 ننتظر إشارة الموقع من هاتفك..."
                channelsStatusText?.text = "📡 ننتظر إشارة الموقع من هاتفك..."
                mockLocationButton?.visibility = View.VISIBLE
            }
        } catch (_: SecurityException) {
        }
    }

    private fun offsetLocation(lat: Double, lng: Double, distanceKm: Double, bearingDeg: Double): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        val dLat = (distanceKm * cos(rad)) / 111.0
        val dLng = (distanceKm * sin(rad)) / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return Pair(lat + dLat, lng + dLng)
    }

    private fun onUserLocationReceived(lat: Double, lng: Double) {
        userLat = lat
        userLng = lng

        enableGpsButton?.visibility = View.GONE
        channelsEnableGpsBtn?.visibility = View.GONE
        mockLocationButton?.visibility = View.GONE

        if (!sampleRoomsInitialized) {
            sampleRoomsInitialized = true
            val p1 = offsetLocation(lat, lng, 0.8, 35.0)
            val p2 = offsetLocation(lat, lng, 2.2, 145.0)
            val p3 = offsetLocation(lat, lng, 4.3, 275.0)
            val p4 = offsetLocation(lat, lng, 6.8, 60.0)

            allRooms.add(WatchRoom("1", "⚽ مجلس مشجعي المنطقة (تجريبي)", "أحمد", p1.first, p1.second, 14))
            allRooms.add(WatchRoom("2", "🎬 سهرة فيديو جماعية (تجريبي)", "سجاد", p2.first, p2.second, 8))
            allRooms.add(WatchRoom("3", "🎙️ دردشة ليلية لأهل الحي (تجريبي)", "مصطفى", p3.first, p3.second, 5))
            allRooms.add(WatchRoom("4", "📺 غرفة بعيدة (6.8 كم)", "خالد", p4.first, p4.second, 12))
        }

        if (currentScreen == 2) {
            updateChannelsAccessState()
        } else if (currentScreen == 4) {
            renderRadarAndRooms()
        }
    }

    private fun renderRadarAndRooms() {
        val uLat = userLat ?: return
        val uLng = userLng ?: return
        val me = Location("").apply { latitude = uLat; longitude = uLng }

        val nearbyRooms = mutableListOf<Pair<WatchRoom, Float>>()
        val dots = mutableListOf<RadarView.RadarDot>()
        var outsideCount = 0

        for (room in allRooms) {
            val target = Location("").apply { latitude = room.lat; longitude = room.lng }
            val distKm = me.distanceTo(target) / 1000f
            if (distKm <= 5.0f) {
                nearbyRooms.add(Pair(room, distKm))
                val bearingRad = Math.toRadians(me.bearingTo(target).toDouble())
                dots.add(RadarView.RadarDot((distKm / 5.0f).coerceIn(0f, 1f), bearingRad, room.host, room.isUserCreated))
            } else {
                outsideCount++
            }
        }

        nearbyRooms.sortBy { it.second }
        radarView?.updateDots(dots)

        radarStatusText?.text = "✅ تم تحديد موقعك | ${nearbyRooms.size} غرف ضمن 5 كم (و $outsideCount خارج النطاق)"
        radarStatusText?.setTextColor(Color.parseColor("#4ADE80"))

        val container = roomsContainer ?: return
        container.removeAllViews()

        for ((room, distKm) in nearbyRooms) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#1E293B", 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            val titleTv = TextView(this).apply {
                text = room.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }
            val infoTv = TextView(this).apply {
                text = "📍 يبعد ${String.format(Locale.US, "%.1f", distKm)} كم  •  👤 المضيف: ${room.host}  •  👥 ${room.viewers} مشاهد"
                textSize = 13f
                setTextColor(Color.parseColor("#4ADE80"))
                setPadding(0, dp(6), 0, dp(12))
            }
            val enterBtn = Button(this).apply {
                text = "دخول الغرفة"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedBg("#E11D48", 10)
                setOnClickListener { showRoomScreen(room, distKm) }
            }
            card.addView(titleTv)
            card.addView(infoTv)
            card.addView(enterBtn)
            container.addView(card)
        }
    }

    private fun openCreateRoomDialog() {
        val uLat = userLat
        val uLng = userLng
        if (uLat == null || uLng == null) {
            Toast.makeText(this, "انتظر تحديد موقعك أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            hint = "مثال: تجمع مشجعي الحي"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle("فتح غرفة جديدة ضمن 5 كم")
            .setView(input)
            .setPositiveButton("إنشاء") { _, _ ->
                val title = input.text.toString().trim().ifEmpty { "غرفة مشاهدة جديدة" }
                allRooms.add(0, WatchRoom(System.currentTimeMillis().toString(), "🔴 $title", "أنت", uLat, uLng, 1, true))
                renderRadarAndRooms()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showRoomScreen(room: WatchRoom, distKm: Float) {
        currentScreen = 5
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val backBtn = Button(this).apply {
            text = "← الرادار"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBg("#1E293B", 10)
            setOnClickListener { showRadarScreen() }
        }
        val roomTitle = TextView(this).apply {
            text = room.title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(backBtn)
        topBar.addView(roomTitle)
        root.addView(topBar)

        val chatScroll = ScrollView(this).apply {
            background = roundedBg("#1E293B", 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(10)
            }
        }
        val chatMessages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addChatLine(sender: String, msg: String, isMe: Boolean = false) {
            chatMessages.addView(TextView(this).apply {
                text = "$sender: $msg"
                textSize = 14f
                setTextColor(if (isMe) Color.parseColor("#4ADE80") else Color.parseColor("#E2E8F0"))
                setPadding(0, dp(4), 0, dp(4))
            })
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
        addChatLine("النظام", "غرفة دردشة أهل المنطقة (${String.format(Locale.US, "%.1f", distKm)} كم)")
        chatScroll.addView(chatMessages)
        root.addView(chatScroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val messageInput = EditText(this).apply {
            hint = "اكتب رسالتك هنا..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        val sendBtn = Button(this).apply {
            text = "إرسال"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#E11D48", 12)
            setOnClickListener {
                val txt = messageInput.text.toString().trim()
                if (txt.isNotEmpty()) {
                    addChatLine("أنت", txt, isMe = true)
                    messageInput.setText("")
                }
            }
        }
        inputRow.addView(messageInput)
        inputRow.addView(sendBtn)
        root.addView(inputRow)
        setContentView(root)
    }

    // ==================== رسم الرادار المتحرك ====================
    class RadarView(context: Context) : View(context) {
        data class RadarDot(val normDistance: Float, val bearingRad: Double, val label: String, val isMine: Boolean)

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B1120"); style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E3A5F"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4ADE80"); strokeWidth = 4f }
        private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FACC15"); style = Paint.Style.FILL }
        private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4ADE80"); style = Paint.Style.FILL }
        private val myRoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E11D48"); style = Paint.Style.FILL }

        private val dots = mutableListOf<RadarDot>()
        private var sweepAngle = 0f

        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { sweepAngle = it.animatedValue as Float; invalidate() }
        }

        fun updateDots(newDots: List<RadarDot>) {
            dots.clear()
            dots.addAll(newDots)
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (!animator.isStarted) animator.start()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / 2f; val cy = h / 2f
            sweepPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 74, 222, 128)),
                floatArrayOf(0f, 0.72f, 1f)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val radius = (minOf(width, height) / 2f) - 12f
            if (radius <= 0f) return

            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius * 0.33f, ringPaint)
            canvas.drawCircle(cx, cy, radius * 0.66f, ringPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)

            canvas.save()
            canvas.rotate(sweepAngle, cx, cy)
            canvas.drawCircle(cx, cy, radius, sweepPaint)
            canvas.drawLine(cx, cy, cx + radius, cy, linePaint)
            canvas.restore()

            canvas.drawCircle(cx, cy, 10f, userPaint)

            for (dot in dots) {
                val r = radius * dot.normDistance
                val x = cx + (r * sin(dot.bearingRad)).toFloat()
                val y = cy - (r * cos(dot.bearingRad)).toFloat()
                canvas.drawCircle(x, y, 13f, if (dot.isMine) myRoomPaint else roomPaint)
            }
        }
    }
}
