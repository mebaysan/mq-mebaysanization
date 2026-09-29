package com.baysansoft.mqmanager.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.baysansoft.mqmanager.config.MqManagerProperties.SeedConnection;
import com.baysansoft.mqmanager.service.ConnectionProfileService;
import com.baysansoft.mqmanager.web.dto.ConnectionProfileRequest;

/**
 * Creates the connections listed under {@code mqmanager.seed-connections} on startup, so an install can
 * ship with a site's clusters already listed rather than making everyone retype them.
 *
 * <p><strong>Each seed name is applied at most once per data directory.</strong> A marker file in the
 * data directory records which seed names have already been handled; a name found there is never touched
 * again. The consequences are the ones a user expects of a "default":
 * <ul>
 *   <li>Delete a seeded connection in the UI and it stays deleted — it is not recreated on the next
 *       start, because its name is already in the marker.</li>
 *   <li>Edit one and the edit is permanent — again, the name is in the marker, so the seeder leaves it
 *       alone.</li>
 *   <li>Add a <em>new</em> seed later (a new name) and it is created once on the next start, without
 *       resurrecting anything previously deleted.</li>
 * </ul>
 *
 * <p>The seed list is empty in the committed configuration on purpose: broker addresses are site-specific
 * and a public repository is the wrong place for them. The real values live in an untracked
 * {@code seed-connections.yml} bundled into a locally built JAR — see that file and {@code .gitignore}.
 * With no seeds configured this runner does nothing at all.
 */
@Component
class ConnectionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConnectionSeeder.class);

    /** One seed name per line, in the data directory. Not in the repo — the data directory is ignored. */
    private static final String MARKER_FILE = "seeded-connections.marker";

    private final MqManagerProperties properties;
    private final ConnectionProfileService connections;

    ConnectionSeeder(MqManagerProperties properties, ConnectionProfileService connections) {
        this.properties = properties;
        this.connections = connections;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<SeedConnection> seeds = properties.getSeedConnections();
        if (seeds == null || seeds.isEmpty()) {
            return;
        }

        Path marker = properties.getDataDir().resolve(MARKER_FILE);
        Set<String> alreadySeeded = readMarker(marker);
        Set<String> existing = connections.list().stream()
                .map(profile -> normalise(profile.name()))
                .collect(Collectors.toCollection(HashSet::new));

        List<String> newlyHandled = new ArrayList<>();
        int created = 0;
        for (SeedConnection seed : seeds) {
            if (!StringUtils.hasText(seed.getName()) || seed.getProvider() == null) {
                log.warn("Skipping a seed connection with no name or provider.");
                continue;
            }
            String key = normalise(seed.getName());
            // Handled once already — deleting or editing it since is the user's call, not ours to undo.
            if (alreadySeeded.contains(key)) {
                continue;
            }
            // Guard against a name a user created by hand before the first seed run, so we never duplicate.
            if (!existing.contains(key)) {
                connections.create(new ConnectionProfileRequest(
                        null, seed.getName(), seed.getProvider(), seed.getHost(), seed.getPort(),
                        seed.getUsername(), null, seed.getBrokerUrlOverride(), seed.getBootstrapServers(),
                        seed.getQueueManagerName(), seed.getChannel()));
                existing.add(key);
                created++;
            }
            alreadySeeded.add(key);
            newlyHandled.add(seed.getName().trim());
        }

        if (!newlyHandled.isEmpty()) {
            appendToMarker(marker, newlyHandled);
        }
        if (created > 0) {
            log.info("Seeded {} connection(s) on first run.", created);
        }
    }

    private static String normalise(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> readMarker(Path marker) {
        if (!Files.exists(marker)) {
            return new HashSet<>();
        }
        try {
            return Files.readAllLines(marker, StandardCharsets.UTF_8).stream()
                    .filter(StringUtils::hasText)
                    .map(ConnectionSeeder::normalise)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (IOException e) {
            // Unreadable marker: fall back to "create anything missing", guarded by the name check above,
            // rather than failing startup over bookkeeping. Worst case a deleted seed reappears once.
            log.warn("Could not read the seed marker at {} ({}); seeding by presence only this time.",
                    marker, e.toString());
            return new HashSet<>();
        }
    }

    private void appendToMarker(Path marker, List<String> names) {
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, names, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The connections were still created; only the record that we did so failed to persist. Log
            // it rather than crash — the presence guard keeps this from duplicating them next time.
            log.warn("Seeded connections but could not update the marker at {} ({}).", marker, e.toString());
        }
    }
}
