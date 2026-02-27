package com.example.resq_android

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.resq_android.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothService: BluetoothService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val cancellationTokenSource = CancellationTokenSource()
    private val selectedContacts = mutableListOf<EmergencyContact>()
    private var isSendingSOS = false
    private var isTrackingActive = false
    private var trackingJob: Job? = null

    // ==================== НОВЫЕ ПОЛЯ ====================
    private var currentTrack = LocationTrack()
    private val sharedPrefs by lazy { getSharedPreferences("ResQPrefs", MODE_PRIVATE) }
    private val gson = Gson()
    private var isFirstLocation = true

    companion object {
        private const val REQUEST_CONTACTS = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val REQUEST_LOCATION_PERMISSION = 104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothService = BluetoothService(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()
        loadContacts()
        loadTrack() // 🔥 Загружаем сохранённый трек
        setupUI()
        checkDeviceConnection()
    }

    private fun setupUI() {
        // SOS кнопка — одиночное нажатие
        binding.sosButton.setOnClickListener {
            if (!isSendingSOS) {
                sendEmergencySOS()
            }
        }

        // SOS кнопка — долгое нажатие (симуляция аппаратной)
        binding.sosButton.setOnLongClickListener {
            simulateHardwareTrigger()
            true
        }

        // Подключение SIM800L по Bluetooth
        binding.btnConnectDevice.setOnClickListener {
            connectToSim800L()
        }

        // Открыть экран выбора контактов
        binding.contactsButton.setOnClickListener {
            startActivityForResult(
                Intent(this, ContactsActivity::class.java),
                REQUEST_CONTACTS
            )
        }

        // Кнопка отслеживания
        binding.btnShareLocation.setOnClickListener {
            if (isTrackingActive) stopLiveTracking() else startLiveTracking()
        }

        // Настройки → тоже открываем контакты
        binding.btnSettings.setOnClickListener {
            startActivityForResult(
                Intent(this, ContactsActivity::class.java),
                REQUEST_CONTACTS
            )
        }

        // 🔥 Новая кнопка для просмотра трека
        binding.btnViewTrack.setOnClickListener {
            showTrackInfo()
        }

        // Bluetooth колбэки
        bluetoothService.onConnectionStatusChanged = { _, message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                updateDeviceStatus()
            }
        }

        bluetoothService.onDataReceived = { data ->
            runOnUiThread {
                Log.d("SIM800L", "Получено: $data")
                when {
                    data.contains("OK") -> binding.tvSosStatus.text = "SMS отправлено через SIM800L"
                    data.contains("ERROR") -> binding.tvSosStatus.text = "Ошибка SIM800L"
                }
            }
        }
    }

    // ─── Разрешения ───────────────────────────────────────────────────────────

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (!hasPermission(Manifest.permission.SEND_SMS))
            permissions.add(Manifest.permission.SEND_SMS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_LOCATION_PERMISSION)
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ─── Bluetooth / SIM800L ──────────────────────────────────────────────────

    private fun connectToSim800L() {
        if (!bluetoothService.isBluetoothEnabled()) {
            bluetoothService.requestEnableBluetooth()
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices = bluetoothService.getPairedDevices()
        val sim800Device = pairedDevices.find {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return@find false

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@find false
            }
            it.name?.startsWith("HC") == true ||
                    it.name?.contains("SIM800") == true ||
                    it.name?.contains("BT") == true
        }

        if (sim800Device != null) {
            bluetoothService.connectToDevice(sim800Device.address)
        } else {
            Toast.makeText(
                this,
                "Устройство не найдено. Сопрягите HC-05/SIM800 в настройках Bluetooth",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ─── Функции для работы с координатами ─────────────────────────────────────

    /**
     * Форматирует координаты в читаемый вид
     */
    private fun formatCoordinates(location: Location): String {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy.toInt()

        return "📍 https://maps.google.com/?q=$lat,$lng\n🎯 Точность: $accuracy м"
    }

    /**
     * Получает текущие координаты с высоким приоритетом
     */
    private fun getCurrentLocation(callback: (Location?) -> Unit) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback(null)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null)
            return
        }

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            callback(location)
        }.addOnFailureListener { e ->
            Log.e("Location", "Ошибка получения координат: ${e.message}")
            callback(null)
        }
    }

    /**
     * Получает последние известные координаты
     */
    private fun getLastKnownLocation(callback: (Location?) -> Unit) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback(null)
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            callback(location)
        }.addOnFailureListener { e ->
            Log.e("Location", "Ошибка получения последних координат: ${e.message}")
            callback(null)
        }
    }

    // ─── SOS ──────────────────────────────────────────────────────────────────

    private fun sendEmergencySOS() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Сначала выберите контакты!", Toast.LENGTH_LONG).show()
            return
        }

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Toast.makeText(this, "Нет разрешения на отправку SMS", Toast.LENGTH_LONG).show()
            checkPermissions()
            return
        }

        isSendingSOS = true
        binding.sosButton.isEnabled = false
        binding.sosButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()
        binding.tvSosStatus.text = "📍 Получение координат..."
        binding.tvSosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Отправляем без координат
            sendSosToAllContacts("🚨 SOS! Мне нужна помощь! (GPS недоступен)")
            return
        }

        // Сначала пробуем получить текущие координаты
        getCurrentLocation { location ->
            if (location != null) {
                // Есть текущие координаты
                val locationText = formatCoordinates(location)
                val message = "🚨 SOS! Мне нужна помощь!\n$locationText"
                sendSosToAllContacts(message)
            } else {
                // Если нет текущих, пробуем последние известные
                getLastKnownLocation { lastLocation ->
                    if (lastLocation != null) {
                        val locationText = formatCoordinates(lastLocation)
                        val message = "🚨 SOS! Мне нужна помощь!\n$locationText (последние координаты)"
                        sendSosToAllContacts(message)
                    } else {
                        // Совсем нет координат
                        val message = "🚨 SOS! Мне нужна помощь!\n📍 Координаты недоступны. Включите GPS."
                        sendSosToAllContacts(message)
                    }
                }
            }
        }
    }

    private fun sendSosToAllContacts(message: String) {
        val total = selectedContacts.size
        if (total == 0) {
            Toast.makeText(this, "Нет контактов для отправки", Toast.LENGTH_SHORT).show()
            resetSOSButton()
            return
        }

        Log.d("SOS", "Отправка сообщения $total контактам")
        Log.d("SOS", "Сообщение: $message")

        binding.tvSosStatus.text = "📤 Отправка 0/$total..."

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        // Разбиваем длинное сообщение если нужно
        val parts = smsManager.divideMessage(message)

        var sentCount = 0
        var failCount = 0

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (resultCode) {
                    RESULT_OK -> {
                        sentCount++
                        Log.d("SOS", "SMS доставлена ($sentCount/$total)")
                    }
                    else -> {
                        failCount++
                        Log.e("SOS", "Ошибка SMS код: $resultCode")
                    }
                }

                val done = sentCount + failCount
                runOnUiThread {
                    binding.tvSosStatus.text = "📤 Отправка $done/$total..."

                    if (done == total) {
                        if (failCount == 0) {
                            binding.tvSosStatus.text = "✅ SOS отправлен $total контактам!"
                            binding.tvSosStatus.setTextColor(
                                ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
                            )
                        } else {
                            binding.tvSosStatus.text = "⚠️ Отправлено $sentCount/$total"
                            binding.tvSosStatus.setTextColor(
                                ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark)
                            )
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (failCount == 0) "SOS отправлен!" else "Отправлено $sentCount из $total",
                            Toast.LENGTH_LONG
                        ).show()

                        try { unregisterReceiver(this) } catch (_: Exception) {}
                        resetSOSButton()
                    }
                }
            }
        }

        val filter = IntentFilter("SOS_SMS_SENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        selectedContacts.forEach { contact ->
            val phone = contact.phone
            Log.d("SOS", "Отправка на ${contact.name}: $phone")
            try {
                val sentPI = PendingIntent.getBroadcast(
                    this, 0, Intent("SOS_SMS_SENT"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (parts.size > 1) {
                    val sentList = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentPI) } }
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentList, null)
                } else {
                    smsManager.sendTextMessage(phone, null, message, sentPI, null)
                }
            } catch (e: Exception) {
                Log.e("SOS", "Ошибка для ${contact.name}: ${e.message}")
                failCount++
            }
        }
    }

    // ─── Отслеживание местоположения ──────────────────────────────────────────

    private fun startLiveTracking() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Сначала выберите контакты!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(this, "Нет разрешения на GPS", Toast.LENGTH_SHORT).show()
            return
        }

        isTrackingActive = true
        isFirstLocation = true

        loadTrack()

        binding.tvTrackingStatus.text = "Остановить"
        binding.tvTrackingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        binding.btnShareLocation.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)

        updateTrackingStatus()

        Toast.makeText(
            this,
            "Отслеживание запущено. Координаты каждую минуту. Всего точек: ${currentTrack.points.size}",
            Toast.LENGTH_LONG
        ).show()

        sendLocationUpdateWithTrack()

        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isTrackingActive) {
                delay(60_000)
                if (isTrackingActive) {
                    sendLocationUpdateWithTrack()
                }
            }
        }
    }

    private fun stopLiveTracking() {
        isTrackingActive = false
        trackingJob?.cancel()

        val stats = buildString {
            append("📊 СТАТИСТИКА ТРЕКА:\n")
            append("📍 Точек: ${currentTrack.points.size}\n")
            append("📏 Дистанция: ${(currentTrack.getTotalDistance() / 1000).toInt()} км\n")
            append("🕐 Начало: ${currentTrack.startTime.let {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
            }}")
        }

        Log.d("Track", stats)
        Toast.makeText(this, stats, Toast.LENGTH_LONG).show()

        binding.tvTrackingStatus.text = "Отслеживание"
        binding.tvTrackingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.btnShareLocation.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_green_light)
        binding.tvSosStatus.text = "Отслеживание остановлено"
    }

    private fun sendCurrentLocationUpdate(isInitial: Boolean) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return

        getCurrentLocation { location ->
            if (location != null) {
                val locationText = formatCoordinates(location)
                val msg = if (isInitial) {
                    "🚀 Начало отслеживания!\n$locationText"
                } else {
                    "🔄 Обновление местоположения:\n$locationText"
                }
                sendLocationSms(msg)
            }
        }
    }

    private fun sendLocationSms(message: String) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) return

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        selectedContacts.forEach { contact ->
            try {
                val phone = contact.phone
                smsManager.sendTextMessage(phone, null, message, null, null)
                Log.d("Tracking", "Обновление отправлено ${contact.name}: $phone")
            } catch (e: Exception) {
                Log.e("Tracking", "Ошибка для ${contact.name}: ${e.message}")
            }
        }
    }

    // ─── Симуляция аппаратной кнопки ─────────────────────────────────────────

    private fun simulateHardwareTrigger() {
        Toast.makeText(this, "Симуляция аппаратной кнопки", Toast.LENGTH_SHORT).show()
        binding.sosButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (!isSendingSOS) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                sendEmergencySOS()
            }
        }
    }

    // ─── Устройство Bluetooth ─────────────────────────────────────────────────

    private fun checkDeviceConnection() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            updateDeviceStatus()
        }
    }

    private fun updateDeviceStatus() {
        val isConnected = bluetoothService.isConnected()
        binding.deviceStatus.text = if (isConnected) {
            binding.deviceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            "✅ Устройство подключено"
        } else {
            binding.deviceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            "❌ Устройство не подключено"
        }
    }

    // ─── Контакты ─────────────────────────────────────────────────────────────

    private fun loadContacts() {
        val sharedPref = getSharedPreferences("ResQPrefs", MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPref.getString("selected_contacts", "[]")
        val type = object : TypeToken<List<EmergencyContact>>() {}.type

        try {
            val saved: List<EmergencyContact> = gson.fromJson(json, type)
            selectedContacts.clear()
            selectedContacts.addAll(saved)
        } catch (e: Exception) {
            Log.e("Contacts", "Ошибка загрузки контактов: ${e.message}")
            selectedContacts.clear()
        }

        Log.d("Contacts", "Загружено контактов: ${selectedContacts.size}")
        selectedContacts.forEach { Log.d("Contacts", "  ${it.name}: ${it.phone}") }

        binding.tvSosStatus.text = "Готов (${selectedContacts.size} контактов)"
    }

    // ─── Сброс кнопки ─────────────────────────────────────────────────────────

    private fun resetSOSButton() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            isSendingSOS = false
            binding.sosButton.isEnabled = true
            binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            binding.tvSosStatus.text = "Готов (${selectedContacts.size} контактов)"
            binding.tvSosStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CONTACTS -> {
                if (resultCode == RESULT_OK) {
                    loadContacts()
                    Toast.makeText(this, "Контакты обновлены", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_ENABLE_BT -> {
                if (bluetoothService.isBluetoothEnabled()) connectToSim800L()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Все разрешения получены ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Некоторые разрешения не выданы — SMS/GPS могут не работать", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
        updateDeviceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.disconnect()
        trackingJob?.cancel()
        cancellationTokenSource.cancel()
    }

    // ==================== МОДЕЛЬ ДАННЫХ ====================

    data class LocationPoint(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val speed: Float? = null
    ) {
        fun toMapLink(): String = "https://maps.google.com/?q=$latitude,$longitude"

        fun getFormattedTime(): String {
            val format = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            return format.format(java.util.Date(timestamp))
        }
    }

    data class LocationTrack(
        val points: MutableList<LocationPoint> = mutableListOf(),
        val startTime: Long = System.currentTimeMillis()
    ) {
        fun addPoint(point: LocationPoint) {
            points.add(point)
        }

        fun getLastPoint(): LocationPoint? = points.lastOrNull()

        fun getPointCount(): Int = points.size

        fun getTotalDistance(): Double {
            if (points.size < 2) return 0.0

            var total = 0.0
            for (i in 0 until points.size - 1) {
                total += calculateDistance(
                    points[i].latitude, points[i].longitude,
                    points[i + 1].latitude, points[i + 1].longitude
                )
            }
            return total
        }

        private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371000
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val deltaLat = Math.toRadians(lat2 - lat1)
            val deltaLon = Math.toRadians(lon2 - lon1)

            val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                    Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                    Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

            return R * c
        }
    }

    // ==================== СОХРАНЕНИЕ И ЗАГРУЗКА ТРЕКА ====================

    private fun saveTrack() {
        val json = gson.toJson(currentTrack)
        sharedPrefs.edit().putString("current_track", json).apply()
        Log.d("Track", "Трек сохранён: ${currentTrack.points.size} точек")
    }

    private fun loadTrack() {
        val json = sharedPrefs.getString("current_track", null)
        if (json != null) {
            try {
                val type = object : TypeToken<LocationTrack>() {}.type
                currentTrack = gson.fromJson(json, type)
                Log.d("Track", "Трек загружен: ${currentTrack.points.size} точек")
            } catch (e: Exception) {
                Log.e("Track", "Ошибка загрузки трека: ${e.message}")
                currentTrack = LocationTrack()
            }
        }
    }

    private fun clearTrack() {
        currentTrack = LocationTrack()
        saveTrack()
        Log.d("Track", "Трек очищен")
    }

    // ==================== РАБОТА С КООРДИНАТАМИ ====================

    private fun getCurrentLocationAndTrack(callback: (LocationPoint?) -> Unit) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback(null)
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback(null)
                return
            }

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    val point = LocationPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = System.currentTimeMillis(),
                        speed = location.speed
                    )

                    currentTrack.addPoint(point)
                    saveTrack()

                    Log.d("Track", "✅ Точка ${currentTrack.points.size} добавлена: ${point.latitude}, ${point.longitude}")
                    callback(point)
                } else {
                    Log.d("Track", "⚠️ Точка не получена")
                    callback(null)
                }
            }.addOnFailureListener { e ->
                Log.e("Track", "❌ Ошибка: ${e.message}")
                callback(null)
            }
        } catch (e: Exception) {
            Log.e("Track", "❌ Исключение: ${e.message}")
            callback(null)
        }
    }

    private fun getLastKnownLocationPoint(callback: (LocationPoint?) -> Unit) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback(null)
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback(null)
                return
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val point = LocationPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = System.currentTimeMillis(),
                        speed = location.speed
                    )
                    callback(point)
                } else {
                    callback(null)
                }
            }.addOnFailureListener {
                callback(null)
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    // ==================== ОТСЛЕЖИВАНИЕ С ТРЕКОМ ====================

    private fun updateTrackingStatus() {
        val lastPoint = currentTrack.getLastPoint()
        binding.tvSosStatus.text = buildString {
            append("📡 Точек: ${currentTrack.points.size}")
            if (lastPoint != null) {
                append(" | Посл: ${lastPoint.getFormattedTime()}")
                if (currentTrack.points.size >= 2) {
                    append(" | ${(currentTrack.getTotalDistance() / 1000).toInt()} км")
                }
            }
        }
    }

    private fun sendLocationUpdateWithTrack() {
        getCurrentLocationAndTrack { point ->
            if (point != null) {
                val message = if (isFirstLocation) {
                    isFirstLocation = false
                    buildString {
                        append("🚀 НАЧАЛО ОТСЛЕЖИВАНИЯ\n")
                        append("📍 ${point.toMapLink()}\n")
                        append("🎯 Точность: ${point.accuracy.toInt()}м\n")
                        append("🕐 ${point.getFormattedTime()}\n")
                        append("📊 Всего точек: ${currentTrack.points.size}")
                    }
                } else {
                    buildString {
                        append("🔄 ОБНОВЛЕНИЕ #${currentTrack.points.size}\n")
                        append("📍 ${point.toMapLink()}\n")
                        append("🎯 Точность: ${point.accuracy.toInt()}м\n")
                        append("🕐 ${point.getFormattedTime()}\n")

                        if (currentTrack.points.size >= 2) {
                            val prev = currentTrack.points[currentTrack.points.size - 2]
                            val distance = calculateDistance(
                                prev.latitude, prev.longitude,
                                point.latitude, point.longitude
                            )
                            append("📏 +${distance.toInt()}м от предыдущей\n")
                            append("📊 Всего: ${(currentTrack.getTotalDistance() / 1000).toInt()} км")
                        }
                    }
                }

                sendLocationSms(message)
                updateTrackingStatus()

            } else {
                getLastKnownLocationPoint { lastPoint ->
                    if (lastPoint != null) {
                        val message = buildString {
                            append("⚠️ GPS временно недоступен\n")
                            append("📍 Последние координаты: ${lastPoint.toMapLink()}\n")
                            append("🕐 ${lastPoint.getFormattedTime()}")
                        }
                        sendLocationSms(message)
                    }
                }
            }
        }
    }
    /**
     * Вычисляет расстояние между двумя точками в метрах
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000 // Радиус Земли в метрах
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return R * c
    }

    private fun showTrackInfo() {
        if (currentTrack.points.isEmpty()) {
            Toast.makeText(this, "Нет данных трека", Toast.LENGTH_SHORT).show()
            return
        }

        val info = buildString {
            append("📊 ИНФОРМАЦИЯ О ТРЕКЕ\n")
            append("==================\n")
            append("📍 Всего точек: ${currentTrack.points.size}\n")
            append("📏 Дистанция: ${(currentTrack.getTotalDistance() / 1000).toInt()} км\n")
            append("🕐 Начало: ${currentTrack.startTime.let {
                java.text.SimpleDateFormat("HH:mm dd.MM", java.util.Locale.getDefault())
                    .format(java.util.Date(it))
            }}\n")
            append("🕐 Последняя: ${currentTrack.getLastPoint()?.getFormattedTime()}\n\n")

            currentTrack.points.takeLast(5).reversed().forEachIndexed { index, point ->
                val pointNum = currentTrack.points.size - index
                append("📍 Точка #$pointNum: ${point.getFormattedTime()}\n")
                append("   ${point.toMapLink()}\n")
                append("   Точность: ${point.accuracy.toInt()}м\n")
                if (point.speed != null && point.speed > 0) {
                    append("   Скорость: ${(point.speed * 3.6).toInt()} км/ч\n")
                }
            }
        }

        Log.d("Track", info)

        android.app.AlertDialog.Builder(this)
            .setTitle("История трека")
            .setMessage(info)
            .setPositiveButton("ОК") { _, _ -> }
            .setNeutralButton("Очистить") { _, _ ->
                clearTrack()
                Toast.makeText(this, "Трек очищен", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
