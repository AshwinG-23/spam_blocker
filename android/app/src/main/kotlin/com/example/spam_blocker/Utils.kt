package com.example.spam_blocker

import android.Manifest
import android.content.ContentProviderOperation
import android.util.Log
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore

object Utils {

    private const val CONTACT_NAME = "BlockedContact"

    fun updateContacts(context: Context, newNumbers:MutableSet<String>, prefs: SharedPreferences){

        if(!context.hasContactPermissions()){
            Log.d("Utils", "Cant update contacts, no permissions")
            return
        }
        val contactId = contactExists(context)

        if(contactId == null){ //create contact if does not exist
            Log.d("Utils", "Creating contact")
            val ops = ArrayList<ContentProviderOperation>()
            ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build()

            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, CONTACT_NAME).build()

            newNumbers.forEach { number ->
                ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            }
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                Log.d("Utils", "Created contact “${CONTACT_NAME}” with ${newNumbers.size} numbers")
            } catch (e: Exception) {
                Log.e("Utils", "Failed to create contact “${CONTACT_NAME}”", e)
            }
        }
        else{
            Log.d("Utils", "modifying contact")
            val oldNumbers = prefs.getStringSet("blockedNumbersSet", emptySet()) ?: emptySet()
            val addedNumbers = newNumbers - oldNumbers
            val deletedNumbers = oldNumbers - newNumbers
            if(addedNumbers.isNotEmpty() || deletedNumbers.isNotEmpty()){
                val ops = ArrayList<ContentProviderOperation>()
                deletedNumbers.forEach { number ->
                    ops += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " + "${ContactsContract.Data.MIMETYPE} = ? AND " + "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ?",
                            arrayOf(
                                contactId.toString(),
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                                number
                            )
                        ).build()
                }
                addedNumbers.forEach { number ->
                    ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)

                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        ).build()
                }
                try {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                    Log.d("Utils", "modified numbers to “${CONTACT_NAME}”")
                } catch (e: Exception) {
                    Log.e("Utils", "Failed to modify numbers to $CONTACT_NAME", e)
                }
            }
        }


    }

    fun checkNumber(context: Context, num: String, onResult: (Boolean)->Unit){
        val prefs: SharedPreferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val isAllowed = prefs.getBoolean("flutter.isLoggedIn", false)
        if(isAllowed) {
            val numSet = prefs.getStringSet("blockedNumbersSet", emptySet()) ?: emptySet()
            val codeSet = prefs.getStringSet("blockedCodesSet", emptySet()) ?: emptySet()

            Log.d("getNumber", "$numSet")
            Log.d("getNumber", "$codeSet")

            val isBlocked = numSet.contains(num) || codeSet.any{ code -> num.startsWith(code)}

            Log.d("getNumber", "checkNumber: is $num blocked? $isBlocked")
            onResult(isBlocked)
        }
        else{
            onResult(false)
        }
    }

    fun isDeviceRegistered(context: Context, firestore: FirebaseFirestore, prefs: SharedPreferences, callback: (Boolean) -> Unit) {
        //callback(true)
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        Log.d("regis", id)
        firestore.collection("requests_authentication").document(id).get().addOnSuccessListener { doc: DocumentSnapshot? ->
            val isRegistered = if (doc != null && doc.exists()) {
                doc.getBoolean("authenticated") == true
            } else {
                false
            }
            prefs.edit { putBoolean("flutter.isLoggedIn", isRegistered) }
            callback(isRegistered)

        }.addOnFailureListener { exception ->
            Log.d("Utils", "network error while getting device status", exception)
            val isRegistered = prefs.getBoolean("flutter.isLoggedIn", false)
            callback(isRegistered)
        }
    }
    private fun Context.hasContactPermissions(): Boolean {
        val readOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val writeOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        return readOk && writeOk
    }
    private fun contactExists(context: Context): Long?{
        Log.d("Utils","Checking if contact exists")
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.Data.RAW_CONTACT_ID)
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND " + "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, CONTACT_NAME)
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Found at least one matching name
                    return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID))
                }
            }
        return null
    }
}