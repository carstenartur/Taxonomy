package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import java.util.*;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/** Server-owned registrations. A remote declaration or descriptor bit cannot install a verified contract. */
public final class PublicationPolicy {
    public static final Set<Guarantee> REQUIRED_GUARANTEES = Set.of(Guarantee.values());
    public record VerifiedContract(String connectorId, String profileVersion, PublicationCapabilities capabilities) {
        public VerifiedContract { text(connectorId); text(profileVersion); Objects.requireNonNull(capabilities); }
    }
    private final List<VerifiedContract> contracts;
    /** Production defaults deny every write until an adapter's contract is explicitly registered. */
    public PublicationPolicy() { this(List.of()); }
    public PublicationPolicy(List<VerifiedContract> contracts) { this.contracts = List.copyOf(contracts); }
    public void requireVerified(IntegrationContext context, PublicationCapabilities capabilities) {
        if (capabilities == null || !CONTRACT_VERSION.equals(capabilities.contractVersion())
                || !context.externalScope().equals(capabilities.scope().externalScope())
                || !capabilities.guarantees().containsAll(REQUIRED_GUARANTEES)
                || contracts.stream().noneMatch(c -> c.connectorId().equals(context.profile()) && c.profileVersion().equals(context.profileVersion()) && c.capabilities().equals(capabilities)))
            throw IntegrationProblem.conflict("PUBLICATION_GUARANTEES_UNVERIFIED");
    }
    public void requireVerified(IntegrationContext context, LifecycleIntegrationConnector connector, PublicationCapabilities capabilities) {
        if (!(connector instanceof ConditionalPublicationConnector) || !connector.descriptor().id().equals(context.profile())
                || !connector.descriptor().version().equals(context.profileVersion())) throw IntegrationProblem.conflict("PUBLICATION_GUARANTEES_UNVERIFIED");
        requireVerified(context, capabilities);
    }
    public void requireAuthority(IntegrationContext context, PublicationMode mode) {
        if (mode == PublicationMode.SYNCHRONIZE ? context.authority() != AuthorityMode.BIDIRECTIONAL
                : context.authority() != AuthorityMode.BIDIRECTIONAL && context.authority() != AuthorityMode.PUBLISH_TARGET)
            throw new IntegrationProblem("PUBLICATION_AUTHORITY_DENIED", 403, "This connection does not permit the requested publication mode");
    }
    public PublicationAvailability availability(IntegrationContext context, LifecycleIntegrationConnector connector, PublicationCapabilities capabilities) {
        try {
            requireVerified(context, connector, capabilities);
            Set<PublicationMode> modes = context.authority() == AuthorityMode.BIDIRECTIONAL ? Set.of(PublicationMode.values())
                    : context.authority() == AuthorityMode.PUBLISH_TARGET ? Set.of(PublicationMode.PUSH) : Set.of();
            return new PublicationAvailability(!modes.isEmpty(), modes, modes.isEmpty() ? Set.of() : capabilities.mutations(), modes.isEmpty() ? "PUBLICATION_AUTHORITY_DENIED" : null);
        } catch (IntegrationProblem problem) { return new PublicationAvailability(false, Set.of(), Set.of(), problem.code()); }
    }
}
