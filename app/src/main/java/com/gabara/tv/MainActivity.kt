package com.gabara.tv

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

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

    private var currentScreen = 1 // 1 = الرئيسية, 2 = الرادار, 3 = الغرفة
    private var activeRoom: WatchRoom? = null
    private var sampleRoomsInitialized = false
    private val allRooms = mutableListOf<WatchRoom>()

    private var radarStatusText: TextView? = null
    private var radarView: RadarView? = null
    private var roomsContainer: LinearLayout? = null
    private var enableGpsButton: Button? = null
    private var mockLocationButton: Button? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocationUpdates()
        } else {
            radarStatusText?.text = "⚠️ نحتاج صلاحية الموقع لعرض أهل منطقتك ضمن 5 كم"
            radarStatusText?.setTextColor(Color.parseColor("#FBBF24"))
            mockLocationButton?.visibility = View.VISIBLE
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    3 -> showRadarScreen()
                    2 -> showHomeScreen()
                    else -> finish()
                }
            }
        })

        showHomeScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: SecurityException) {
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBg(colorHex: String, radiusDp: Int = 16): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(radiusDp).toFloat()
        }
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
            text = "مشاهدة جماعية لأهل منطقتك ضمن 5 كم"
            textSize = 18f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }

        val statusText = TextView(this).apply {
            text = "📡 استعد لتشغيل رادار المنطقة والبحث عن الغرف القريبة"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }

        val startRadarButton = Button(this).apply {
            text = "تشغيل رادار 5 كم 📡"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#E11D48", 16)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            setOnClickListener {
                showRadarScreen()
            }
        }

        root.addView(titleText)
        root.addView(subtitleText)
        root.addView(statusText)
        root.addView(startRadarButton)

        setContentView(root)
    }

    // ==================== الشاشة 2: رادار 5 كم ====================
    private fun showRadarScreen() {
        currentScreen = 2

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
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
            ).apply {
                bottomMargin = dp(16)
            }
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
            radarStatusText?.text = "⚠️ خدمة الموقع (GPS) مغلقة في جهازك"
            radarStatusText?.setTextColor(Color.parseColor("#FBBF24"))
            enableGpsButton?.visibility = View.VISIBLE
            mockLocationButton?.visibility = View.VISIBLE
            return
        }

        enableGpsButton?.visibility = View.GONE

        try {
            locationManager.removeUpdates(locationListener)
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 4000L, 15f, locationListener
                )
            }
            if (netEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 4000L, 15f, locationListener
                )
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
                onUserLocationReceived(bestLocation.latitude, bestLocation.longitude)
            } else {
                radarStatusText?.text = "📡 ننتظر إشارة الموقع من هاتفك..."
                radarStatusText?.setTextColor(Color.parseColor("#38BDF8"))
                mockLocationButton?.visibility = View.VISIBLE
            }
        } catch (_: SecurityException) {
        }
    }

    private fun offsetLocation(
        lat: Double,
        lng: Double,
        distanceKm: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        val dLat = (distanceKm * cos(rad)) / 111.0
        val dLng = (distanceKm * sin(rad)) / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return Pair(lat + dLat, lng + dLng)
    }

    private fun onUserLocationReceived(lat: Double, lng: Double) {
        userLat = lat
        userLng = lng

        enableGpsButton?.visibility = View.GONE
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

        renderRadarAndRooms()
    }

    private fun renderRadarAndRooms() {
        val uLat = userLat ?: return
        val uLng = userLng ?: return

        val me = Location("").apply {
            latitude = uLat
            longitude = uLng
        }

        val nearbyRooms = mutableListOf<Pair<WatchRoom, Float>>()
        val dots = mutableListOf<RadarView.RadarDot>()
        var outsideCount = 0

        for (room in allRooms) {
            val target = Location("").apply {
                latitude = room.lat
                longitude = room.lng
            }
            val distKm = me.distanceTo(target) / 1000f
            if (distKm <= 5.0f) {
                nearbyRooms.add(Pair(room, distKm))
                val bearingRad = Math.toRadians(me.bearingTo(target).toDouble())
                dots.add(
                    RadarView.RadarDot(
                        normDistance = (distKm / 5.0f).coerceIn(0f, 1f),
                        bearingRad = bearingRad,
                        label = room.host,
                        isMine = room.isUserCreated
                    )
                )
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

        if (nearbyRooms.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "لا توجد غرف ضمن 5 كم حالياً. افتح أول غرفة!"
                textSize = 15f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
            }
            container.addView(emptyText)
            return
        }

        for ((room, distKm) in nearbyRooms) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#1E293B", 14)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }

            val titleTv = TextView(this).apply {
                text = room.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }

            val infoTv = TextView(this).apply {
                val distFormatted = String.format(Locale.US, "%.1f", distKm)
                text = "📍 يبعد $distFormatted كم  •  👤 المضيف: ${room.host}  •  👥 ${room.viewers} مشاهد"
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
                setPadding(dp(14), dp(8), dp(14), dp(8))
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
            Toast.makeText(this, "انتظر تحديد موقعك أولاً (أو اضغط اختبار بموقع افتراضي)", Toast.LENGTH_SHORT).show()
            return
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val input = EditText(this).apply {
            hint = "مثال: تجمع مشجعي الحي"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            background = roundedBg("#1E293B", 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        wrapper.addView(input)

        AlertDialog.Builder(this)
            .setTitle("فتح غرفة جديدة ضمن 5 كم")
            .setView(wrapper)
            .setPositiveButton("إنشاء") { _, _ ->
                val title = input.text.toString().trim().ifEmpty { "غرفة مشاهدة جديدة" }
                val newRoom = WatchRoom(
                    id = System.currentTimeMillis().toString(),
                    title = "🔴 $title",
                    host = "أنت",
                    lat = uLat,
                    lng = uLng,
                    viewers = 1,
                    isUserCreated = true
                )
                allRooms.add(0, newRoom)
                renderRadarAndRooms()
                Toast.makeText(this, "تم إنشاء الغرفة على رادار الـ 5 كم!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ==================== الشاشة 3: غرفة المشاهدة والدردشة ====================
    private fun showRoomScreen(room: WatchRoom, distKm: Float) {
        currentScreen = 3
        activeRoom = room

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
            setPadding(dp(12), dp(6), dp(12), dp(6))
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

        val playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#020617", 14)
            setPadding(dp(16), dp(28), dp(16), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190)
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val playerLabel = TextView(this).apply {
            text = "📺 شاشة المشاهدة الجماعية\n(المضيف: ${room.host} • البعد: ${String.format(Locale.US, "%.1f", distKm)} كم)"
            textSize = 15f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
        }
        playerBox.addView(playerLabel)
        root.addView(playerBox)

        val chatHeader = TextView(this).apply {
            text = "💬 دردشة أهل المنطقة:"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(chatHeader)

        val chatScroll = ScrollView(this).apply {
            background = roundedBg("#1E293B", 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val chatMessages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addChatLine(sender: String, msg: String, isMe: Boolean = false) {
            val line = TextView(this).apply {
                text = "$sender: $msg"
                textSize = 14f
                setTextColor(if (isMe) Color.parseColor("#4ADE80") else Color.parseColor("#E2E8F0"))
                setPadding(0, dp(4), 0, dp(4))
            }
            chatMessages.addView(line)
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }

        addChatLine("النظام", "أهلاً بك في الغرفة ضمن نطاق 5 كم!")
        addChatLine(room.host, "حياكم الله جميعاً يا أهل المنطقة 🔥")

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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val sendBtn = Button(this).apply {
            text = "إرسال"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedBg("#E11D48", 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
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

        data class RadarDot(
            val normDistance: Float,
            val bearingRad: Double,
            val label: String,
            val isMine: Boolean
        )

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0B1120")
            style = Paint.Style.FILL
        }

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E3A5F")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80")
            strokeWidth = 4f
        }

        private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FACC15")
            style = Paint.Style.FILL
        }

        private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80")
            style = Paint.Style.FILL
        }

        private val myRoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E11D48")
            style = Paint.Style.FILL
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }

        private val dots = mutableListOf<RadarDot>()
        private var sweepAngle = 0f

        private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweepAngle = it.animatedValue as Float
                invalidate()
            }
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
            val cx = w / 2f
            val cy = h / 2f
            sweepPaint.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(130, 74, 222, 128)),
                floatArrayOf(0f, 0.72f, 1f)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
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

            for (dot in dots) {
                val r = radius * dot.normDistance
                val x = cx + (r * sin(dot.bearingRad)).toFloat()
                val y = cy - (r * cos(dot.bearingRad)).toFloat()
                canvas.drawCircle(x, y, 13f, if (dot.isMine) myRoomPaint else roomPaint)
