package com.example.resq_android

import android.os.Parcel
import android.os.Parcelable

data class EmergencyContact(
    val name: String,
    val phone: String,
    var id: String = "",
    val relation: String = "Друг"
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",      // id - исправлено
        parcel.readString() ?: "Друг"   // relation
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(phone)
        parcel.writeString(id)           // id - добавлено
        parcel.writeString(relation)
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