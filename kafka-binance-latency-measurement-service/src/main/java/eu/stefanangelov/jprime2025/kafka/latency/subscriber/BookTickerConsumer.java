package eu.stefanangelov.jprime2025.kafka.latency.subscriber;

import eu.stefanangelov.jprime2025.kafka.websocket.model.BookTicker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class BookTickerConsumer {

    private final MeterRegistry meterRegistry;
    private final Timer latencyTimer;

    public BookTickerConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Initialize latency histogram
        this.latencyTimer = Timer.builder("kafka.latency")
                .description("Latency of messages received via Kafka")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @KafkaListener(topics = "book-ticker", groupId = "latency-measurement-group")
    public void consume(BookTicker bookTicker) {
        try {
            meterRegistry.counter("kafka.messages.received").increment();
            long now = System.nanoTime();
            long latencyNs = now - bookTicker.getTimestamp();
            double latencyMs = latencyNs / 1_000_000.0;
            // Record latency in milliseconds
            latencyTimer.record((Duration.ofNanos(latencyNs)));
            log.info("Received book ticker: {}, Latency: {} ms", bookTicker.getSymbol(), latencyMs);
        } catch (Exception e) {
            meterRegistry.counter("kafka.errors").increment();
            log.error("Error processing message", e);
        }
    }
}
