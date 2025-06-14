package com.cookandroid.falldetection

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.CompoundButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchFallDetection: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        switchFallDetection = findViewById(R.id.switchFallDetection)

        // 1. 저장된 설정 값 불러와서 스위치 초기 상태 설정
        val isEnabled = SettingsManager.isFallDetectionEnabled(this)
        switchFallDetection.isChecked = isEnabled

        // 2. 스위치 상태 변경 리스너 설정
        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            // 3. 변경된 설정 값 저장
            SettingsManager.setFallDetectionEnabled(this, isChecked)

            // 4. 설정 값에 따라 낙상 감지 서비스 시작 또는 중지
            if (isChecked) {
                startFallDetectionService()
            } else {
                stopFallDetectionService()
            }
        }
    }

    // 낙상 감지 서비스 시작 함수
    private fun startFallDetectionService() {
        //  FallDetectionService 시작 로직 구현 (Intent 사용)
        Log.i("SettingsActivity", "낙상 감지 서비스 시작 시도")
        val serviceIntent = Intent(this, FallDetectionService::class.java)
        // Android 8.0 이상에서는 포그라운드 서비스를 시작해야 함
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // 낙상 감지 서비스 중지 함수 (
    private fun stopFallDetectionService() {
        // FallDetectionService 중지 로직 구현 (Intent 사용)
        Log.i("SettingsActivity", "낙상 감지 서비스 중지 시도")
        val serviceIntent = Intent(this, FallDetectionService::class.java)
        stopService(serviceIntent)
    }
}