package eu.stefanangelov.jprime2025.nats.latency.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NatsConfig {

    @Bean
    public Connection natsConnection(@Value("${nats.url}") String natsUrl) throws Exception {
        Options options = new Options.Builder()
                .server(natsUrl)
                .build();
        return Nats.connect(options);
    }
}
