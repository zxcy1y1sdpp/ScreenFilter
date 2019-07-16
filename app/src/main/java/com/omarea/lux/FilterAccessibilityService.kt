package com.omarea.lux

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.*
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import com.omarea.lux.light.LightHandler
import com.omarea.lux.light.LightHistory
import com.omarea.lux.light.LightSensorWatcher
import java.util.*


class FilterAccessibilityService : AccessibilityService() {
    private lateinit var config: SharedPreferences
    private var dynamicOptimize: DynamicOptimize? = null
    private var lightSensorWatcher: LightSensorWatcher? = null
    private var handler = Handler()
    private var isLandscapf = false
    private val lightHistory = Stack<LightHistory>()

    private var filterBrightness = 0F // 当前由程序控制的屏幕亮度

    private lateinit var mWindowManager: WindowManager
    private lateinit var display: Display

    // 当前手机屏幕是否处于开启状态
    private var screenOn = false;
    private var reciverLock: ReciverLock? = null

    // 悬浮窗
    private var popupView: View? = null
    // 计算平滑亮度的定时器
    private var smoothLightTimer: Timer? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            screenOn = ScreenState(this).isScreenOn()
        }
        if (reciverLock == null) {
            reciverLock = ReciverLock.autoRegister(this, ScreenEventHandler({
                screenOn = false
                if (dynamicOptimize != null) {
                    dynamicOptimize!!.registerListener()
                }
            }, {
                screenOn = true
                if (GlobalStatus.filterEnabled) {
                    if (config.getBoolean(SpfConfig.DYNAMIC_OPTIMIZE, SpfConfig.DYNAMIC_OPTIMIZE_DEFAULT)) {
                        if (dynamicOptimize != null) {
                            dynamicOptimize = DynamicOptimize(this)
                        }
                        dynamicOptimize!!.registerListener()
                    }
                    if (lightHistory.size > 0) {
                        val last = lightHistory.last()
                        lightHistory.clear()
                        updateFilterNow(last.lux)
                    }
                }
            }))
        }

        if (GlobalStatus.sampleData == null) {
            GlobalStatus.sampleData = SampleData(applicationContext)
        }

        config = getSharedPreferences(SpfConfig.FILTER_SPF, Context.MODE_PRIVATE)
        GlobalStatus.filterOpen = Runnable {
            filterOpen()
        }
        GlobalStatus.filterClose = Runnable {
            filterClose()
        }

        if (config.getBoolean(SpfConfig.FILTER_AUTO_START, SpfConfig.FILTER_AUTO_START_DEFAULT)) {
            filterOpen()
        }
        super.onServiceConnected()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (reciverLock != null) {
            ReciverLock.unRegister(this)
            reciverLock = null
        }

        GlobalStatus.filterOpen = null
        GlobalStatus.filterClose = null
        GlobalStatus.filterRefresh = null
        filterClose()

        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
    }

    private fun filterClose() {
        try {
            if (valueAnimator != null && valueAnimator!!.isRunning) {
                valueAnimator!!.cancel()
                valueAnimator = null
            }
            currentAlpha = 0

            if (popupView != null) {
                val mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                mWindowManager.removeView(popupView)
                popupView = null
            }

            lightSensorWatcher?.stopSystemConfigWatcher()
            dynamicOptimize?.unregisterListener()

            GlobalStatus.filterRefresh = null
            GlobalStatus.filterEnabled = false
            lightHistory.empty()
            stopSmoothLightTimer()
            filterBrightness = -1F
        } catch (ex: Exception) {
        }
    }

    /**
     * 系统亮度改变时触发
     */
    private fun brightnessChangeHandle(brightness: Int) {
        // 系统最大亮度值
        var maxLight = config.getInt(SpfConfig.SCREENT_MAX_LIGHT, SpfConfig.SCREENT_MAX_LIGHT_DEFAULT)
        // 部分设备最大亮度不符合谷歌规定的1-255，会出现2047 4096等超大数值，因此要自适应一下
        if (brightness > maxLight) {
            config.edit().putInt(SpfConfig.SCREENT_MAX_LIGHT, brightness).apply()
            maxLight = brightness
        }

        updateFilterNow((brightness.toFloat() / maxLight) * 1000F, false)
    }

    /**
     * 周围光线发生变化时触发
     */
    private fun luxChangeHandle(currentLux: Float) {
        if (config.getBoolean(SpfConfig.SMOOTH_ADJUSTMENT, SpfConfig.SMOOTH_ADJUSTMENT_DEFAULT)) {
            val history = LightHistory()
            history.run {
                time = System.currentTimeMillis()
                lux = currentLux
            }

            if (lightHistory.size > 100) {
                lightHistory.pop()
            }

            lightHistory.push(history)

            startSmoothLightTimer()
        } else {
            stopSmoothLightTimer()
            updateFilterNow(currentLux)
        }
    }

    private fun filterOpen() {
        if (popupView != null) {
            filterClose()
        }

        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        display = mWindowManager.getDefaultDisplay()

        val params = WindowManager.LayoutParams()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        params.gravity = Gravity.NO_GRAVITY
        params.format = PixelFormat.TRANSLUCENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (config.getBoolean(SpfConfig.HARDWARE_ACCELERATED, SpfConfig.HARDWARE_ACCELERATED_DEFAULT)) {
            params.flags = params.flags.or(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        }

        params.width = WRAP_CONTENT
        params.height = WRAP_CONTENT
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // BRIGHTNESS_OVERRIDE_FULL

        popupView = LayoutInflater.from(this).inflate(R.layout.filter, null)
        mWindowManager.addView(popupView, params)

        if (lightSensorWatcher == null) {
            lightSensorWatcher = LightSensorWatcher(this, object : LightHandler {
                override fun onModeChange(auto: Boolean) {
                    if (!auto) {
                        stopSmoothLightTimer()
                    }
                }

                override fun onBrightnessChange(brightness: Int) {
                    brightnessChangeHandle(brightness)
                }

                override fun onLuxChange(currentLux: Float) {
                    luxChangeHandle(currentLux)
                }
            })
        }
        lightSensorWatcher?.startSystemConfigWatcher()

        if (config.getBoolean(SpfConfig.DYNAMIC_OPTIMIZE, SpfConfig.DYNAMIC_OPTIMIZE_DEFAULT)) {
            if (dynamicOptimize == null) {
                dynamicOptimize = DynamicOptimize(this)
            }
            dynamicOptimize!!.registerListener()
        }

        GlobalStatus.filterRefresh = Runnable {
            updateFilterNow(GlobalStatus.currentLux)
        }

        GlobalStatus.filterEnabled = true
    }

    /**
     * 屏幕配置改变（旋转、分辨率更改、DPI更改等）
     */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (newConfig != null) {
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                isLandscapf = false
            } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                isLandscapf = true
            }
        }
    }

    /**
     * 启动平滑亮度定时任务
     */
    private fun startSmoothLightTimer() {
        if (smoothLightTimer == null) {
            smoothLightTimer = Timer()
            smoothLightTimer!!.schedule(object : TimerTask() {
                override fun run() {
                    handler.post {
                        smoothLightTimerTick()
                    }
                }
            }, 200, 5000)
        }
    }

    private fun smoothLightTimerTick() {
        try {
            if (popupView != null) {
                val currentTime = System.currentTimeMillis()
                val historys = lightHistory.filter {
                    (currentTime - it.time) < 11000
                }
                if (historys.size > 0) {
                    var total: Double = 0.toDouble()
                    for (history in historys) {
                        total += history.lux
                    }
                    val avg = (total / historys.size).toFloat()
                    updateFilterNow(avg)
                } else if (lightHistory.size > 0) {
                    updateFilterNow(lightHistory.last().lux)
                }
            }
        } catch (ex: Exception) {
        }
    }

    private fun stopSmoothLightTimer() {
        if (smoothLightTimer != null) {
            smoothLightTimer!!.cancel()
            smoothLightTimer = null
        }
    }

    private fun updateFilterNow(lux: Float, smoothness: Boolean = true) {
        var optimizedLux = lux

        // 亮度微调
        var staticOffset = config.getInt(SpfConfig.BRIGTHNESS_OFFSET, SpfConfig.BRIGTHNESS_OFFSET_DEFAULT) / 100.0F

        // 横屏
        if (isLandscapf) {
            staticOffset += 0.1F
        }

        var offsetPractical = 0.toDouble()
        // 场景优化
        if (dynamicOptimize != null && config.getBoolean(SpfConfig.DYNAMIC_OPTIMIZE, SpfConfig.DYNAMIC_OPTIMIZE_DEFAULT)) {
            optimizedLux += dynamicOptimize!!.luxOptimization(lux)
            offsetPractical += dynamicOptimize!!.brightnessOptimization(
                    config.getFloat(SpfConfig.DYNAMIC_OPTIMIZE_SENSITIVITY, SpfConfig.DYNAMIC_OPTIMIZE_SENSITIVITY_DEFAULT),
                    lux)
        }
        val sample = GlobalStatus.sampleData!!.getVitualSample(optimizedLux)
        sample?.run {
            val totalRatio = (sample / 1000F) + staticOffset
            popupView?.run {
                updateFilterByRatio( (totalRatio * (1 + offsetPractical)).toFloat(), smoothness && popupView!!.isHardwareAccelerated)
            }
        }
    }

    private var valueAnimator: ValueAnimator? = null
    private var currentAlpha: Int = 0
    private fun updateFilterByRatio(ratio: Float, smoothness: Boolean = true) {
        if (!screenOn) {
            if (config.getBoolean(SpfConfig.SCREEN_OFF_PAUSE, SpfConfig.SCREEN_OFF_PAUSE_DEFAULT)) {
                return
            }
        }
        if (valueAnimator != null && valueAnimator!!.isRunning) {
            valueAnimator!!.cancel()
            valueAnimator = null
        }

        val layoutParams = popupView!!.layoutParams as WindowManager.LayoutParams

        // 首次刷新 - 立即调整亮度，而不是延迟执行
        if (!smoothness || layoutParams.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            layoutParams.screenBrightness = ratio
            mWindowManager.updateViewLayout(popupView, layoutParams)
            filterBrightness = ratio
        } else {
            valueAnimator = ValueAnimator.ofFloat(layoutParams.screenBrightness, ratio)
            valueAnimator!!.run {
                duration = 2000
                addUpdateListener { animation ->
                    popupView?.run {
                        layoutParams.screenBrightness = animation.animatedValue as Float
                        mWindowManager.updateViewLayout(popupView, layoutParams)
                    }
                    filterBrightness = ratio
                }
                start()
            }
            // layoutParams.screenBrightness = ratio
            // mWindowManager.updateViewLayout(popupView, layoutParams)
            // filterBrightness = ratio
        }

        GlobalStatus.currentFilterBrightness = filterBrightness
    }

}
