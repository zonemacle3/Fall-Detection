package com.cookandroid.falldetection

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFS_NAME = "FallDetectionPrefs"
    private const val KEY_FALL_DETECTION_ENABLED = "fall_detection_enabled"

    private fun getPreferences(context: Context): SharedPreferences {
        // 앱 전용 비공개 SharedPreferences 인스턴스 가져오기
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setFallDetectionEnabled(context: Context, isEnabled: Boolean) {
        val editor = getPreferences(context).edit()
        editor.putBoolean(KEY_FALL_DETECTION_ENABLED, isEnabled)
        editor.apply() // 비동기 저장
    }

    fun isFallDetectionEnabled(context: Context): Boolean {
        // 기본값은 false (꺼짐)
        return getPreferences(context).getBoolean(KEY_FALL_DETECTION_ENABLED, false)
    }
}