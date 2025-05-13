package eu.stefanangelov.jprime2025.kafka.latency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LatencyMeasurementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LatencyMeasurementServiceApplication.class, args);
    }
}
