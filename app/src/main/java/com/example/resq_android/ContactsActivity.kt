package com.example.resq_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.resq_android.databinding.ActivityContactsBinding

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private val contactsAdapter = ContactsAdapter()
    private val selectedContacts = mutableListOf<EmergencyContact>()

    companion object {
        private const val PERMISSION_CONTACTS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        checkContactsPermission()
        updateSelectedCount() // Инициализируем счетчик
    }

    private fun setupRecyclerView() {
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.contactsRecyclerView.adapter = contactsAdapter

        contactsAdapter.onContactSelected = { contact, isSelected ->
            if (isSelected) {
                if (selectedContacts.size >= 3) {
                    Toast.makeText(this, "Можно выбрать только 3 контакта", Toast.LENGTH_SHORT).show()
                    // Просто ничего не делаем, выход из лямбды автоматический
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
            pickContactFromPhone()
        }
    }

    private fun updateSelectedCount() {
        binding.selectedCountText.text = "Выбрано: ${selectedContacts.size}/3"
    }

    private fun checkContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_CONTACTS), PERMISSION_CONTACTS
            )
        } else {
            loadContacts()
        }
    }

    private fun loadContacts() {
        // ... код загрузки контактов ...
    }

    private fun pickContactFromPhone() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, PERMISSION_CONTACTS)
    }

    private fun saveSelectedContacts() {
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Выберите хотя бы один контакт", Toast.LENGTH_SHORT).show()
            return
        }

        // Сохранение в SharedPreferences
        val sharedPref = getSharedPreferences("ResQPrefs", MODE_PRIVATE)
        with(sharedPref.edit()) {
            // Сохраняем выбранные контакты
            putInt("contacts_count", selectedContacts.size)
            selectedContacts.forEachIndexed { index, contact ->
                putString("contact_name_$index", contact.name)
                putString("contact_phone_$index", contact.phone)
                putString("contact_relation_$index", contact.relation)
            }
            apply()
        }

        Toast.makeText(this, "Сохранено ${selectedContacts.size} контактов", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ... остальные методы (onActivityResult, onRequestPermissionsResult) ...
}