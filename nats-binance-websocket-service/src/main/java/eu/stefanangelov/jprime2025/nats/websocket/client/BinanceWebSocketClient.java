package eu.stefanangelov.jprime2025.nats.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.nats.websocket.model.BookTicker;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class BinanceWebSocketClient extends WebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Connection natsConnection;
    private final MeterRegistry meterRegistry;
    private final Set<String> subscribedPairs = Collections.synchronizedSet(new HashSet<>());

    public BinanceWebSocketClient(Connection natsConnection, MeterRegistry meterRegistry,
                                  @Value("wss://stream.binance.com:9443/ws") String binanceWsUrl) throws Exception {
        super(new URI(binanceWsUrl));
        this.natsConnection = natsConnection;
        this.meterRegistry = meterRegistry;
        this.subscribedPairs.add("btcusdt");
    }

    @PostConstruct
    public void connectWebSocket() {
        connect();
    }

    @PreDestroy
    public void close() {
        try {
            if (natsConnection != null) {
                natsConnection.close();
            }
        } catch (Exception e) {
            log.error("Error closing NATS connection", e);
        }
        super.close();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("Connected to Binance WebSocket");
        subscribeToPairs(subscribedPairs);
    }

    @Override
    public void onMessage(String message) {
        meterRegistry.counter("binance.messages.received").increment();
        try {
            BookTicker bookTicker = objectMapper.readValue(message, BookTicker.class);
            bookTicker.setTimestamp(System.nanoTime()); // Attach timestamp

            byte[] payload = objectMapper.writeValueAsBytes(bookTicker);
            natsConnection.publish("book.ticker."+bookTicker.getSymbol(), payload);
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

    /**
     * Subscribe to additional pairs dynamically.
     *
     * @param pairs the trading pairs to subscribe to
     */
    public synchronized void subscribeToPairs(Set<String> pairs) {
        try {
            Map<String, Object> subscription = Map.of(
                    "method", "SUBSCRIBE",
                    "params", pairs.stream()
                            .map(pair -> pair.toLowerCase() + "@bookTicker")
                            .toList(),
                    "id", 1
            );

            String subscribeMessage = objectMapper.writeValueAsString(subscription);
            send(subscribeMessage);
            log.info("Subscribed to pairs: {}", pairs);
        } catch (Exception e) {
            log.error("Error subscribing to pairs", e);
        }
    }

    /**
     * Add a new pair via REST request.
     *
     * @param pair the trading pair to add
     */
    @RestController
    @RequestMapping("/pairs")
    public static class PairController {
        private final BinanceWebSocketClient webSocketClient;

        public PairController(BinanceWebSocketClient webSocketClient) {
            this.webSocketClient = webSocketClient;
        }

        @PostMapping("/add")
        public ResponseEntity<String> addPair(@RequestParam String pair) {
            if (webSocketClient.subscribedPairs.add(pair.toLowerCase())) {
                webSocketClient.subscribeToPairs(Set.of(pair));
                return ResponseEntity.ok("Added and subscribed to pair: " + pair);
            }
            return ResponseEntity.badRequest().body("Pair already subscribed: " + pair);
        }
    }
}
