package com.workloadhub.forecastweb.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built front end ({@code forecast-web.ui-dir}, {@code ui/dist} by default) at the root, with the
 * single-page fallback: a path that is not a file and not under {@code /api} gets {@code index.html}, so the
 * browser's router owns it. Nothing is registered when the directory does not exist.
 */
public final class UiResources implements WebMvcConfigurer {

    private final Path dir;

    public UiResources(Path dir) {
        this.dir = dir;
    }

    public boolean present() {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("index.html"));
    }

    /** What a request path resolves to: the file when it exists, index.html for a page route, nothing under /api. */
    public static String resolve(String requestPath, Predicate<String> fileExists) {
        String path = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (path.startsWith("api/") || path.equals("api")) {
            return null;
        }
        if (!path.isEmpty() && fileExists.test(path)) {
            return path;
        }
        return "index.html";
    }

    /** The root itself is not a resource path Spring hands to the resolver, so it is forwarded to the index explicitly. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (present()) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!present()) {
            return;
        }
        registry.addResourceHandler("/**").addResourceLocations(dir.toUri().toString()).resourceChain(true).addResolver(new PathResourceResolver() {
            @Override
            protected Resource getResource(String resourcePath, Resource location) throws IOException {
                String target = resolve(resourcePath, p -> {
                    try {
                        Resource r = location.createRelative(p);
                        return r.exists() && r.isReadable() && !r.getFile().isDirectory();
                    } catch (IOException e) {
                        return false;
                    }
                });
                return target == null ? null : location.createRelative(target);
            }
        });
    }
}
