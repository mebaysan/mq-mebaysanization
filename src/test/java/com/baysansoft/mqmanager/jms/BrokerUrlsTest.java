package com.baysansoft.mqmanager.jms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The masking rules behind two absolute promises in README.md — that a credential never reaches a log
 * line and never reaches an error body. Both are reachable without authentication (a 502 body, and the
 * Logs page), and both fire for <em>saved</em> profiles, so a leak here is a third party reading someone
 * else's stored broker password. This class exists because that code shipped untested and did leak.
 */
class BrokerUrlsTest {

    @Test
    @DisplayName("an ordinary user:password@host is masked")
    void masksOrdinaryCredentials() {
        assertThat(BrokerUrls.sanitize("tcp://admin:secret@127.0.0.1:61616"))
                .isEqualTo("tcp://****:****@127.0.0.1:61616");
    }

    @Test
    @DisplayName("a password containing '@' is masked whole, not truncated at the first '@'")
    void masksPasswordContainingAtSign() {
        // The regression that motivated this class: the narrower pattern anchored on the first '@' and
        // returned "tcp://****:****@ssw0rd@127.0.0.1:1", publishing most of the password.
        assertThat(BrokerUrls.sanitize("tcp://admin:p@ssw0rd@127.0.0.1:1"))
                .isEqualTo("tcp://****:****@127.0.0.1:1")
                .doesNotContain("ssw0rd");
    }

    @Test
    @DisplayName("a username containing '@' is still recognised")
    void masksUsernameContainingAtSign() {
        assertThat(BrokerUrls.sanitize("tcp://user@domain:secret@broker:61616"))
                .isEqualTo("tcp://****:****@broker:61616")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("every credential in a composite failover URL is masked, not just the first")
    void masksEveryHostInAFailoverUrl() {
        String masked = BrokerUrls.sanitize(
                "failover://(tcp://u1:p1@a:61616,tcp://u2:p2@b:61616)?maxReconnectAttempts=1");

        assertThat(masked)
                .isEqualTo("failover://(tcp://****:****@a:61616,tcp://****:****@b:61616)?maxReconnectAttempts=1")
                .doesNotContain("p1")
                .doesNotContain("p2");
    }

    @Test
    @DisplayName("a credential-free URL is returned untouched, so error messages stay useful")
    void leavesCredentialFreeUrlsAlone() {
        assertThat(BrokerUrls.sanitize("tcp://127.0.0.1:61616?connectionTimeout=5000"))
                .isEqualTo("tcp://127.0.0.1:61616?connectionTimeout=5000");
        assertThat(BrokerUrls.sanitize("failover://(tcp://a:61616,tcp://b:61616)"))
                .isEqualTo("failover://(tcp://a:61616,tcp://b:61616)");
    }

    @Test
    @DisplayName("Kafka bootstrap servers have no scheme and pass through unchanged")
    void leavesBootstrapServersAlone() {
        assertThat(BrokerUrls.sanitize("host1:9092,host2:9092")).isEqualTo("host1:9092,host2:9092");
    }

    @Test
    @DisplayName("a password containing '/' cannot be masked reliably, so the whole URL is withheld")
    void withholdsWhatItCannotMask() {
        // '/' is indistinguishable from a path separator, so the pattern cannot span it. Withholding is
        // the honest outcome: the caller loses a hostname, not a password.
        String masked = BrokerUrls.sanitize("tcp://admin:pa/ss@127.0.0.1:61616");

        assertThat(masked).doesNotContain("pa/ss").contains("withheld");
    }

    @Test
    @DisplayName("null survives, because describe() feeds it optional profile fields")
    void nullIsNotAnError() {
        assertThat(BrokerUrls.sanitize(null)).isNull();
    }
}
