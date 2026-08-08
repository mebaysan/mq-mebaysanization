package com.baysansoft.mqmanager.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.baysansoft.mqmanager.domain.ConnectionProfile;
import com.baysansoft.mqmanager.domain.Provider;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins down the requirement that no credential ever leaves the API.
 *
 * <p>Checked structurally as well as by serializing, because the structural check is the one that keeps
 * holding when someone adds a field in a year's time.
 */
class NoCredentialsInResponseTest {

    private static ConnectionProfile populatedProfile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(7L);
        profile.setName("prod");
        profile.setProvider(Provider.IBM_MQ);
        profile.setHost("mq.internal");
        profile.setPort(1414);
        profile.setUsername("app");
        profile.setPasswordCipher("v1:super-secret-ciphertext-that-must-never-be-serialized");
        profile.setQueueManagerName("QM1");
        profile.setChannel("DEV.APP.SVRCONN");
        profile.setCreatedAt(Instant.now());
        profile.setUpdatedAt(Instant.now());
        return profile;
    }

    @Test
    @DisplayName("the response type has no password-ish component at all")
    void responseTypeHasNoCredentialComponent() {
        RecordComponent[] components = ConnectionProfileResponse.class.getRecordComponents();

        // "hasPassword" is a legitimate boolean flag the edit form needs, so this checks for components
        // that could actually carry a secret rather than for the word appearing anywhere.
        assertThat(Arrays.stream(components))
                .as("a credential must not be representable in the response type, "
                        + "not merely filtered out of it")
                .noneMatch(component -> {
                    String name = component.getName().toLowerCase();
                    boolean nameLooksSensitive = name.equals("password")
                            || name.contains("cipher")
                            || name.contains("secret")
                            || name.contains("credential") && component.getType() == String.class;
                    return nameLooksSensitive;
                });
    }

    @Test
    @DisplayName("serialized JSON contains neither the ciphertext nor any password key")
    void serializedResponseLeaksNothing() throws Exception {
        ConnectionProfileResponse response =
                ConnectionProfileResponse.from(populatedProfile(), true);

        // findAndRegisterModules picks up JSR-310, the same as Boot's configured mapper.
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(json).doesNotContain("super-secret-ciphertext");
        // Specifically the keys, not any substring: "hasPassword" is a legitimate boolean flag and
        // naturally contains the word "password".
        assertThat(json)
                .doesNotContain("\"password\"")
                .doesNotContain("\"passwordCipher\"");
        // The non-sensitive signals the edit form needs are still present.
        assertThat(json).contains("\"hasPassword\":true").contains("\"credentialsReadable\":true");
    }

    @Test
    @DisplayName("the entity's toString never carries a credential into a log line")
    void entityToStringIsSafe() {
        String rendered = populatedProfile().toString();

        assertThat(rendered).doesNotContain("super-secret-ciphertext");
        assertThat(rendered).contains("prod").contains("IBM_MQ");
    }
}
