package eu.stefanangelov.jprime2025.kafka.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.kafka.websocket.model.BookTicker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;


import java.net.URI;

@Slf4j
@Component
public class BinanceWebSocketClient extends WebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaTemplate<String, BookTicker> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public BinanceWebSocketClient(KafkaTemplate<String, BookTicker> kafkaTemplate, MeterRegistry meterRegistry) throws Exception {
            super(new URI("wss://stream.binance.com:9443/ws/btcusdt@bookTicker"));
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void connectWebSocket() {
        connect();
    }

    @PreDestroy
    public void close() {
        super.close();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("Connected to Binance WebSocket");
    }

    @Override
    public void onMessage(String message) {
        meterRegistry.counter("binance.messages.received").increment();
        try {
            BookTicker bookTicker = objectMapper.readValue(message, BookTicker.class);
            bookTicker.setTimestamp(System.nanoTime()); // Attach timestamp
            // Produce to Kafka
            kafkaTemplate.send("book-ticker", bookTicker.getSymbol(), bookTicker);
            meterRegistry.counter("kafka.messages.produced").increment();
            log.info("Produced book ticker: {}", bookTicker.getSymbol());
        } catch (Exception e) {
            meterRegistry.counter("binance.errors").increment();
            log.error("Error processing message: {}", message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("WebSocket closed: {} - {}", code, reason);
    }

    @Override
    public void onError(Exception ex) {
        meterRegistry.counter("binance.errors").increment();
        log.error("WebSocket error", ex);
    }
}