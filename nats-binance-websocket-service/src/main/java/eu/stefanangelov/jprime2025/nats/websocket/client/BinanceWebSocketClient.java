package eu.stefanangelov.jprime2025.nats.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.nats.websocket.model.BookTicker;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Slf4j
@Component
public class BinanceWebSocketClient extends WebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Connection natsConnection;
    private final MeterRegistry meterRegistry;

    public BinanceWebSocketClient(Connection natsConnection, MeterRegistry meterRegistry,
                                  @Value("${nats.url}") String natsUrl) throws Exception {
        super(new URI("wss://stream.binance.com:9443/ws/btcusdt@bookTicker"));
        this.natsConnection = natsConnection;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void connectWebSocket() {
        connect();
    }

    @PreDestroy
    public void close() {
        try {
            natsConnection.close();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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
            // Serialize to JSON and publish to NATS
            byte[] data = objectMapper.writeValueAsBytes(bookTicker);
            natsConnection.publish("book.ticker", data); // Use Connection.publish instead of JetStream
            meterRegistry.counter("nats.messages.published").increment();
            log.info("Published book ticker: {}", bookTicker.getSymbol());
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