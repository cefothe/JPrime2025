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
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.ByteBuffer;

@Slf4j
@Component
public class BinanceWebSocketClient extends WebSocketClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Aeron aeron;
    private final Publication publication;

    private final UnsafeBuffer unsafeBuffer;

    public BinanceWebSocketClient() throws Exception {
        super(new URI("wss://stream.binance.com:9443/ws/btcusdt@bookTicker"));
        // Initialize Aeron

        //construct Media Driver, cleaning up media driver folder on start/stop
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy())
                .aeronDirectoryName("/Users/stefanangelov/Documents/workspace/JPrime2025/aeron-binance-websocket-service/aeron")
                .dirDeleteOnShutdown(true);
        unsafeBuffer = new UnsafeBuffer(ByteBuffer.allocate(256));
        final MediaDriver mediaDriver = MediaDriver.launchEmbedded(mediaDriverCtx);
        Aeron.Context context = new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName());
        aeron = Aeron.connect(context);
        publication = aeron.addPublication("aeron:udp?endpoint=localhost:40123|alias=book-ticker", 1001);
    }

    @PostConstruct
    public void connectWebSocket() {
        connect();
    }

    @PreDestroy
    public void close() {
        publication.close();
        aeron.close();
        super.close();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.info("Connected to Binance WebSocket");
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
}