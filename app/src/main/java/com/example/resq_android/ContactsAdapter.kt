package com.example.resq_android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactsAdapter : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private var contacts = listOf<EmergencyContact>()
    private val selectedContacts = mutableSetOf<EmergencyContact>()
    var onContactSelected: ((EmergencyContact, Boolean) -> Unit)? = null

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.contactName)
        val phoneTextView: TextView = itemView.findViewById(R.id.contactPhone)
        val checkBox: CheckBox = itemView.findViewById(R.id.contactCheckBox)

        fun bind(contact: EmergencyContact, isSelected: Boolean, listener: ((EmergencyContact, Boolean) -> Unit)?) {
            nameTextView.text = contact.name
            phoneTextView.text = contact.phone
            checkBox.isChecked = isSelected

            // Убираем старый слушатель, чтобы избежать багов
            checkBox.setOnCheckedChangeListener(null)

            // Устанавливаем новый слушатель
            checkBox.setOnCheckedChangeListener { _, checked ->
                listener?.invoke(contact, checked)
            }

            // Клик по всей строке переключает чекбокс
            itemView.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        val isSelected = selectedContacts.contains(contact)
        holder.bind(contact, isSelected, onContactSelected)
    }

    override fun getItemCount(): Int = contacts.size

    /**
     * Обновляет список всех контактов
     */
    fun updateContacts(newContacts: List<EmergencyContact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    /**
     * Обновляет выделение конкретного контакта
     */
    fun updateSelection(contact: EmergencyContact, isSelected: Boolean) {
        if (isSelected) {
            selectedContacts.add(contact)
        } else {
            selectedContacts.remove(contact)
        }

        val position = contacts.indexOfFirst { it.phone == contact.phone }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    /**
     * Устанавливает список выбранных контактов
     */
    fun setSelectedContacts(selected: List<EmergencyContact>) {
        selectedContacts.clear()
        selectedContacts.addAll(selected)
        notifyDataSetChanged()
    }

    /**
     * Возвращает список выбранных контактов
     */
    fun getSelectedContacts(): List<EmergencyContact> {
        return selectedContacts.toList()
    }

    /**
     * Проверяет, выбран ли контакт
     */
    fun isSelected(contact: EmergencyContact): Boolean {
        return selectedContacts.contains(contact)
    }

    /**
     * Очищает все выбранные контакты
     */
    fun clearSelection() {
        selectedContacts.clear()
        notifyDataSetChanged()
    }
}