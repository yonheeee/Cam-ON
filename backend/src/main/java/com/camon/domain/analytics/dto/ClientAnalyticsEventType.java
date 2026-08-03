package com.camon.domain.analytics.dto;

import com.camon.domain.analytics.domain.AnalyticsEventName;

public enum ClientAnalyticsEventType {
    ROOM_ENTERED,
    CAMERA_PERMISSION_RESULT,
    RECOGNITION_TEST_RESULT,
    RESULT_SCREEN_VIEWED,
    NINJA_RECOGNITION_WINDOW;

    public AnalyticsEventName toEventName() {
        return AnalyticsEventName.valueOf(name());
    }
}
