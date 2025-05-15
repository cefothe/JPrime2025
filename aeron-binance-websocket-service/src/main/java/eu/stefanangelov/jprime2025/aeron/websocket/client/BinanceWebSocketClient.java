package eu.stefanangelov.jprime2025.aeron.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.aeron.websocket.model.BookTicker;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
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
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class BinanceWebSocketClient extends WebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Aeron aeron;
    private final Publication publication;
    private final UnsafeBuffer unsafeBuffer;
    private final Set<String> subscribedPairs = Collections.synchronizedSet(new HashSet<>());
    private final MediaDriver mediaDriver;

    public BinanceWebSocketClient(
            @Value("${AERON_CHANNEL:aeron:udp?control=localhost:40123|control-mode=dynamic}") String aeronChannel)
            throws Exception {
        super(new URI("wss://stream.binance.com:9443/ws"));
        subscribedPairs.add("btcusdt");

        // Initialize Aeron
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy());
//                .aeronDirectoryName("/aeron"); // Matches Kubernetes volume mount

        this.mediaDriver = MediaDriver.launchEmbedded(mediaDriverCtx);
        Aeron.Context context = new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName());
        this.aeron = Aeron.connect(context);
        this.publication = aeron.addExclusivePublication(aeronChannel, 100);
        this.unsafeBuffer = new UnsafeBuffer(ByteBuffer.allocate(256));
    }

    @PostConstruct
    public void connectWebSocket() {
        connect();
    }

    @PreDestroy
    public void close() {
        try {
            if (publication != null && !publication.isClosed()) {
                publication.close();
            }
        } catch (Exception e) {
            log.error("Error closing publication", e);
        }
        try {
            if (aeron != null && !aeron.isClosed()) {
                aeron.close();
            }
        } catch (Exception e) {
            log.error("Error closing Aeron", e);
        }
        try {
            if (mediaDriver != null) {
                mediaDriver.close();
            }
        } catch (Exception e) {
            log.error("Error closing MediaDriver", e);
        }
        try {
            super.close();
        } catch (Exception e) {
            log.error("Error closing WebSocket", e);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("Connected to Binance WebSocket");
        subscribeToPairs(subscribedPairs);
    }

    @Override
    public void onMessage(String message) {
        try {
            BookTicker bookTicker = objectMapper.readValue(message, BookTicker.class);
            bookTicker.setTimestamp(System.nanoTime()); // Attach timestamp

            unsafeBuffer.putStringAscii(0, objectMapper.writeValueAsString(bookTicker));
            // Publish via Aeron
            while (publication.offer(unsafeBuffer) < -1) {
                // Handle backpressure
                Thread.yield();
            }
            log.info("Published book ticker: {}", bookTicker.getSymbol());
        } catch (Exception e) {
            log.error("Error processing message: {}", message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("WebSocket closed: {} - {}", code, reason);
    }

    @Override
    public void onError(Exception ex) {
        log.error("WebSocket error", ex);
    }

    /**
     * Subscribe to additional pairs dynamically.
     *
     * @param pairs the trading pairs to subscribe to
     */
    public synchronized void subscribeToPairs(Set<String> pairs) {
        try {
            Map<String, Object> subscription = new HashMap<>();
            subscription.put("method", "SUBSCRIBE");
            subscription.put("params", pairs.stream()
                    .map(pair -> pair.toLowerCase() + "@bookTicker")
                    .toList());
            subscription.put("id", 1);

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