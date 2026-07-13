/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.saturnmask.gallery.customtasks.mobileactions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import com.saturnmask.gallery.R
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

private const val TAG = "AGMAViewModel"

@HiltViewModel
class MobileActionsViewModel @Inject constructor() : ViewModel() {
  fun performAction(action: Action, context: Context): String {
    return when (action) {
      // Flashlight on.
      is FlashlightOnAction -> setFlashlight(context = context, isEnabled = true)

      // Flashlight off.
      is FlashlightOffAction -> setFlashlight(context = context, isEnabled = false)

      // Create contact.
      is CreateContactAction ->
        createContact(
          context = context,
          firstName = action.firstName,
          lastName = action.lastName,
          phoneNumber = action.phoneNumber,
          email = action.email,
        )

      // Send email.
      is SendEmailAction ->
        sendEmail(context = context, to = action.to, subject = action.subject, body = action.body)

      // Show location on map.
      is ShowLocationOnMap -> showLocationOnMap(context = context, location = action.location)

      // Open wifi settings.
      is OpenWifiSettingsAction -> openWifiSettings(context = context)

      // Create calendar events.
      is CreateCalendarEventAction ->
        createCalendarEvent(context = context, datetime = action.datetime, title = action.title)

      else -> ""
    }
  }

  private fun setFlashlight(context: Context, isEnabled: Boolean): String {
    val cameraManager: CameraManager =
      context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Assuming the device has a rear camera with a flash unit (usually camera ID '0')
    var cameraId: String? = null

    try {
      // Find the ID of the camera that supports the flash unit
      for (id in cameraManager.cameraIdList) {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val isFlashAvailable =
          characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        if (isFlashAvailable) {
          cameraId = id
          break
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set flashlight", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    cameraId?.let { id ->
      try {
        cameraManager.setTorchMode(id, isEnabled)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set flashlight", e)
        return e.message ?: context.getString(R.string.unknown_error)
      }
    }

    return ""
  }

  private fun createContact(
    context: Context,
    firstName: String,
    lastName: String,
    phoneNumber: String,
    email: String,
  ): String {
    val intent =
      Intent(ContactsContract.Intents.Insert.ACTION)
        .apply { type = ContactsContract.RawContacts.CONTENT_TYPE }
        .apply {
          // Name
          putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName")
          // Inserts an email address
          putExtra(ContactsContract.Intents.Insert.EMAIL, email)
          putExtra(
            ContactsContract.Intents.Insert.EMAIL_TYPE,
            ContactsContract.CommonDataKinds.Email.TYPE_WORK,
          )
          // Inserts a phone number
          putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
          putExtra(
            ContactsContract.Intents.Insert.PHONE_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
          )
        }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create contact", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun sendEmail(context: Context, to: String, subject: String, body: String): String {
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        data = "mailto:".toUri()
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
      }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send email", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun showLocationOnMap(context: Context, location: String): String {
    val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
    val intent = Intent(Intent.ACTION_VIEW).apply { data = "geo:0,0?q=$encodedLocation".toUri() }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show location on map", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun openWifiSettings(context: Context): String {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open wifi settings", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun createCalendarEvent(context: Context, datetime: String, title: String): String {
    // Convert datetime string to ms.
    var ms = System.currentTimeMillis()
    try {
      val localDateTime = LocalDateTime.parse(datetime)
      val systemDefaultZone = ZoneId.systemDefault()
      val zonedDateTime = localDateTime.atZone(systemDefaultZone)
      ms = zonedDateTime.toInstant().toEpochMilli()
    } catch (e: Exception) {
      // Ignore parsing error.
      Log.w(TAG, "Failed to parse date time: '$datetime'", e)
    }

    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600000)
      }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create calendar event", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }
}
