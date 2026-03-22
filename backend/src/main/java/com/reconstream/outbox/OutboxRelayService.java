package com.reconstream.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    public static final String TOPIC = "payment.events";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;

    public OutboxRelayService(OutboxEventRepository outboxEventRepository,
                              KafkaTemplate<String, String> kafkaTemplate,
                              MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = meterRegistry.counter("reconstream.outbox.published.total");
    }

    @Scheduled(fixedDelay = 500)
    public void scheduledRelay() {
        relayPendingEvents();
    }

    @Transactional
    public int relayPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEventsForUpdate();
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);

                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);

                publishedCounter.increment();
                publishedCount++;
                log.debug("Published outbox event {} for aggregate {}", event.getId(), event.getAggregateId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} to Kafka topic {}", event.getId(), TOPIC, e);
            }
        }
        return publishedCount;
    }
}
