package com.taxonomy.portfolio.controller;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Origin-relative resource locations under the effective servlet or forwarded context path. */
final class PortfolioResourceLocation {

    private PortfolioResourceLocation() {
    }

    static URI forPath(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .scheme(null).host(null).port(-1)
                // The servlet context is already URI-encoded; controlled resource paths are ASCII.
                .path(path).build(true).toUri();
    }
}
