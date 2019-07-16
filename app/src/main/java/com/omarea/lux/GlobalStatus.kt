package com.omarea.lux

object GlobalStatus {
    var filterEnabled = false

    var currentLux: Float = 0F
    var currentFilterBrightness: Float = 0F
    var sampleData: SampleData? = null

    var filterOpen: Runnable? = null
    var filterClose: Runnable? = null
    var filterRefresh: Runnable? = null
}
