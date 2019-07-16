package com.omarea.lux.light;

public interface LightHandler {
    void onLuxChange(float lux);

    void onBrightnessChange(int brightness);

    void onModeChange(boolean auto);
}
