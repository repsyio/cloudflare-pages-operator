package io.repsy.cfpo.cloudflare.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code subdomain} is the project's {@code <name>.pages.dev} hostname. */
public record PagesProject(
    String id,
    String name,
    String subdomain,
    @JsonProperty("production_branch") String productionBranch) {}
