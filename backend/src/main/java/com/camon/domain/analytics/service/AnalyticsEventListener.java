package com.camon.domain.analytics.service;

import com.camon.domain.analytics.domain.AnalyticsDomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AnalyticsEventListener {

    private final AnalyticsEventRecorder recorder;

    public AnalyticsEventListener(AnalyticsEventRecorder recorder) {
        this.recorder = recorder;
    }

    @EventListener
    public void onAnalyticsEvent(AnalyticsDomainEvent event) {
        try {
            recorder.record(event);
        } catch (RuntimeException exception) {
            log.error(
                "[Analytics] event persistence failed: eventId={}, eventName={}, roomId={}",
                event.eventId(),
                event.eventName(),
                event.roomId(),
                exception
            );
        }
    }
}
