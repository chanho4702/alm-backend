package com.platform.almbackend.event;

import com.platform.proto.events.v1.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope event);
}

