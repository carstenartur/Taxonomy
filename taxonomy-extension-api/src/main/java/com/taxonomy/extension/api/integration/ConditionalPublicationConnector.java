package com.taxonomy.extension.api.integration;
import com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext;
import com.taxonomy.extension.api.integration.PublicationContracts.*;

/** Only server-verified adapters may execute this atomic, durable idempotency contract. */
public interface ConditionalPublicationConnector extends LifecycleIntegrationConnector {
    PublicationCapabilities publicationCapabilities(IntegrationContext context, PublicationScope scope);
    ScopeSnapshot readPublicationScope(IntegrationContext context, PublicationScope scope, String expectedRevision);
    PublicationReceipt publishItem(IntegrationContext context, PublicationItemRequest request);
    PublicationReceiptLookup lookupPublicationReceipt(IntegrationContext context, PublicationReceiptQuery query);
}
