package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.PluginIdentity;

public record PluginOperationResult(String id, PluginIdentity plugin, Status status, String code) {
    public enum Status { STOPPED, DRAINING, REJECTED }
}
