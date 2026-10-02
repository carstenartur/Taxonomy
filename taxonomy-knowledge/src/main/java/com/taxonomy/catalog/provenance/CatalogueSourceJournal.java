package com.taxonomy.catalog.provenance;

import jakarta.persistence.*;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.*;

/** Source evidence commits with the catalogue mutation, never in a separate transaction. */
@Service
public class CatalogueSourceJournal {
    private static final String STATE_ID = "base-catalogue";
    private final EntityManager em;
    public enum Use { APPLIED, PARSE_FAILED, NOT_USED, NOT_RETAINED }
    public record SourceUse(CatalogueSourceBytes input, Use use) {
        public SourceUse {
            Objects.requireNonNull(use);
            if ((use == Use.APPLIED || use == Use.PARSE_FAILED) != (input != null))
                throw new IllegalArgumentException("Catalogue input use does not match its retained bytes");
        }
        public static SourceUse applied(CatalogueSourceBytes input) { return new SourceUse(input, Use.APPLIED); }
        public static SourceUse rejected(CatalogueSourceBytes input) { return new SourceUse(input, Use.PARSE_FAILED); }
        public static SourceUse notUsed() { return new SourceUse(null, Use.NOT_USED); }
    }
    public record InputReference(Use use, String sha256, long length) { }
    public record Snapshot(String id, Instant createdAt, InputReference workbook, InputReference overlay, InputReference relations) { }
    public CatalogueSourceJournal(EntityManagerFactory factory) { em = SharedEntityManagerCreator.createSharedEntityManager(factory); }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot initialize(CatalogueSourceBytes workbook, SourceUse overlay, SourceUse relations) {
        requireTransaction(); Objects.requireNonNull(workbook);
        var state = lockState(); var previous = snapshot(state);
        return record(state, previous, retain(SourceUse.applied(workbook)), retain(overlay), retain(relations));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot reconcileOverlay(SourceUse overlay) {
        requireTransaction();
        var state = lockState(); var previous = snapshot(state);
        var unknown = new InputReference(Use.NOT_RETAINED, null, 0);
        return record(state, previous, previous == null ? unknown : previous.workbook(), retain(overlay),
                previous == null ? unknown : previous.relations());
    }

    @Transactional(readOnly = true)
    public Snapshot current() { return snapshot(em.find(CatalogueSourceState.class, STATE_ID)); }

    private void requireTransaction() {
        if (!em.isJoinedToTransaction()) throw new IllegalStateException("Catalogue source retention requires the catalogue transaction");
    }

    private CatalogueSourceState lockState() {
        var state = em.find(CatalogueSourceState.class, STATE_ID, LockModeType.PESSIMISTIC_WRITE);
        if (state == null) {
            state = new CatalogueSourceState(); state.id = STATE_ID;
            em.persist(state); em.flush();
        }
        return state;
    }

    private Snapshot record(CatalogueSourceState state, Snapshot previous, InputReference workbook,
                            InputReference overlay, InputReference relations) {
        if (previous != null && previous.workbook().equals(workbook) && previous.overlay().equals(overlay)
                && previous.relations().equals(relations)) return previous;
        var revision = new CatalogueSourceRevision(); revision.id = UUID.randomUUID().toString(); revision.createdAt = Instant.now().toString();
        revision.workbook = workbook.sha256(); revision.workbookUse = workbook.use().name();
        revision.overlay = overlay.sha256(); revision.overlayUse = overlay.use().name();
        revision.relations = relations.sha256(); revision.relationsUse = relations.use().name();
        // Flush immutable evidence before the pointer so SQL foreign keys also hold on providers without JPA association ordering.
        em.persist(revision); em.flush(); state.currentRevision = revision.id; em.flush();
        return new Snapshot(revision.id, Instant.parse(revision.createdAt), workbook, overlay, relations);
    }

    private InputReference retain(SourceUse source) {
        Objects.requireNonNull(source);
        if (source.input() == null) return new InputReference(source.use(), null, 0);
        var input = source.input(); var existing = em.find(CatalogueSourceBlob.class, input.sha256());
        byte[] content = input.copyForStorage();
        if (existing == null) {
            var blob = new CatalogueSourceBlob(); blob.sha256 = input.sha256(); blob.byteLength = input.length(); blob.payload = content;
            em.persist(blob); em.flush();
        } else if (existing.byteLength != input.length() || !Arrays.equals(existing.payload, content)) {
            throw new IllegalStateException("Retained catalogue source bytes differ from their recorded identity");
        }
        return new InputReference(source.use(), input.sha256(), input.length());
    }

    private Snapshot snapshot(CatalogueSourceState state) {
        if (state == null || state.currentRevision == null) {
            if (em.createQuery("select count(r) from CatalogueSourceRevision r", Long.class).getSingleResult() != 0) throw invalid();
            return null;
        }
        try {
            if (!UUID.fromString(state.currentRevision).toString().equals(state.currentRevision)) throw invalid();
            var revision = em.find(CatalogueSourceRevision.class, state.currentRevision);
            if (revision == null) throw invalid();
            return new Snapshot(revision.id, Instant.parse(revision.createdAt),
                    reference(revision.workbookUse, revision.workbook), reference(revision.overlayUse, revision.overlay),
                    reference(revision.relationsUse, revision.relations));
        } catch (IllegalArgumentException | NullPointerException | DateTimeException failure) { throw invalid(); }
    }

    private InputReference reference(String status, String hash) {
        var use = Use.valueOf(status);
        if (use == Use.NOT_USED || use == Use.NOT_RETAINED) {
            if (hash != null) throw invalid();
            return new InputReference(use, null, 0);
        }
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw invalid();
        var lengths = em.createQuery("select b.byteLength from CatalogueSourceBlob b where b.sha256=:hash", Long.class)
                .setParameter("hash", hash).getResultList();
        if (lengths.size() != 1 || lengths.getFirst() < 0 || lengths.getFirst() > CatalogueSourceBytes.MAX_BYTES) throw invalid();
        return new InputReference(use, hash, lengths.getFirst());
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Retained catalogue source references are inconsistent"); }
}
