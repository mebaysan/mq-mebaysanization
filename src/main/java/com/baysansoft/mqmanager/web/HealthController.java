package com.baysansoft.mqmanager.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class HealthController {

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("ok", true);
    }
}
