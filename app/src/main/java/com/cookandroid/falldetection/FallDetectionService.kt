package com.cookandroid.falldetection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class FallDetectionService : Service(), SensorEventListener {

    // === 센서 관리 ===
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var isMonitoring = false
    private var currentSamplingRate = SensorManager.SENSOR_DELAY_NORMAL

    // === 센서 실패 관리 ===
    private var sensorFailureCount = 0
    private val MAX_SENSOR_FAILURES = 5
    private lateinit var sensorRestartHandler: Handler

    // === 배터리 관리 ===
    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    // === 낙상 감지 임계값 ===
    private val FREE_FALL_THRESHOLD = 0.65f // g
    private val IMPACT_THRESHOLD = 3.0f // g
    private val GYRO_THRESHOLD = 35.0f // degrees/second
    private val TIME_THRESHOLD_MS = 500L // ms
    private val POST_IMPACT_DURATION = 3000L // ms
    private val POST_IMPACT_STILLNESS_THRESHOLD = 1.1f // g

    // === 낙상 상태 추적 ===
    private var lastFreeFallTime = 0L
    private var isFreeFallDetected = false
    private var postImpactStartTime = 0L
    private var isPostImpactMonitoring = false
    private var postImpactHandler = Handler(Looper.getMainLooper())
    private var stillnessReadings = mutableListOf<Float>()

    // === 자이로스코프 데이터 ===
    private var lastGyroMagnitude = 0f
    private var gyroReadings = mutableListOf<Float>()

    // === 알림 관련 ===
    private val NOTIFICATION_CHANNEL_ID = "FallDetectionChannel"
    private val NOTIFICATION_ID = 1
    private val PERMISSION_NOTIFICATION_ID = 2

    override fun onCreate() {
        super.onCreate()
        Log.d("FallDetectionService", "onCreate 호출됨")

        initializeManagers()
        initializeSensors()
        createNotificationChannel()
        sensorRestartHandler = Handler(Looper.getMainLooper())
    }

    private fun initializeManagers() {
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private fun initializeSensors() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) // 가속도
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) // 자이로스코프

        if (accelerometer == null) {
            Log.e("FallDetectionService", "가속도 센서를 찾을 수 없습니다.")
        }
        if (gyroscope == null) {
            Log.w("FallDetectionService", "자이로스코프 센서를 찾을 수 없습니다.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FallDetectionService", "onStartCommand 호출됨")

        // 필수 권한 확인
        if (!checkRequiredPermissions()) {
            Log.e("FallDetectionService", "필수 권한이 부여되지 않음")
            sendPermissionRequiredNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        // 포그라운드 서비스 시작
        startForegroundService()

        // 배터리 최적화 확인
        checkBatteryOptimization()

        // 센서 모니터링 시작
        startSensorMonitoring()

        // Wake Lock 획득
        acquireWakeLock()

        return START_STICKY
    }

    private fun checkRequiredPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d("FallDetectionService", "포그라운드 서비스 시작됨")
    }


    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.w("FallDetectionService", "배터리 최적화 예외 설정이 권장됩니다.")
                sendBatteryOptimizationNotification()
            }
        }
    }

    private fun startSensorMonitoring() {
        if (isMonitoring) {
            Log.w("FallDetectionService", "이미 센서 모니터링 중입니다.")
            return
        }

        if (accelerometer == null) {
            Log.e("FallDetectionService", "가속도 센서 없음 - 서비스 중지")
            stopSelf()
            return
        }

        val samplingRate = getOptimalSamplingRate()
        currentSamplingRate = samplingRate

        // 가속도 센서 등록
        val accelRegistered = sensorManager.registerListener(
            this, accelerometer, samplingRate
        )

        // 자이로스코프 센서 등록 (있는 경우)
        var gyroRegistered = true
        gyroscope?.let {
            gyroRegistered = sensorManager.registerListener(this, it, samplingRate)
        }

        if (accelRegistered) {
            isMonitoring = true
            sensorFailureCount = 0
            Log.d("FallDetectionService", "센서 모니터링 시작됨 (샘플링 주기: $samplingRate)")

            // 주기적으로 배터리 상태 확인 및 샘플링 주기 조정
            startBatteryMonitoring()
        } else {
            Log.e("FallDetectionService", "센서 등록 실패")
            stopSelf()
        }
    }

    private fun getOptimalSamplingRate(): Int {
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = isDeviceCharging()

        return when {
            // isCharging -> SensorManager.SENSOR_DELAY_GAME // 충전 중이면 고성능
            batteryLevel > 20 -> SensorManager.SENSOR_DELAY_GAME //
            else -> {
                Log.w("FallDetectionService", "배터리 부족 - 절전 모드")
                SensorManager.SENSOR_DELAY_NORMAL // 기능 유지하되 절전
            }
        }
    }

    private fun isDeviceCharging(): Boolean {
        val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun startBatteryMonitoring() {
        sensorRestartHandler.postDelayed({
            if (isMonitoring) {
                adjustSamplingBasedOnBattery()
                startBatteryMonitoring() // 재귀 호출로 주기적 체크
            }
        }, 30000) // 30초마다 체크
    }

    private fun adjustSamplingBasedOnBattery() {
        val newSamplingRate = getOptimalSamplingRate()

        if (newSamplingRate != currentSamplingRate) {
            Log.d("FallDetectionService", "샘플링 주기 변경: $currentSamplingRate -> $newSamplingRate")

            // 센서 재등록
            sensorManager.unregisterListener(this)
            sensorManager.registerListener(this, accelerometer, newSamplingRate)
            gyroscope?.let {
                sensorManager.registerListener(this, it, newSamplingRate)
            }

            currentSamplingRate = newSamplingRate
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FallDetection::Service"
        ).apply {
            acquire(10 * 60 * 1000L) // 10분 제한
        }

        Log.d("FallDetectionService", "WakeLock 획득됨")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        try {
            if (event == null) {
                handleSensorFailure()
                return
            }

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    processAccelerometerData(event.values)
                }

                Sensor.TYPE_GYROSCOPE -> {
                    processGyroscopeData(event.values)
                }
            }

            // 성공적으로 처리되면 실패 카운터 리셋
            sensorFailureCount = 0

        } catch (e: Exception) {
            handleSensorFailure()
            Log.e("FallDetectionService", "센서 데이터 처리 오류", e)
        }
    }

    private fun processAccelerometerData(values: FloatArray) {
        val x = values[0]
        val y = values[1]
        val z = values[2]

        val accelerationMagnitude = sqrt((x * x + y * y + z * z).toDouble())
        val accelerationG = (accelerationMagnitude / SensorManager.GRAVITY_EARTH).toFloat()

        // 포스트 임팩트 모니터링 중이면 정적 상태 확인
        if (isPostImpactMonitoring) {
            stillnessReadings.add(accelerationG)
            if (stillnessReadings.size > 20) { // 최근 20개 값만 유지
                stillnessReadings.removeAt(0)
            }
        }

        detectFall(accelerationG)
    }

    private fun processGyroscopeData(values: FloatArray) {
        val x = values[0]
        val y = values[1]
        val z = values[2]

        val gyroMagnitude = sqrt((x * x + y * y + z * z).toDouble())
        // 라디안/초를 도/초로 변환
        lastGyroMagnitude = Math.toDegrees(gyroMagnitude).toFloat()

        gyroReadings.add(lastGyroMagnitude)
        if (gyroReadings.size > 10) { // 최근 10개 값만 유지
            gyroReadings.removeAt(0)
        }
    }

    private fun detectFall(accelerationG: Float) {
        val currentTime = System.currentTimeMillis()

        // 1단계: 자유낙하 감지
        if (accelerationG < FREE_FALL_THRESHOLD) {
            if (!isFreeFallDetected) {
                isFreeFallDetected = true
                lastFreeFallTime = currentTime
                Log.i("FallDetectionService", "자유낙하 시작: ${String.format("%.2f", accelerationG)}g")
            }
        } else {
            // 2단계: 충격 감지
            if (isFreeFallDetected && accelerationG > IMPACT_THRESHOLD) {
                val timeDiff = currentTime - lastFreeFallTime

                if (timeDiff <= TIME_THRESHOLD_MS) {
                    // 3단계: 자이로스코프 확인 (있는 경우)
                    val gyroCondition = if (gyroscope != null) {
                        val avgGyro = gyroReadings.average().toFloat()
                        avgGyro > GYRO_THRESHOLD
                    } else {
                        true // 자이로스코프 없으면 통과
                    }

                    if (gyroCondition) {
                        Log.w(
                            "FallDetectionService",
                            "낙상 의심 감지! (시간차: ${timeDiff}ms, 자이로: ${lastGyroMagnitude}°/s)"
                        )
                        startPostImpactVerification()
                    } else {
                        Log.d("FallDetectionService", "자이로스코프 조건 미충족으로 무시")
                    }
                } else {
                    Log.d("FallDetectionService", "시간 초과로 무시: ${timeDiff}ms")
                }

                isFreeFallDetected = false
            } else if (currentTime - lastFreeFallTime > TIME_THRESHOLD_MS) {
                isFreeFallDetected = false
            }
        }
    }

    private fun startPostImpactVerification() {
        postImpactStartTime = System.currentTimeMillis()
        isPostImpactMonitoring = true
        stillnessReadings.clear()

        Log.i("FallDetectionService", "포스트 임팩트 검증 시작")

        // 3초 후 최종 확인
        postImpactHandler.postDelayed({
            if (isPostImpactMonitoring) {
                confirmFallDetection()
            }
        }, POST_IMPACT_DURATION)
    }

    private fun confirmFallDetection() {
        isPostImpactMonitoring = false

        // 정적 상태 분석
        if (stillnessReadings.isNotEmpty()) {
            val avgStillness = stillnessReadings.average().toFloat()
            val maxStillness = stillnessReadings.maxOrNull() ?: 0f

            // 대부분의 시간 동안 정적 상태였는지 확인
            val stillnessCount = stillnessReadings.count { it < POST_IMPACT_STILLNESS_THRESHOLD }
            val stillnessRatio = stillnessCount.toFloat() / stillnessReadings.size

            Log.d(
                "FallDetectionService", "정적 상태 분석 - 평균: ${String.format("%.2f", avgStillness)}g, " +
                        "최대: ${String.format("%.2f", maxStillness)}g, 비율: ${
                            String.format(
                                "%.2f",
                                stillnessRatio
                            )
                        }"
            )

            if (stillnessRatio > 0.7f) { // 70% 이상 정적 상태
                Log.w("FallDetectionService", "*** 낙상 최종 확정! ***")
                handleFallDetected()
            } else {
                Log.i("FallDetectionService", "활동 지속으로 오탐지 판정")
            }
        } else {
            Log.w("FallDetectionService", "데이터 부족으로 낙상 의심 처리")
            handleFallDetected() // 데이터 없으면 안전을 위해 확정
        }

        stillnessReadings.clear()
    }

    private fun handleFallDetected() {
        Log.i("FallDetectionService", "낙상 확정 - FallConfirmationActivity 시작")

        val confirmationIntent = Intent(this, FallConfirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(confirmationIntent)
    }

    private fun handleSensorFailure() {
        sensorFailureCount++
        Log.w("FallDetectionService", "센서 실패 횟수: $sensorFailureCount")

        if (sensorFailureCount >= MAX_SENSOR_FAILURES) {
            Log.e("FallDetectionService", "센서 실패 한계 초과 - 재시작 시도")
            restartSensorMonitoring()
        }
    }

    private fun restartSensorMonitoring() {
        stopSensorMonitoring()

        // 1초 후 재시작 시도
        sensorRestartHandler.postDelayed({
            if (!isMonitoring) {
                Log.i("FallDetectionService", "센서 모니터링 재시작 시도")
                startSensorMonitoring()
            }
        }, 1000)
    }

    private fun stopSensorMonitoring() {
        if (!isMonitoring) return

        try {
            sensorManager.unregisterListener(this)
            isMonitoring = false
            Log.d("FallDetectionService", "센서 모니터링 중지됨")
        } catch (e: Exception) {
            Log.e("FallDetectionService", "센서 리스너 해제 중 오류", e)
        }
    }

    // 알림 관련 메서드들
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "낙상 감지 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 낙상 감지를 실행합니다."
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)

            Log.d("FallDetectionService", "알림 채널 생성됨")
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = isDeviceCharging()

        val contentText = if (isCharging) {
            "낙상 감지 활성화 - 충전 중 (배터리: ${batteryLevel}%)"
        } else {
            "낙상 감지 활성화 - 배터리: ${batteryLevel}%"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("낙상 감지 서비스")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_fall_detection) // ← 여기에 적용
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }


    private fun sendPermissionRequiredNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("request_permissions", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("권한 필요")
            .setContentText("낙상 감지 서비스를 위해 권한이 필요합니다.")
            .setSmallIcon(R.drawable.ic_fall_detection) // ← 여기에 적용
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(PERMISSION_NOTIFICATION_ID, notification)
    }


    private fun sendBatteryOptimizationNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("배터리 최적화 설정 권장")
            .setContentText("더 안정적인 낙상 감지를 위해 배터리 최적화 예외 설정을 권장합니다.")
            .setSmallIcon(R.drawable.ic_fall_detection) // ← 여기에 적용
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(PERMISSION_NOTIFICATION_ID + 1, notification)
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("FallDetectionService", "센서 정확도 변경: ${sensor?.name}, 정확도: $accuracy")

        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w("FallDetectionService", "센서 정확도 낮음 - 재보정 필요할 수 있음")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FallDetectionService", "onDestroy 호출됨")

        // 센서 모니터링 중지
        stopSensorMonitoring()

        // 핸들러 정리
        sensorRestartHandler.removeCallbacksAndMessages(null)
        postImpactHandler.removeCallbacksAndMessages(null)

        // Wake Lock 해제
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d("FallDetectionService", "WakeLock 해제됨")
            }
        }

        // 포그라운드 서비스 중지
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        Log.d("FallDetectionService", "서비스 완전 종료됨")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
