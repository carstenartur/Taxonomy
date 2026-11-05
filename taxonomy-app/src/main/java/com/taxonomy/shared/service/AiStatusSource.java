package com.taxonomy.shared.service;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AiStatusResponse;

/** Read-only optional analysis status; no provider implementation or execution API. */
public interface AiStatusSource {
    AiAvailabilityLevel getAvailabilityLevel();
    String getActiveProviderName();
    AiStatusResponse snapshot();
    AiStatusResponse snapshot(String provider);
}
