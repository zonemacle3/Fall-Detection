package com.cookandroid.falldetection

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.cardview.widget.CardView // CardView 임포트
import android.content.Intent

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 각 CardView ID를 사용하여 뷰 가져오기
        val cardEmergencyCall: CardView = findViewById(R.id.cardEmergencyCall)
        val cardTodayStatus: CardView = findViewById(R.id.cardTodayStatus)
        val cardNotificationHistory: CardView = findViewById(R.id.cardNotificationHistory)
        val cardActivityRecord: CardView = findViewById(R.id.cardActivityRecord)
        val cardSettings: CardView = findViewById(R.id.cardSettings)
        val cardGuardianContact: CardView = findViewById(R.id.cardGuardianContact)

        // 클릭 리스너 설정
        cardEmergencyCall.setOnClickListener {
            Toast.makeText(this, "긴급 호출 클릭됨", Toast.LENGTH_SHORT).show()
        }

        cardTodayStatus.setOnClickListener {
            Toast.makeText(this, "오늘 상태 클릭됨", Toast.LENGTH_SHORT).show()
        }

        cardNotificationHistory.setOnClickListener {
            Toast.makeText(this, "알림 기록 클릭됨", Toast.LENGTH_SHORT).show()
        }

        cardActivityRecord.setOnClickListener {
            Toast.makeText(this, "활동 기록 클릭됨", Toast.LENGTH_SHORT).show()
        }

        cardSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            Toast.makeText(this, "설정 클릭됨", Toast.LENGTH_SHORT).show()
        }

        cardGuardianContact.setOnClickListener {
            Toast.makeText(this, "보호자 연락처 클릭됨", Toast.LENGTH_SHORT).show()
        }
    }
}