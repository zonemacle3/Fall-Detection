package com.cookandroid.falldetection

import android.app.* // Notification 관련 import 추가
import android.content.Context // 추가
import android.content.Intent
import android.hardware.Sensor // 추가
import android.hardware.SensorEvent // 추가
import android.hardware.SensorEventListener // 추가
import android.hardware.SensorManager // 추가
import android.os.Build // Build 버전 체크 추가
import android.os.IBinder
import android.util.Log // 추가
import androidx.core.app.NotificationCompat // NotificationCompat 추가
import androidx.core.app.ServiceCompat.startForeground
import androidx.core.app.ServiceCompat.stopForeground
import androidx.core.content.ContextCompat.getSystemService
import kotlin.math.sqrt // 추가

class FallDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // --- 임계값 설정 (예시 - 실제 값은 테스트를 통해 조정 필요) ---
    private val FREE_FALL_THRESHOLD = 0.65f // g (중력가속도) 미만이면 자유낙하 간주
    private val IMPACT_THRESHOLD = 3.0f    // g 초과면 충격 간주_
    private val TIME_THRESHOLD_MS = 500L  // 자유낙하와 충격 사이의 최대 시간 (밀리초)
    private var lastFreeFallTime = 0L
    private var isFreeFallDetected = false
    // ----------------------------------------------------------

    // --- Notification 관련 상수 ---
    private val NOTIFICATION_CHANNEL_ID = "FallDetectionChannel"
    private val NOTIFICATION_ID = 1 // 포그라운드 서비스 알림 ID
    // ---------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d("FallDetectionService", "onCreate 호출됨")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Android 8.0 이상 알림 채널 생성
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FallDetectionService", "onStartCommand 호출됨")

        // --- Foreground Service 시작 로직 ---
        val notification = createNotification()
        // startForegroundService() 호출 후 5초 이내에 startForeground()를 호출해야 함
        startForeground(NOTIFICATION_ID, notification)
        Log.d("FallDetectionService", "startForeground 호출됨")
        // ---------------------------------

        startSensorMonitoring() // 센서 모니터링 시작

        // 서비스가 시스템에 의해 종료될 경우 자동으로 재시작하도록 설정
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FallDetectionService", "onDestroy 호출됨")
        stopSensorMonitoring() // 센서 모니터링 중지
        // 포그라운드 서비스 종료 시 알림 제거 (선택적, false는 알림 유지, true는 알림 제거)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE) // API 24 이상
        } else {
            // API 24 미만에서는 stopForeground(true) 사용 불가, 필요시 NotificationManager로 직접 제거
            stopForeground(true) // 이 버전에서는 알림 제거 의미
        }
        Log.d("FallDetectionService", "포그라운드 서비스 종료됨")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Binding을 사용하지 않으므로 null 반환
        return null
    }

    // --- 알림 채널 생성 (Android 8.0 이상) ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "낙상 감지 서비스 채널", // 사용자 설정에 표시될 채널 이름
                NotificationManager.IMPORTANCE_DEFAULT // 중요도 (소리/진동 없이 조용한 알림 등 설정 가능)
            ).apply {
                description = "백그라운드에서 낙상 감지를 실행합니다." // 채널 설명 (선택적)
                // 채널에 대한 추가 설정 가능 (예: setSound, enableVibration 등)
            }

            val manager = getSystemService(NotificationManager::class.java)
            if (manager == null) {
                Log.e("FallDetectionService", "NotificationManager 가져오기 실패")
                return
            }
            manager.createNotificationChannel(serviceChannel)
            Log.d("FallDetectionService", "알림 채널 생성됨: $NOTIFICATION_CHANNEL_ID")
        }
    }
    // ----------------------------------------

    // --- 포그라운드 서비스 알림 생성 ---
    private fun createNotification(): Notification {
        // 알림 클릭 시 MainActivity를 열도록 Intent 설정
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            // 필요시 플래그 추가 (예: 앱이 이미 실행 중일 때 새 인스턴스 대신 기존 인스턴스 가져오기)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        // PendingIntent 생성 (Android 12 이상에서는 FLAG_IMMUTABLE 또는 FLAG_MUTABLE 명시 필요)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, // requestCode (여러 PendingIntent 구분 시 사용)
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // 변경 불가능한 PendingIntent
        )

        // 알림 빌더를 사용하여 알림 객체 생성
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("낙상 감지 서비스") // 알림 제목
            .setContentText("낙상 감지 기능이 활성화되어 백그라운드에서 실행 중입니다.") // 알림 내용
            .setContentIntent(pendingIntent) // 알림 클릭 시 실행할 Intent
            .setOngoing(true) // 사용자가 알림을 쉽게 지울 수 없도록 설정 (선택적)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 알림 중요도
            .build() // Notification 객체 생성

        Log.d("FallDetectionService", "포그라운드 서비스 알림 생성됨")
        return notification
    }
    // -----------------------------------

    // --- 센서 모니터링 시작 ---
    private fun startSensorMonitoring() {
        if (accelerometer == null) {
            Log.w("FallDetectionService", "가속도 센서를 찾을 수 없습니다.")
            // 사용자에게 알림 또는 서비스 중지 등의 처리 필요
            stopSelf() // 센서 없으면 서비스 중지
            return
        }
        // 센서 리스너 등록 (SENSOR_DELAY_NORMAL은 일반적인 속도, 더 민감하게 하려면 SENSOR_DELAY_GAME, SENSOR_DELAY_FASTEST 사용 가능하나 배터리 소모 증가)
        val registered = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        if (registered) {
            Log.d("FallDetectionService", "가속도 센서 리스너 등록 성공")
        } else {
            Log.e("FallDetectionService", "가속도 센서 리스너 등록 실패")
            stopSelf() // 등록 실패 시 서비스 중지
        }
    }
    // -------------------------

    // --- 센서 모니터링 중지 ---
    private fun stopSensorMonitoring() {
        try {
            sensorManager.unregisterListener(this)
            Log.d("FallDetectionService", "가속도 센서 리스너 해제됨")
        } catch (e: Exception) {
            Log.e("FallDetectionService", "센서 리스너 해제 중 오류 발생", e)
        }
    }
    // -------------------------

    // --- SensorEventListener 인터페이스 구현 ---
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 센서 정확도가 변경되었을 때 호출됨 (일반적으로 특별한 처리 불필요)
        Log.d("FallDetectionService", "센서 정확도 변경: ${sensor?.name}, 정확도: $accuracy")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // 센서 값이 변경될 때마다 호출됨
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // 중력 가속도를 포함한 전체 가속도 벡터 크기 계산 (m/s^2)
            val accelerationMagnitudeRaw = sqrt((x*x + y*y + z*z).toDouble())
            // g 단위로 변환 (1g = 약 9.8 m/s^2)
            val accelerationMagnitudeG = accelerationMagnitudeRaw / SensorManager.GRAVITY_EARTH

            // Log.v("FallDetectionService", "가속도 값: x=$x, y=$y, z=$z, 크기(g)=${String.format("%.2f", accelerationMagnitudeG)}") // 너무 자주 로깅되므로 필요 시에만 사용

            // --- 임계값 기반 낙상 감지 로직 ---
            val currentTime = System.currentTimeMillis()

            // 1. 자유 낙하 감지 (특정 g 값 미만)
            if (accelerationMagnitudeG < FREE_FALL_THRESHOLD) {
                if (!isFreeFallDetected) {
                    // 처음 자유 낙하 상태 진입
                    isFreeFallDetected = true
                    lastFreeFallTime = currentTime
                    Log.i("FallDetectionService", "자유 낙하 상태 시작됨: ${String.format("%.2f", accelerationMagnitudeG)}g")
                }
                // 자유 낙하 상태가 지속되는 동안 시간 갱신 (선택적)
                // lastFreeFallTime = currentTime
            } else { // 자유 낙하 상태가 아닐 때
                // 2. 충격 감지 (이전에 자유 낙하가 감지되었던 경우에만 확인)
                if (isFreeFallDetected) {
                    if (accelerationMagnitudeG > IMPACT_THRESHOLD) {
                        Log.i("FallDetectionService", "충격 감지됨: ${String.format("%.2f", accelerationMagnitudeG)}g")
                        // 3. 시간 임계값 확인 (자유 낙하 시작 후 너무 오래되지 않았는지)
                        if (currentTime - lastFreeFallTime <= TIME_THRESHOLD_MS) {
                            Log.w("FallDetectionService", "*** 낙상 감지됨! (시간차: ${currentTime - lastFreeFallTime}ms) ***")
                            // 낙상 후 처리 로직 호출
                            handleFallDetected()
                            // 낙상 처리 후에는 자유 낙하 상태 초기화
                            isFreeFallDetected = false
                        } else {
                            Log.d("FallDetectionService", "충격 감지되었으나 시간 초과: ${currentTime - lastFreeFallTime}ms")
                            // 시간 초과 시 자유 낙하 상태 초기화
                            isFreeFallDetected = false
                        }
                    } else {
                        // 자유 낙하 후 충격 임계값 미만의 가속도가 감지된 경우
                        // (예: 살짝 내려놓거나 정상 활동 복귀)
                        // 시간 임계값 지나면 초기화 (선택적 - 바로 초기화 할 수도 있음)
                        if (currentTime - lastFreeFallTime > TIME_THRESHOLD_MS) {
                            Log.d("FallDetectionService", "자유 낙하 후 시간 초과로 상태 초기화")
                            isFreeFallDetected = false
                        }
                    }
                }
                // else -> 자유 낙하 감지 안 된 상태에서 일반적인 움직임은 무시
            }
            // ---------------------------------
        }
    }
    // ------------------------------------------

    // --- 낙상 감지 후 처리 함수 ---
    private fun handleFallDetected() {
        Log.i("FallDetectionService", "handleFallDetected 호출됨")

        // FallConfirmationActivity 실행
        val confirmationIntent = Intent(this, FallConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(confirmationIntent)
    }
    // ---------------------------
}
