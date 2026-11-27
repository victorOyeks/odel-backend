package com.odel.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RequestInfoTest {

    @Test
    void nginxRealIpWins() {
        assertEquals("203.0.113.7", RequestInfo.clientIp("203.0.113.7", "1.2.3.4, 127.0.0.1", "127.0.0.1"));
    }

    @Test
    void firstForwardedForWhenNoRealIp() {
        assertEquals("2a02:c207:2054:1::1", RequestInfo.clientIp(null, "2a02:c207:2054:1::1, 127.0.0.1", "127.0.0.1"));
    }

    @Test
    void peerWhenNoHeaders() {
        assertEquals("127.0.0.1", RequestInfo.clientIp(" ", null, "127.0.0.1"));
    }
}
