package com.example.resq_android


import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothService(private val activity: Activity) {

    companion object {
        private const val TAG = "BluetoothService"
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 2
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var connected = false

    // Callback для статуса подключения
    var onConnectionStatusChanged: ((Boolean, String) -> Unit)? = null
    var onDataReceived: ((String) -> Unit)? = null

    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun requestEnableBluetooth() {
        if (!isBluetoothEnabled()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            ActivityCompat.startActivityForResult(
                activity, enableBtIntent, REQUEST_ENABLE_BT, null
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        Thread {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                device?.let {
                    socket = it.createRfcommSocketToServiceRecord(MY_UUID)
                    socket?.connect()

                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream
                    connected = true

                    activity.runOnUiThread {
                        onConnectionStatusChanged?.invoke(true, "Подключено к ${it.name}")
                    }

                    // Запускаем чтение данных
                    startReading()

                } ?: run {
                    activity.runOnUiThread {
                        onConnectionStatusChanged?.invoke(false, "Устройство не найдено")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Ошибка подключения: ${e.message}")
                connected = false
                activity.runOnUiThread {
                    onConnectionStatusChanged?.invoke(false, "Ошибка: ${e.message}")
                }
            }
        }.start()
    }

    fun sendCommand(command: String) {
        if (!connected || outputStream == null) {
            Log.e(TAG, "Не подключено к устройству")
            return
        }

        Thread {
            try {
                // Добавляем \r\n в конец команды AT
                val cmd = if (!command.endsWith("\r\n")) "$command\r\n" else command
                outputStream?.write(cmd.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Отправлено: $command")
            } catch (e: IOException) {
                Log.e(TAG, "Ошибка отправки: ${e.message}")
                disconnect()
            }
        }.start()
    }

    fun sendSMS(phoneNumber: String, message: String) {
        // Формируем команды для отправки SMS через SIM800L
        val commands = listOf(
            "AT+CMGF=1",                    // Текстовый режим
            "AT+CMGS=\"$phoneNumber\"",     // Указываем номер
            "$message${Char(26)}"           // Сообщение + Ctrl+Z
        )

        Thread {
            commands.forEach { command ->
                sendCommand(command)
                Thread.sleep(500) // Пауза между командами
            }
        }.start()
    }

    private fun startReading() {
        Thread {
            val buffer = ByteArray(1024)
            var bytes: Int

            while (connected) {
                try {
                    bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)
                        activity.runOnUiThread {
                            onDataReceived?.invoke(data)
                            Log.d(TAG, "Получено: $data")
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Ошибка чтения: ${e.message}")
                    disconnect()
                    break
                }
            }
        }.start()
    }

    fun disconnect() {
        try {
            connected = false
            inputStream?.close()
            outputStream?.close()
            socket?.close()

            activity.runOnUiThread {
                onConnectionStatusChanged?.invoke(false, "Отключено")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка отключения: ${e.message}")
        }
    }

    fun isConnected(): Boolean = connected

    fun getConnectionStatus(): String {
        return if (connected) "Подключено" else "Не подключено"
    }
}