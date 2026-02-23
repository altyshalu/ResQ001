package com.example.resq_android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothService: BluetoothService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val cancellationTokenSource = CancellationTokenSource()
    private var selectedContacts = mutableListOf<EmergencyContact>()
    private var isSendingSOS = false
    private var isTrackingActive = false
    private var trackingJob: kotlinx.coroutines.Job? = null



    // Константы
    companion object {
        private const val REQUEST_CONTACTS = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val REQUEST_SMS_PERMISSION = 102
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 103
        private const val REQUEST_LOCATION_PERMISSION = 104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Bluetooth сервиса
        bluetoothService = BluetoothService(this)

        // Инициализация Location Client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupUI()
        checkPermissions()
        loadContacts()
        checkDeviceConnection()

        // Установка слушателей для кнопок
        setupListeners()
    }

    private fun setupUI() {
        // Кнопка SOS
        binding.sosButton.setOnClickListener {
            if (!isSendingSOS) {
                sendEmergencySOS()
            }
        }

        // Долгое нажатие для теста
        binding.sosButton.setOnLongClickListener {
            simulateHardwareTrigger()
            true
        }

        // Кнопка подключения Bluetooth
        binding.btnConnectDevice.setOnClickListener {
            connectToSim800L()
        }

        // Кнопка контактов
        binding.contactsButton.setOnClickListener {
            val intent = Intent(this, ContactsActivity::class.java)
            startActivityForResult(intent, REQUEST_CONTACTS)
        }

        // Обновление статуса устройства
        updateDeviceStatus()

        // Callback для статуса Bluetooth
        bluetoothService.onConnectionStatusChanged = { isConnected, message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                updateDeviceStatus()
            }
        }

        // Callback для полученных данных
        bluetoothService.onDataReceived = { data ->
            runOnUiThread {
                Log.d("SIM800L", "Получено: $data")
                // Обработка ответов от SIM800L
                if (data.contains("OK")) {
                    binding.tvSosStatus.text = "SMS отправлено"
                } else if (data.contains("ERROR")) {
                    binding.tvSosStatus.text = "Ошибка отправки"
                }
            }
        }
    }

    private fun setupListeners() {
        // Дополнительные слушатели
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, ContactsActivity::class.java)
            startActivityForResult(intent, REQUEST_CONTACTS)
        }

        // Новая кнопка для отслеживания
        binding.btnShareLocation.setOnClickListener {
            if (isTrackingActive) {
                stopLiveTracking()
            } else {
                startLiveTracking()
            }
        }
    }

    private fun checkPermissions() {
        // Запрашиваем разрешение на SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                REQUEST_SMS_PERMISSION
            )
        }

        // Запрашиваем разрешение на геолокацию
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }

        // Проверка разрешений для Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )

            if (permissions.any {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }) {
                ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSIONS)
            }
        } else {
            // Для старых версий Android
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_BLUETOOTH_PERMISSIONS
                )
            }
        }
    }

    private fun connectToSim800L() {
        // Ищем подключенные устройства Bluetooth
        val pairedDevices = bluetoothService.getPairedDevices()

        // Ищем HC-05/HC-06 (обычно начинается с "HC")
        val sim800Device = pairedDevices.find {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@find false
            }
            it.name?.startsWith("HC") == true ||
                    it.name?.contains("SIM800") == true
        }

        if (sim800Device != null) {
            bluetoothService.connectToDevice(sim800Device.address)
        } else {
            // Если устройство не найдено, показываем диалог для сопряжения
            if (!bluetoothService.isBluetoothEnabled()) {
                bluetoothService.requestEnableBluetooth()
            }

            // После включения Bluetooth нужно вручную сопрячься с HC-05
            Toast.makeText(this,
                "Включите Bluetooth и сопрягитесь с устройством HC-05",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentLocation(onLocationReady: (String, Double, Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Нет разрешения на геолокацию", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvSosStatus.text = "Получение координат..."
        binding.tvSosStatus.visibility = android.view.View.VISIBLE

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            if (location != null) {
                val mapsLink = "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
                val locationText = String.format(
                    "📍 Местоположение: %s (точность: %.0fм)",
                    mapsLink,
                    location.accuracy
                )
                onLocationReady(locationText, location.latitude, location.longitude)
            } else {
                // Если не удалось получить быстро, пробуем последнее известное
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                    if (lastLocation != null) {
                        val mapsLink = "https://www.google.com/maps?q=${lastLocation.latitude},${lastLocation.longitude}"
                        val locationText = String.format(
                            "📍 Последнее известное: %s",
                            mapsLink
                        )
                        onLocationReady(locationText, lastLocation.latitude, lastLocation.longitude)
                    } else {
                        binding.tvSosStatus.text = "Не удалось получить координаты"
                        Toast.makeText(this, "Включите GPS и выйдите на открытое пространство", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.addOnFailureListener { e ->
            binding.tvSosStatus.text = "Ошибка GPS: ${e.message}"
        }
    }

    private fun startLiveTracking() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Сначала выберите контакты в настройках", Toast.LENGTH_SHORT).show()
            return
        }

        isTrackingActive = true

        // 🔥 ВСТАВЬТЕ ЭТИ СТРОКИ:
        binding.tvTrackingStatus.text = "Остановить"
        binding.btnShareLocation.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)

        binding.tvSosStatus.text = "Отслеживание активно..."

        Toast.makeText(this, "Начало отслеживания. Координаты будут отправляться каждые 30 секунд", Toast.LENGTH_LONG).show()

        sendCurrentLocation(isInitial = true)

        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isTrackingActive) {
                delay(30000)
                if (isTrackingActive) {
                    sendCurrentLocation(isInitial = false)
                }
            }
        }
    }

    private fun stopLiveTracking() {
        isTrackingActive = false
        trackingJob?.cancel()

        // 🔥 ВСТАВЬТЕ ЭТИ СТРОКИ:
        binding.tvTrackingStatus.text = "Отслеживание"
        binding.btnShareLocation.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_light)

        binding.tvSosStatus.text = "Отслеживание остановлено"
        Toast.makeText(this, "Отслеживание остановлено", Toast.LENGTH_SHORT).show()
    }

    private fun sendCurrentLocation(isInitial: Boolean) {
        getCurrentLocation { locationText, lat, lon ->
            val message = if (isInitial) {
                "🚀 Начало отслеживания! $locationText"
            } else {
                "🔄 Обновление местоположения: $locationText"
            }

            sendLocationToContacts(message)

            if (isInitial) {
                runOnUiThread {
                    binding.tvSosStatus.text = "Первая точка отправлена"
                }
            }
        }
    }

    private fun sendLocationToContacts(message: String) {
        selectedContacts.forEach { contact ->
            if (bluetoothService.isConnected()) {
                bluetoothService.sendSMS(contact.phone.filter { it.isDigit() || it == '+' }, message)
            } else {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                        == PackageManager.PERMISSION_GRANTED) {
                        val smsManager = SmsManager.getDefault()
                        smsManager.sendTextMessage(
                            contact.phone.filter { it.isDigit() || it == '+' },
                            null,
                            message,
                            null,
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.e("SMS", "Ошибка отправки: ${e.message}")
                }
            }
        }
    }

    // 🔥 ИСПРАВЛЕННЫЙ МЕТОД: при нажатии SOS отправляет живые координаты
    private fun sendEmergencySOS() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Сначала выберите контакты в настройках", Toast.LENGTH_LONG).show()
            return
        }

        // Сразу получаем живые координаты и отправляем
        getCurrentLocation { locationText, lat, lon ->
            val fullMessage = "🚨 SOS! Мне нужна помощь! $locationText"

            if (bluetoothService.isConnected()) {
                sendViaSim800L(fullMessage)
            } else {
                sendViaSmsManager(fullMessage)
            }

            // Автоматически начинаем отслеживание после SOS
            if (!isTrackingActive) {
                CoroutineScope(Dispatchers.Main).launch {
                    delay(1000)
                    startLiveTracking()
                }
            }
        }
    }

    private fun sendViaSim800L(message: String) {
        isSendingSOS = true
        binding.tvSosStatus.text = "Отправка через SIM800L..."
        binding.tvSosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))

        // Анимация кнопки
        binding.sosButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                selectedContacts.forEach { contact ->
                    runOnUiThread {
                        binding.tvSosStatus.text = "Отправка на ${contact.name}..."
                    }
                    bluetoothService.sendSMS(contact.phone.filter { it.isDigit() || it == '+' }, message)
                    delay(2000)
                }

                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "✅ SOS отправлен!"
                    Toast.makeText(this@MainActivity,
                        "SOS отправлен через SIM800L!",
                        Toast.LENGTH_LONG).show()

                    // Возвращаем кнопку в исходное состояние
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    isSendingSOS = false
                }

            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "Ошибка отправки"
                    Toast.makeText(this@MainActivity,
                        "Ошибка SIM800L: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    isSendingSOS = false
                }
            }
        }
    }

    private fun sendViaSmsManager(message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Требуется разрешение на отправку SMS", Toast.LENGTH_SHORT).show()
            return
        }

        isSendingSOS = true
        binding.tvSosStatus.text = "Отправка SOS..."
        binding.tvSosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))

        // Анимация кнопки
        binding.sosButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val smsManager = SmsManager.getDefault()

                selectedContacts.forEach { contact ->
                    smsManager.sendTextMessage(
                        contact.phone.filter { it.isDigit() || it == '+' },
                        null,
                        message,
                        null,
                        null
                    )
                    delay(500)
                }

                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "✅ SOS отправлен!"
                    Toast.makeText(this@MainActivity,
                        "SOS отправлен ${selectedContacts.size} контактам через SMS",
                        Toast.LENGTH_LONG).show()

                    // Возвращаем кнопку в исходное состояние
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    isSendingSOS = false
                }

            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "Ошибка отправки"
                    Toast.makeText(this@MainActivity,
                        "Ошибка SMS: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    isSendingSOS = false
                }
            }
        }
    }

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

    private fun checkDeviceConnection() {
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            updateDeviceStatus()
        }
    }

    private fun updateDeviceStatus() {
        val isConnected = bluetoothService.isConnected()
        val status = if (isConnected) {
            binding.deviceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            "✅ Устройство подключено"
        } else {
            binding.deviceStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            "❌ Устройство не подключено"
        }
        binding.deviceStatus.text = status
    }

    private fun loadContacts() {
        val sharedPref = getSharedPreferences("ResQPrefs", MODE_PRIVATE)
        val count = sharedPref.getInt("contacts_count", 0)

        selectedContacts.clear()
        for (i in 0 until count) {
            val name = sharedPref.getString("contact_name_$i", "")
            val phone = sharedPref.getString("contact_phone_$i", "")
            if (!name.isNullOrEmpty() && !phone.isNullOrEmpty()) {
                selectedContacts.add(EmergencyContact(name, phone))
            }
        }
        updateUIWithContactsCount()
    }

    private fun updateUIWithContactsCount() {
        binding.tvSosStatus.text = "Готово к отправке (${selectedContacts.size} контактов)"
    }

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
                if (bluetoothService.isBluetoothEnabled()) {
                    connectToSim800L()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_SMS_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Разрешение на SMS получено", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Разрешение на геолокацию получено", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_BLUETOOTH_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Разрешения Bluetooth получены", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateDeviceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothService.disconnect()
        trackingJob?.cancel()
        cancellationTokenSource.cancel()
    }
}