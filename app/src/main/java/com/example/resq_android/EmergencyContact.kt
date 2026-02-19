package com.example.resq_android

import android.os.Parcel
import android.os.Parcelable

data class EmergencyContact(
    val name: String,
    val phone: String,
    val relation: String = "Друг"  // Добавьте это поле
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "Друг"  // Добавьте сюда
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(phone)
        parcel.writeString(relation)  // Добавьте сюда
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<EmergencyContact> {
        override fun createFromParcel(parcel: Parcel): EmergencyContact {
            return EmergencyContact(parcel)
        }

        override fun newArray(size: Int): Array<EmergencyContact?> {
            return arrayOfNulls(size)
        }
    }
}