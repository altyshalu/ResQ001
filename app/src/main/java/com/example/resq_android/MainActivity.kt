package com.example.resq_android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.resq_android.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothService: BluetoothService
    private var selectedContacts = mutableListOf<EmergencyContact>()
    private var isSendingSOS = false

    // Константы
    companion object {
        private const val REQUEST_CONTACTS = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val REQUEST_SMS_PERMISSION = 102
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 103
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Bluetooth сервиса
        bluetoothService = BluetoothService(this)

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
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
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

    private fun sendEmergencySOS() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Сначала выберите контакты в настройках", Toast.LENGTH_LONG).show()
            return
        }

        // Проверяем, подключено ли Bluetooth устройство
        if (!bluetoothService.isConnected()) {
            // Если Bluetooth не подключен, используем стандартную отправку SMS
            sendViaSmsManager()
        } else {
            // Если Bluetooth подключен, отправляем через SIM800L
            sendViaSim800L()
        }
    }

    private fun sendViaSim800L() {
        isSendingSOS = true
        binding.tvSosStatus.text = "Отправка SOS через SIM800L..."
        binding.tvSosStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))

        // Анимация кнопки
        binding.sosButton.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Получаем геолокацию (тестовые координаты)
                val location = "Широта: 55.7558, Долгота: 37.6173"

                // Отправляем SMS каждому контакту через SIM800L
                selectedContacts.forEach { contact ->
                    val message = "SOS! Мне нужна помощь! Местоположение: $location"

                    // Обновляем UI
                    runOnUiThread {
                        binding.tvSosStatus.text = "Отправка SMS на ${contact.name}..."
                    }

                    bluetoothService.sendSMS(contact.phone.filter { it.isDigit() || it == '+' }, message)

                    // Пауза между отправками
                    delay(2000)
                }

                // Обновляем UI в основном потоке
                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "SOS отправлен ${selectedContacts.size} контактам через SIM800L"
                    Toast.makeText(this@MainActivity,
                        "SOS отправлен через SIM800L!",
                        Toast.LENGTH_LONG).show()

                    // Возвращаем кнопку в исходное состояние
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()

                    // Сбрасываем статус через 3 секунды
                    delay(3000)
                    binding.tvSosStatus.text = ""
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

    private fun sendViaSmsManager() {
        // Проверяем разрешение на SMS
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
                val smsManager = android.telephony.SmsManager.getDefault()
                val location = "Моё местоположение: тестовые координаты"
                val message = "SOS! Мне срочно нужна помощь! $location"

                selectedContacts.forEach { contact ->
                    smsManager.sendTextMessage(
                        contact.phone.filter { it.isDigit() || it == '+' },
                        null,
                        message,
                        null,
                        null
                    )

                    // Задержка между отправками
                    delay(500)
                }

                // Обновляем UI в основном потоке
                CoroutineScope(Dispatchers.Main).launch {
                    binding.tvSosStatus.text = "SOS отправлен!"
                    Toast.makeText(this@MainActivity,
                        "SOS отправлен ${selectedContacts.size} контактам через SMS",
                        Toast.LENGTH_LONG).show()

                    // Возвращаем кнопку в исходное состояние
                    binding.sosButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()

                    // Сбрасываем статус через 3 секунды
                    delay(3000)
                    binding.tvSosStatus.text = ""
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
        // Симуляция срабатывания аппаратной кнопки на устройстве
        Toast.makeText(this, "Симуляция срабатывания аппаратной кнопки", Toast.LENGTH_SHORT).show()

        // Вибро-отклик
        binding.sosButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        // Отправляем тестовое SOS
        if (!isSendingSOS) {
            CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                sendEmergencySOS()
            }
        }
    }

    private fun checkDeviceConnection() {
        // Симуляция проверки подключения к аппаратному устройству
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000) // Имитация задержки подключения

            // Проверяем реальное подключение Bluetooth
            val isConnected = bluetoothService.isConnected()

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

        // Просто уберите строку с .text
        // binding.contactsButton.text = "Контакты (${selectedContacts.size}/3)"

        // Вместо этого можно обновить контент в другом месте
        updateUIWithContactsCount()
    }

    private fun updateUIWithContactsCount() {
        // Например, в заголовке или статусе
        binding.tvSosStatus.text = "Готово к отправке (${selectedContacts.size} контактов)"
        // Или в Toast при нажатии
        binding.contactsButton.setOnClickListener {
            Toast.makeText(this, "Контакты: ${selectedContacts.size}/3", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, ContactsActivity::class.java)
            startActivityForResult(intent, REQUEST_CONTACTS)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CONTACTS -> {
                if (resultCode == RESULT_OK) {
                    loadContacts() // Перезагружаем контакты
                    Toast.makeText(this, "Контакты обновлены", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_ENABLE_BT -> {
                // Bluetooth включен, можно попробовать подключиться снова
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
    }
}