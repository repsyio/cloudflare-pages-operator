package io.repsy.cfpo.cloudflare.model;

public record DnsRecord(
    String id, String type, String name, String content, Boolean proxied, String comment) {}
