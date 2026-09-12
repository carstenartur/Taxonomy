package com.taxonomy.composition.dsl.service;

import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.versioning.service.DslOperationsFacade;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Composes document export/materialization and archive-compatible comparison with workspace Git. */
@Service
public class DslDocumentOperationsFacade {
    private final TaxDslExportService exportService;
    private final DslMaterializeService materializeService;
    private final ArchitectureDslDocumentRepository documentRepository;
    private final DslOperationsFacade dslOps;

    public DslDocumentOperationsFacade(TaxDslExportService exportService,
                                       DslMaterializeService materializeService,
                                       ArchitectureDslDocumentRepository documentRepository,
                                       DslOperationsFacade dslOps) {
        this.exportService = exportService;
        this.materializeService = materializeService;
        this.documentRepository = documentRepository;
        this.dslOps = dslOps;
    }

    public String exportAll(String namespace) {
        return exportService.exportAll(namespace);
    }

    public CanonicalArchitectureModel buildCanonicalModel() {
        return exportService.buildCanonicalModel();
    }

    public DslMaterializeService.MaterializeResult materialize(
            String dslText, String path, String branch, String commitId) {
        return materializeService.materialize(dslText, path, branch, commitId);
    }

    public DslMaterializeService.MaterializeResult materializeIncremental(
            Long beforeDocId, Long afterDocId) {
        return materializeService.materializeIncremental(beforeDocId, afterDocId);
    }

    public Optional<ArchitectureDslDocument> findDocumentById(Long id) {
        return documentRepository.findById(id);
    }

    public Optional<Long> findDocumentIdByCommitId(String commitId) {
        return documentRepository.findByCommitId(commitId)
                .map(ArchitectureDslDocument::getId);
    }

    public List<ArchitectureDslDocument> listDocuments() {
        return documentRepository.findAll();
    }

    public ModelDiff diffBetween(String beforeId, String afterId) throws Exception {
        if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
            return dslOps.diffBetween(beforeId, afterId);
        }
        return materializeService.diffDocuments(
                Long.valueOf(beforeId), Long.valueOf(afterId));
    }

    private static boolean looksLikeGitSha(String value) {
        return value != null
                && value.length() == 40
                && value.matches("[0-9a-f]+");
    }

}
