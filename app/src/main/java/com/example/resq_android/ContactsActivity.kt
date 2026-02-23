package com.example.resq_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resq_android.databinding.ActivityContactsBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var contactsAdapter: ContactsAdapter
    private val allContacts = mutableListOf<EmergencyContact>()
    private val selectedContacts = mutableListOf<EmergencyContact>()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val PICK_CONTACT_REQUEST = 102
        private const val MAX_CONTACTS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        loadSavedContacts()
        checkContactsPermission()
    }

    private fun setupRecyclerView() {
        contactsAdapter = ContactsAdapter()
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.contactsRecyclerView.adapter = contactsAdapter

        contactsAdapter.onContactSelected = { contact, isSelected ->
            if (isSelected) {
                if (selectedContacts.size >= MAX_CONTACTS) {
                    Toast.makeText(this, "Можно выбрать только $MAX_CONTACTS контакта", Toast.LENGTH_SHORT).show()
                    contactsAdapter.updateSelection(contact, false)
                } else {
                    selectedContacts.add(contact)
                    updateSelectedCount()
                }
            } else {
                selectedContacts.remove(contact)
                updateSelectedCount()
            }
        }
    }

    private fun setupButtons() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.saveButton.setOnClickListener {
            saveSelectedContacts()
        }

        binding.pickContactButton.setOnClickListener {
            if (selectedContacts.size >= MAX_CONTACTS) {
                Toast.makeText(this, "Вы уже выбрали максимальное количество контактов", Toast.LENGTH_SHORT).show()
            } else {
                pickContactFromPhone()
            }
        }
    }

    private fun updateSelectedCount() {
        binding.selectedCountText.text = "Выбрано: ${selectedContacts.size}/$MAX_CONTACTS"
    }

    private fun checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_REQUEST_CODE
            )
        } else {
            loadContacts()
        }
    }

    private fun loadContacts() {
        allContacts.clear()

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val contactId = it.getString(idColumn)
                val name = it.getString(nameColumn)
                val phone = it.getString(numberColumn)

                if (allContacts.none { contact -> contact.phone == phone }) {
                    allContacts.add(EmergencyContact(name, phone, contactId))
                }
            }
        }

        contactsAdapter.updateContacts(allContacts)
        highlightSavedContacts()
    }

    private fun loadSavedContacts() {
        val sharedPref = getSharedPreferences("ResQPrefs", MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPref.getString("selected_contacts", "[]")
        val type = object : TypeToken<List<EmergencyContact>>() {}.type

        try {
            val saved: List<EmergencyContact> = gson.fromJson(json, type)
            selectedContacts.clear()
            selectedContacts.addAll(saved)
            updateSelectedCount()
        } catch (e: Exception) {
            selectedContacts.clear()
        }
    }

    private fun highlightSavedContacts() {
        if (selectedContacts.isNotEmpty() && allContacts.isNotEmpty()) {
            contactsAdapter.setSelectedContacts(selectedContacts)
        }
    }

    private fun pickContactFromPhone() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(intent, PICK_CONTACT_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_CONTACT_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { contactUri ->
                getContactFromUri(contactUri)?.let { contact ->
                    if (selectedContacts.any { it.phone == contact.phone }) {
                        Toast.makeText(this, "Этот контакт уже выбран", Toast.LENGTH_SHORT).show()
                        return
                    }

                    if (allContacts.none { it.phone == contact.phone }) {
                        allContacts.add(contact)
                        contactsAdapter.updateContacts(allContacts)
                    }

                    selectedContacts.add(contact)
                    contactsAdapter.updateSelection(contact, true)
                    updateSelectedCount()

                    Toast.makeText(this, "Добавлен: ${contact.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getContactFromUri(contactUri: Uri): EmergencyContact? {
        return try {
            val cursor = contentResolver.query(contactUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberColumn = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    val name = if (nameColumn >= 0) it.getString(nameColumn) else "Без имени"
                    val phone = if (numberColumn >= 0) it.getString(numberColumn) else ""

                    val formattedPhone = phone.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")

                    EmergencyContact(
                        name = name,
                        phone = formattedPhone,
                        id = System.currentTimeMillis().toString()
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadContacts()
                } else {
                    Toast.makeText(this, "Нет доступа к контактам", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun saveSelectedContacts() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один контакт", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedPref = getSharedPreferences("ResQPrefs", MODE_PRIVATE)
        val editor = sharedPref.edit()
        val gson = Gson()

        val json = gson.toJson(selectedContacts)
        editor.putString("selected_contacts", json)
        editor.apply()

        editor.putInt("contacts_count", selectedContacts.size)
        selectedContacts.forEachIndexed { index, contact ->
            editor.putString("contact_name_$index", contact.name)
            editor.putString("contact_phone_$index", contact.phone)
        }
        editor.apply()

        Toast.makeText(this, "Сохранено ${selectedContacts.size} контактов", Toast.LENGTH_SHORT).show()

        val resultIntent = Intent()
        resultIntent.putExtra("contacts_updated", true)
        setResult(RESULT_OK, resultIntent)

        finish()
    }
}