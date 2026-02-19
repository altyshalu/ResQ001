package com.example.resq_android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ContactsAdapter(
    private var contacts: List<EmergencyContact> = emptyList()
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private val selectedContacts = mutableSetOf<EmergencyContact>()
    var onContactSelected: ((EmergencyContact, Boolean) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]

        holder.nameTextView.text = contact.name
        holder.phoneTextView.text = contact.phone

        // Устанавливаем состояние чекбокса
        holder.checkBox.isChecked = selectedContacts.contains(contact)

        // Отключаем слушатель при установке состояния, чтобы избежать рекурсии
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedContacts.contains(contact)

        // Обработчик изменения состояния чекбокса
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && selectedContacts.size >= 3 && !selectedContacts.contains(contact)) {
                holder.checkBox.isChecked = false
                Toast.makeText(holder.itemView.context,
                    "Можно выбрать только 3 контакта",
                    Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                selectedContacts.add(contact)
            } else {
                selectedContacts.remove(contact)
            }
            onContactSelected?.invoke(contact, isChecked)
        }

        // Обработчик клика на весь элемент
        holder.itemView.setOnClickListener {
            val isCurrentlyChecked = selectedContacts.contains(contact)

            if (!isCurrentlyChecked && selectedContacts.size >= 3) {
                Toast.makeText(holder.itemView.context,
                    "Можно выбрать только 3 контакта",
                    Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newState = !isCurrentlyChecked
            holder.checkBox.isChecked = newState
        }
    }

    override fun getItemCount(): Int = contacts.size

    fun setContacts(newContacts: List<EmergencyContact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    fun getSelectedContacts(): List<EmergencyContact> = selectedContacts.toList()

    fun setSelectedContacts(contacts: List<EmergencyContact>) {
        selectedContacts.clear()
        selectedContacts.addAll(contacts)
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.tvContactName)
        val phoneTextView: TextView = itemView.findViewById(R.id.tvContactPhone)
        val checkBox: CheckBox = itemView.findViewById(R.id.cbSelectContact)
    }
}