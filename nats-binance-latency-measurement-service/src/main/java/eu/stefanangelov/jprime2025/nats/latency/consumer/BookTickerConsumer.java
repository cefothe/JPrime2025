package eu.stefanangelov.jprime2025.nats.latency.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.nats.latency.model.BookTicker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nats.client.Connection;
import io.nats.client.Subscription;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class BookTickerConsumer {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Connection natsConnection;
    private final MeterRegistry meterRegistry;
    private final Timer latencyTimer;
    private Subscription subscription;

    public BookTickerConsumer(Connection natsConnection, MeterRegistry meterRegistry,
                              @Value("${nats.url}") String natsUrl) {
        this.natsConnection = natsConnection;
        this.meterRegistry = meterRegistry;
        this.latencyTimer = Timer.builder("nats.latency")
                .description("Latency of messages received via NATS")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    public void startSubscription() {
        subscription = natsConnection.subscribe("book.ticker"); // Use Connection.subscribe instead of JetStream
        new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    io.nats.client.Message msg = subscription.nextMessage(100); // Poll messages
                    if (msg != null) {
                        meterRegistry.counter("nats.messages.received").increment();
                        long now = System.nanoTime();
                        BookTicker bookTicker = objectMapper.readValue(msg.getData(), BookTicker.class);
                        long latencyNs = now - bookTicker.getTimestamp();
                        double latencyMs = latencyNs / 1_000_000.0;
                        latencyTimer.record((Duration.ofNanos(latencyNs)));
                        log.info("Received book ticker: {}, Latency: {} ms", bookTicker.getSymbol(), latencyMs);
                    }
                } catch (Exception e) {
                    meterRegistry.counter("nats.errors").increment();
                    log.error("Error processing message", e);
                }
            }
        }).start();
    }

    @PreDestroy
    public void close() throws Exception {
        if (subscription != null) {
            subscription.unsubscribe();
        }
        natsConnection.close();
    }
}
