package com.baysansoft.mqmanager.web;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.baysansoft.mqmanager.config.MqManagerProperties;

/**
 * When {@code mqmanager.open-browser} is on, points the machine's default browser at this app once it is
 * actually serving. That flag is off by default and only the desktop launchers set it (see
 * {@link MqManagerProperties#isOpenBrowser()}), so a server or a {@code spring-boot:run} never pops a
 * window.
 *
 * <p>The port is read from {@link WebServerInitializedEvent} rather than {@code server.port}, because the
 * configured value can be {@code 0} ("pick any free port") and only the initialised server knows the real
 * one. The browser is opened on {@link ApplicationReadyEvent}, one step later, so the URL answers the
 * instant it is opened rather than racing the last of startup.
 *
 * <p>Opening a browser is a best-effort convenience: any failure is logged and swallowed. Refusing to
 * start because a browser would not launch would be the tool lying about the thing that matters (the
 * server is up) over the thing that does not.
 */
@Component
class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final MqManagerProperties properties;
    private volatile int port = -1;

    BrowserLauncher(MqManagerProperties properties) {
        this.properties = properties;
    }

    @EventListener
    void onWebServerReady(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    @EventListener
    void onApplicationReady(ApplicationReadyEvent event) {
        if (!properties.isOpenBrowser()) {
            return;
        }
        if (port <= 0) {
            log.warn("Cannot open browser: no HTTP port was reported by the web server.");
            return;
        }
        String url = "http://localhost:" + port;
        try {
            openInDefaultBrowser(url);
            log.info("Opened the default browser at {}", url);
        } catch (Exception e) {
            // A blocked or missing browser must never take the server down with it.
            log.warn("Could not open a browser automatically; open {} yourself. ({})", url, e.toString());
        }
    }

    private void openInDefaultBrowser(String url) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.contains("win")) {
            // rundll32 hands the URL to the shell's default handler without flashing a cmd window, and it
            // needs no AWT (java.awt.Desktop is unreliable under a console/headless-ish launcher).
            command = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else if (os.contains("mac")) {
            command = List.of("open", url);
        } else {
            command = List.of("xdg-open", url);
        }
        new ProcessBuilder(command).inheritIO().start();
    }
}
