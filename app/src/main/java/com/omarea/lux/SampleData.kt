package com.omarea.lux

import android.content.Context
import android.util.Log
import com.omarea.shared.FileWrite
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * 样本数据
 */
class SampleData {
    // 样本数据（lux, Sample）
    private var samples = HashMap<Int, Int>()

    private var filterConfig = "Samples.json"

    constructor (context: Context) {
        this.readConfig(context)
    }

    public fun readConfig(context: Context, officialOnlay: Boolean = false) {
        try {
            samples.clear()
            val customConfig = FileWrite.getPrivateFilePath(context, filterConfig)
            val configFile = if ((!officialOnlay) && File(customConfig).exists()) File(customConfig).readBytes() else context.assets.open(filterConfig).readBytes()

            val jsonObject = JSONObject(String(configFile))
            val samples = jsonObject.getJSONObject("samples")
            for (item in samples.keys()) {
                val lux = item.toInt()
                val filterAlpha = samples.getInt(item)

                if (this.samples.containsKey(lux)) {
                    this.samples.remove(lux)
                }
                this.samples.put(lux, filterAlpha)
            }
        } catch (ex: Exception) {
            samples.put(0, 10)
            samples.put(10000, 1000)
        }
    }

    fun saveConfig(context: Context) {
        val sampleConfig = JSONObject()
        for (sample in samples) {
            sampleConfig.put(sample.key.toString(), sample.value)
        }
        val config = JSONObject()
        config.putOpt("samples", sampleConfig)
        val jsonStr = config.toString(2)

        if (!FileWrite.writePrivateFile(jsonStr.toByteArray(Charset.defaultCharset()), filterConfig, context)) {
            Log.e("ScreenFilter", "存储样本失败！！！")
        }
    }

    /**
     * 添加样本数据
     */
    public fun addSample(lux: Int, sample: Int) {
        // 查找现存样本中与新增样本冲突的旧样本
        val invalidSamples = samples.filter {
            (it.key >= lux && it.value <= sample) || (it.key <= lux && it.value >= sample)
        }
        invalidSamples.forEach {
            samples.remove(it.key)
        }

        if (!samples.containsKey(lux)) {
            samples.put(lux, sample)
        }
    }

    /**
     * 移除样本
     */
    public fun removeSample(lux: Int) {
        if (samples.containsKey(lux)) {
            samples.remove(lux)
        }
    }

    /**
     * 替换样本
     */
    public fun replaceSample(lux: Int, sample: Int) {
        removeSample(lux)
        addSample(lux, sample)
    }

    /**
     * 获取样本（）
     */
    public fun getSample(lux: Int): Int {
        if (samples.containsKey(lux)) {
            return samples.get(lux) as Int
        }
        return -1
    }

    public fun getVitualSample(lux: Int): Int? {
        return getVitualSample(lux.toFloat())
    }

    /**
     * 获取虚拟样本，根据已有样本计算数值
     */
    public fun getVitualSample(lux: Float): Int? {
        if (samples.size > 1) {
            var sampleValue = 0
            val intValue = lux.toInt()
            // 如果有现成的样本 直接获取样本值
            if (intValue.toFloat() == lux && samples.containsKey(intValue)) {
                sampleValue = samples.get(intValue) as Int
            } else {
                // 计算生成虚拟样本
                val keys = samples.keys.sorted()
                var rangeLeftLux = keys.first()
                var rangeRightLux = keys.last()

                if (lux < rangeLeftLux) {
                    return samples[rangeLeftLux]
                } else if (lux > rangeRightLux) {
                    return samples[rangeRightLux]
                } else {
                    for (sampleLux in keys) {
                        if (lux > sampleLux) {
                            rangeLeftLux = sampleLux
                        } else {
                            rangeRightLux = sampleLux
                            break
                        }
                    }
                    val rangeLeftBrightness = samples.get(rangeLeftLux)!!
                    val rangeRightBrightness = samples.get(rangeRightLux)!!
                    if (rangeLeftBrightness == rangeRightBrightness || rangeLeftLux == rangeRightLux) {
                        return rangeLeftBrightness
                    }
                    return rangeLeftBrightness + ((rangeRightBrightness - rangeLeftBrightness) * (lux - rangeLeftLux) / (rangeRightLux - rangeLeftLux)).toInt()
                }
            }
            return sampleValue
        }
        return null
    }

    /**
     * 获取所有样本
     */
    public fun getAllSamples(): HashMap<Int, Int> {
        return this.samples
    }
}