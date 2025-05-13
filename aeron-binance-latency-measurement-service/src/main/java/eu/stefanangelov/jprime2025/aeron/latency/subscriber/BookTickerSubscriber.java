package eu.stefanangelov.jprime2025.aeron.latency.subscriber;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.stefanangelov.jprime2025.aeron.latency.model.BookTicker;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.BusySpinIdleStrategy;

import java.time.Duration;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class BookTickerSubscriber {

    private final Aeron aeron;
    private final Subscription subscription;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MeterRegistry meterRegistry;
    private final Timer latencyTimer;

    public BookTickerSubscriber(MeterRegistry meterRegistry) {

        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true)
                .threadingMode(ThreadingMode.DEDICATED)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .aeronDirectoryName("/Users/stefanangelov/Documents/workspace/JPrime2025/aeron-binance-latency-measurement-service/aeron")
                .receiverIdleStrategy(new BusySpinIdleStrategy());

        final MediaDriver mediaDriver = MediaDriver.launchEmbedded(mediaDriverCtx);
        Aeron.Context context = new Aeron.Context().aeronDirectoryName(mediaDriver.aeronDirectoryName());
        aeron = Aeron.connect(context);
        subscription = aeron.addSubscription("aeron:udp?endpoint=localhost:40123|alias=book-ticker", 1001);
        this.meterRegistry = meterRegistry;
        this.latencyTimer = Timer.builder("aeron.latency")
                .description("Latency of messages received via Aeron")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    public void startPolling() {
        FragmentHandler handler = (buffer, offset, length, header) -> {
            try {
                meterRegistry.counter("aeron.messages.received").increment();
                long now = System.nanoTime();
                BookTicker bookTicker = objectMapper.readValue(buffer.getStringAscii(offset), BookTicker.class);
                long latencyNs = now - bookTicker.getTimestamp();
                double latencyMs = latencyNs / 1_000_000.0;
                latencyTimer.record((Duration.ofNanos(latencyNs)));
                log.info("Received book ticker: {}, Latency: {} ms", bookTicker.getSymbol(), latencyMs);
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        };

        new Thread(() -> {
            while (!Thread.interrupted()) {
                subscription.poll(handler, 10);
                Thread.yield();
            }
        }).start();
    }

    @PreDestroy
    public void close() {
        subscription.close();
        aeron.close();
    }
}
