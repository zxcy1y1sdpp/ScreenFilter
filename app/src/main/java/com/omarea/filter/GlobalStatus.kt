package com.omarea.filter

object GlobalStatus {
    var filterEnabled = false

    var currentLux: Float = 0F
    var currentFilterBrightness: Int = 0
    var currentFilterAlpah: Int = 0
    var sampleData: SampleData? = null

    var filterOpen: Runnable? = null
    var filterClose: Runnable? = null
    var filterRefresh: Runnable? = null
    var screenCap: Runnable? = null
}
