package com.baysansoft.mqmanager.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the compiled React bundle out of the JAR and makes React Router deep links survive a hard
 * refresh, without ever hijacking the API.
 *
 * <p>A catch-all {@code @GetMapping("/**")} controller would be the obvious approach and is wrong:
 * {@code RequestMappingHandlerMapping} is order 0 while resource handling sits at
 * {@code Integer.MAX_VALUE - 1}, so the controller would intercept {@code /assets/index-abc123.js} and
 * return HTML for JavaScript. The {@code /{path:[^.]*}} regex variant is valid PathPattern syntax but
 * matches a single segment only, so {@code /connections/1/queue} would still 404.
 *
 * <p>Note {@code spring.web.resources.add-mappings=false} in application.yml: Boot registers its own
 * {@code /**} handler first and {@code ResourceHandlerRegistry} keeps handlers in a LinkedHashMap, so
 * a second {@code /**} registration silently replaces Boot's. Turning Boot's off makes this the single,
 * explicit registration rather than an accidental override.
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    /** Paths that must 404 as JSON rather than being answered with the SPA shell. */
    private static final String[] RESERVED_PREFIXES = {"api", "actuator", "error", "h2-console"};

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite emits content-hashed filenames, so this is a finite key space and safe to cache hard.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable())
                .resourceChain(true);

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                // Exactly ONE location: the resolver runs once per configured location and the first
                // non-null result wins, so with several locations the fallback could shadow a real file
                // that lives in a later one.
                //
                // resourceChain(false) is deliberate. CachingResourceResolver is backed by an UNBOUNDED
                // ConcurrentMapCache, and this handler's key space is any URL a client cares to invent.
                // With no authentication in front of the app that is a memory-exhaustion vector.
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    static class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }

            String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;

            // Never answer for the API. An unmapped /api/** path falls through to
            // NoResourceFoundException, which the exception advice renders as the standard JSON 404.
            for (String reserved : RESERVED_PREFIXES) {
                if (path.equals(reserved) || path.startsWith(reserved + "/")) {
                    return null;
                }
            }

            // Anything that looks like a file (a dot after the last slash) gets an honest 404 rather
            // than a 200 of HTML. This is why queue names travel as query parameters and never as path
            // segments: DEV.QUEUE.1 is a perfectly ordinary MQ queue name.
            if (path.lastIndexOf('.') > path.lastIndexOf('/')) {
                return null;
            }

            // A React Router route. Content-Type comes from the resolved resource's filename, not the
            // request URI, so this correctly returns text/html.
            return super.getResource("index.html", location);
        }
    }
}
