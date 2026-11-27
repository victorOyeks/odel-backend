package com.odel.common.files;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SigV4PresignerTest {
    /** The worked example in AWS's "Authenticating Requests: Using Query Parameters" (S3 API reference). */
    @Test
    void matchesAwsDocumentedExample() {
        var signer = new SigV4Presigner("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        String url = signer.presign("GET", "https", "examplebucket.s3.amazonaws.com", "/test.txt",
                Duration.ofSeconds(86400), Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(url).endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void encodesKeysPerSegment() {
        assertThat(SigV4Presigner.encodePath("/bucket/logos/a b+c.svg")).isEqualTo("/bucket/logos/a%20b%2Bc.svg");
    }
}
