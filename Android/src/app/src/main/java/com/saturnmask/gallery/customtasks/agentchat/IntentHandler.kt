/*
 * Copyright 2026 Google LLC
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
package com.saturnmask.gallery.customtasks.agentchat

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toUri
import com.saturnmask.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.saturnmask.gallery.proto.ScheduledNotification
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import java.lang.Exception
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true) data class ReadCalendarEventsParams(val date: String)

@JsonClass(generateAdapter = true)
data class CreateContactParams(
  val first_name: String,
  val last_name: String,
  val phone_number: String,
  val email: String,
)

@JsonClass(generateAdapter = true) data class ShowLocationOnMapParams(val location: String)

@JsonClass(generateAdapter = true) data class NavigateToParams(val destination: String)

@JsonClass(generateAdapter = true) data class CallPhoneParams(val phone_number: String)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true)
data class ReadCalendarEventsResponse(val events: List<CalendarEventDto>)

enum class IntentAction(val action: String) {
  SEND_EMAIL("send_email"),
  SEND_SMS("send_sms"),
  CREATE_CALENDAR_EVENT("create_calendar_event"),
  READ_CALENDAR_EVENTS("read_calendar_events"),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  SCHEDULE_NOTIFICATION("schedule_notification"),
  // Consolidated here from the standalone MobileActionsTools (customtasks/mobileactions) so
  // Agent Chat's runIntent gains the same capabilities through the one safely-scoped allowlist,
  // instead of via a second, separate, hardcoded-methods tool. MobileActionsTools itself is left
  // untouched — it backs its own dedicated single-purpose screen and isn't part of Agent Chat.
  TURN_ON_FLASHLIGHT("turn_on_flashlight"),
  TURN_OFF_FLASHLIGHT("turn_off_flashlight"),
  OPEN_WIFI_SETTINGS("open_wifi_settings"),
  CREATE_CONTACT("create_contact"),
  SHOW_LOCATION_ON_MAP("show_location_on_map"),
  // New: courier/delivery-style actions. Both deliberately use the "soft-confirm" Intent variants
  // (ACTION_DIAL opens the dialer for a human tap, not ACTION_CALL which places the call
  // immediately and requires the dangerous CALL_PHONE permission) so the safety property of every
  // other action here — nothing fires without a human's final tap in the target app — holds for
  // these too.
  NAVIGATE_TO("navigate_to"),
  CALL_PHONE("call_phone");

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

@JsonClass(generateAdapter = true)
data class ScheduleNotificationParams(
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val deeplink: String? = null,
  val task_id: String? = null,
  val model_name: String? = null,
  val year: Int? = null,
  val month: Int? = null,
  val day: Int? = null,
  val repeat_daily: Boolean? = null,
)

object IntentHandler {
  private const val TAG = "IntentHandler"

  suspend fun handleAction(
    context: Context,
    action: String,
    parameters: String,
    // requestPermission is a suspend function that takes a permission string and returns true if
    // the permission is granted, false otherwise.
    requestPermission: suspend (String) -> Boolean,
  ): String {
    return when (IntentAction.from(action)) {
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val intent =
              Intent(Intent.ACTION_SEND).apply {
                data = "mailto:".toUri()
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(params.extra_email))
                putExtra(Intent.EXTRA_SUBJECT, params.extra_subject)
                putExtra(Intent.EXTRA_TEXT, params.extra_text)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "smsto:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", params.sms_body)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = Events.CONTENT_URI
                putExtra(Events.TITLE, params.title)
                putExtra(Events.DESCRIPTION, params.description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.READ_CALENDAR_EVENTS -> {
        readCalendarEvents(context, parameters, requestPermission)
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.SCHEDULE_NOTIFICATION -> {
        scheduleNotification(context, parameters)
      }
      IntentAction.TURN_ON_FLASHLIGHT -> setFlashlight(context, isEnabled = true)
      IntentAction.TURN_OFF_FLASHLIGHT -> setFlashlight(context, isEnabled = false)
      IntentAction.OPEN_WIFI_SETTINGS -> {
        try {
          context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
          "succeeded"
        } catch (e: Exception) {
          Log.e(TAG, "Failed to open wifi settings", e)
          "failed"
        }
      }
      IntentAction.CREATE_CONTACT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateContactParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            // ACTION_INSERT opens the Contacts app's "new contact" screen pre-filled — the user
            // still has to tap Save there. Nothing is written to the contacts DB by this call
            // alone.
            val intent =
              Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(
                  ContactsContract.Intents.Insert.NAME,
                  "${params.first_name} ${params.last_name}".trim(),
                )
                putExtra(ContactsContract.Intents.Insert.EMAIL, params.email)
                putExtra(
                  ContactsContract.Intents.Insert.EMAIL_TYPE,
                  ContactsContract.CommonDataKinds.Email.TYPE_WORK,
                )
                putExtra(ContactsContract.Intents.Insert.PHONE, params.phone_number)
                putExtra(
                  ContactsContract.Intents.Insert.PHONE_TYPE,
                  ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
                )
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_contact parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_contact parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SHOW_LOCATION_ON_MAP -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(ShowLocationOnMapParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val encoded = URLEncoder.encode(params.location, StandardCharsets.UTF_8.toString())
            val intent = Intent(Intent.ACTION_VIEW).apply { data = "geo:0,0?q=$encoded".toUri() }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse show_location_on_map parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse show_location_on_map parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.NAVIGATE_TO -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(NavigateToParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val encoded = URLEncoder.encode(params.destination, StandardCharsets.UTF_8.toString())
            // google.navigation: launches turn-by-turn navigation mode in Maps directly, rather
            // than just showing a pin — the courier-relevant case. The user still sees Maps'
            // own route-confirmation UI before anything actually starts.
            val intent = Intent(Intent.ACTION_VIEW).apply { data = "google.navigation:q=$encoded".toUri() }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse navigate_to parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse navigate_to parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CALL_PHONE -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CallPhoneParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            // ACTION_DIAL (not ACTION_CALL) — opens the dialer with the number pre-filled. The
            // user has to tap the call button themselves; nothing is dialed automatically, and no
            // CALL_PHONE runtime permission is needed for this variant.
            val intent = Intent(Intent.ACTION_DIAL).apply { data = "tel:${params.phone_number}".toUri() }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse call_phone parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse call_phone parameters: $parameters", e)
          "failed"
        }
      }
      null -> "failed"
    }
  }

  /**
   * Toggles the rear camera's torch (flashlight) directly — no confirmation UI to fall back on
   * for this one (there's no separate "app" to hand it off to), but the effect is instantaneous,
   * trivially visible, and trivially reversible, which is the same judgment call the
   * pre-existing MobileActionsTools implementation already made for this specific action.
   */
  private fun setFlashlight(context: Context, isEnabled: Boolean): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
      val cameraId =
        cameraManager.cameraIdList.firstOrNull { id ->
          cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ==
            true
        }
      if (cameraId == null) {
        Log.w(TAG, "No camera with flash found")
        return "failed: no flash available on this device"
      }
      cameraManager.setTorchMode(cameraId, isEnabled)
      return "succeeded"
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set flashlight", e)
      return "failed: ${e.message}"
    }
  }

  suspend fun readCalendarEvents(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(android.Manifest.permission.READ_CALENDAR)
      if (!granted) {
        Log.e(TAG, "READ_CALENDAR permission denied by user")
        return "failed: READ_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ReadCalendarEventsParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateObj = format.parse(params.date)
        if (dateObj != null) {
          val cal =
            Calendar.getInstance().apply {
              timeInMillis = dateObj.time
              set(Calendar.HOUR_OF_DAY, 0)
              set(Calendar.MINUTE, 0)
              set(Calendar.SECOND, 0)
              set(Calendar.MILLISECOND, 0)
            }
          val startOfDayMillis = cal.timeInMillis

          cal.apply {
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
          }
          val endOfDayMillis = cal.timeInMillis

          val projection =
            arrayOf(Instances.TITLE, Instances.DESCRIPTION, Instances.BEGIN, Instances.END)

          val builder = Instances.CONTENT_URI.buildUpon()
          android.content.ContentUris.appendId(builder, startOfDayMillis)
          android.content.ContentUris.appendId(builder, endOfDayMillis)

          val cursor =
            context.contentResolver.query(
              builder.build(),
              projection,
              null,
              null,
              "${Instances.BEGIN} ASC",
            )

          val eventsList = mutableListOf<CalendarEventDto>()
          cursor?.use { c ->
            val titleIdx = c.getColumnIndex(Instances.TITLE)
            val descIdx = c.getColumnIndex(Instances.DESCRIPTION)
            val startIdx = c.getColumnIndex(Instances.BEGIN)
            val endIdx = c.getColumnIndex(Instances.END)
            val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            while (c.moveToNext()) {
              val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
              val desc = if (descIdx >= 0) c.getString(descIdx) ?: "" else ""
              val start = if (startIdx >= 0) c.getLong(startIdx) else 0L
              val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
              eventsList.add(
                CalendarEventDto(
                  title = title,
                  description = desc,
                  begin_time = if (start > 0) timeFormat.format(Date(start)) else "",
                  end_time = if (end > 0) timeFormat.format(Date(end)) else "",
                )
              )
            }
          }
          val responseAdapter = moshi.adapter(ReadCalendarEventsResponse::class.java)
          return responseAdapter.toJson(ReadCalendarEventsResponse(eventsList))
        } else {
          Log.e(TAG, "Failed to parse read_calendar_events date: ${params.date}")
          return "failed"
        }
      } else {
        Log.e(TAG, "Failed to parse read_calendar_events parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read calendar events: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  fun scheduleNotification(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ScheduleNotificationParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val notificationProtoBuilder =
          ScheduledNotification.newBuilder()
            .setId(java.util.UUID.randomUUID().toString())
            .setTitle(params.title)
            .setMessage(params.message)
            .setHour(params.hour)
            .setMinute(params.minute)
            .setChannelId("agent_skill_tasks_channel")
            .setChannelName("Agent Skill Task")
        if (params.deeplink != null) {
          notificationProtoBuilder.setDeeplink(params.deeplink)
        } else if (params.task_id != null && params.model_name != null) {
          val uri =
            "com.saturnmask.gallery://model/${params.task_id}/${params.model_name}"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else if (params.task_id != null) {
          val uri =
            "com.saturnmask.gallery://${params.task_id}/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else {
          val fallbackUri =
            "com.saturnmask.gallery://llm_agent_chat/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting fallback deeplink to: $fallbackUri")
          notificationProtoBuilder.setDeeplink(fallbackUri)
        }
        if (params.year != null) {
          notificationProtoBuilder.setYear(params.year)
        }
        if (params.month != null) {
          notificationProtoBuilder.setMonth(params.month)
        }
        if (params.day != null) {
          notificationProtoBuilder.setDay(params.day)
        }
        if (params.repeat_daily != null) {
          notificationProtoBuilder.setRepeatDaily(params.repeat_daily)
        }

        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val success =
          entryPoint
            .notificationScheduleManager()
            .scheduleNotification(notificationProtoBuilder.build())
        if (!success) {
          return "failed"
        }
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters", e)
      return "failed"
    }
  }
}
