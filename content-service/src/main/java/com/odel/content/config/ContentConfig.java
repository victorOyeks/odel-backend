package com.odel.content.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.content.client.BunnyVideoGateway;
import com.odel.content.client.FakeVideoGateway;
import com.odel.content.client.VideoGateway;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** ODEL_VIDEO_MODE picks the video host: bunny (needs the ODEL_BUNNY_* keys) or fake (the default, local and tests). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentProperties.class)
public class ContentConfig {
    @Bean
    VideoGateway videoGateway(ContentProperties props, ObjectMapper json, Clock clock, @Value("${server.port:9308}") int port) {
        if (props.fake()) {
            LoggerFactory.getLogger(ContentConfig.class).warn("Videos use the FAKE host (ODEL_VIDEO_MODE=fake): nothing goes to Bunny Stream.");
            return new FakeVideoGateway(json, clock, "http://127.0.0.1:" + port, props.fakeReadySeconds());
        }
        return new BunnyVideoGateway(props.bunny(), json);
    }
}
