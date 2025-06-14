package com.cookandroid.falldetection

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class FallConfirmationActivity : AppCompatActivity() {

    private lateinit var tvTimer: TextView
    private lateinit var btnImOkay: Button
    private var countDownTimer: CountDownTimer? = null
    private val COUNTDOWN_SECONDS = 15 // 예시: 15초 카운트다운

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fall_confirmation)

        Log.d("FallConfirmation", "onCreate 호출됨")

        tvTimer = findViewById(R.id.tvTimer)
        btnImOkay = findViewById(R.id.btnImOkay)

        btnImOkay.setOnClickListener {
            Log.d("FallConfirmation", "괜찮아요 버튼 클릭됨")
            countDownTimer?.cancel() // 타이머 취소
            // TODO: 필요한 경우, 서비스나 다른 곳에 '괜찮음' 상태 알리기
            Toast.makeText(this, "알림이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            finish() // 현재 액티비티 종료
        }

        startCountdown()
    }

    private fun startCountdown() {
        tvTimer.text = COUNTDOWN_SECONDS.toString()
        countDownTimer = object : CountDownTimer((COUNTDOWN_SECONDS * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000
                tvTimer.text = secondsRemaining.toString()
                Log.d("FallConfirmation", "카운트다운: $secondsRemaining 초 남음")
            }

            override fun onFinish() {
                tvTimer.text = "0"
                Log.w("FallConfirmation", "카운트다운 종료! 비상 연락 시작")
                // TODO: 카운트다운 종료 시 비상 연락처/119 신고 등 실제 동작 수행
                // 예: sendEmergencyAlert()
                Toast.makeText(applicationContext, "비상 연락을 시작합니다!", Toast.LENGTH_LONG).show()
                finish() // 현재 액티비티 종료 (또는 다른 화면으로 전환)
            }
        }.start()
    }

    // 액티비티가 파괴될 때 타이머가 남아있으면 취소
    override fun onDestroy() {
        super.onDestroy()
        Log.d("FallConfirmation", "onDestroy 호출됨")
        countDownTimer?.cancel()
    }

    // TODO: 실제 비상 연락 로직 구현
    // private fun sendEmergencyAlert() {
    //     // SMS, 전화 등
    // }
}