package com.taxonomy.shared.service;

import com.taxonomy.dto.AiAvailabilityLevel;

/** Read-only optional analysis status; no provider implementation or execution API. */
public interface AiStatusSource {
    AiAvailabilityLevel getAvailabilityLevel();
    String getActiveProviderName();
}
