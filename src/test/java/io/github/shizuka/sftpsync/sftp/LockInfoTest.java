package io.github.shizuka.sftpsync.sftp;

import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LockInfoTest {

    @Test
    @DisplayName("LockInfo.now sets acquiredAt and lastHeartbeatAt to the same instant")
    void now_acquiredAtEqualsLastHeartbeat() {
        LockInfo info = LockInfo.now("host+pid1+abc12345", "push", 300);
        assertThat(info.acquiredAt()).isEqualTo(info.lastHeartbeatAt());
        assertThat(info.holder()).isEqualTo("host+pid1+abc12345");
        assertThat(info.operation()).isEqualTo("push");
        assertThat(info.ttlSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("LockInfo compact constructor coerces ttlSeconds=0 to default 300")
    void compactCtor_zeroTtl_coercesToDefault() {
        LockInfo info = new LockInfo("h", "push", "t0", "t1", 0);
        assertThat(info.ttlSeconds()).isEqualTo(LockInfo.DEFAULT_TTL_SECONDS);
    }

    @Test
    @DisplayName("LockInfo round-trips through jackson-jr JSON")
    void jsonRoundTrip_preservesAllFields() throws IOException {
        LockInfo original = new LockInfo("host+pid42+abcd1234", "pull",
            "2026-05-13T14:20:00Z", "2026-05-13T14:21:30Z", 300);
        String json = JSON.std.asString(original);
        LockInfo decoded = JSON.std.beanFrom(LockInfo.class, json);
        assertThat(decoded).isEqualTo(original);
    }
}
