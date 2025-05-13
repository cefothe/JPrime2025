package eu.stefanangelov.jprime2025.aeron.latency.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookTicker {
    @JsonProperty("s")
    private String symbol;
    @JsonProperty("b")
    private String bidPrice;
    @JsonProperty("B")
    private String bidQty;
    @JsonProperty("a")
    private String askPrice;
    @JsonProperty("A")
    private String askQty;
    private long timestamp; // Added for latency measurement
}