package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.model.TaxonomyNode;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final String FIELD_CODE         = "code";
    private static final String FIELD_UUID         = "uuid";
    private static final String FIELD_EXTERNAL_ID  = "externalId";
    private static final String FIELD_NAME_EN      = "nameEn";
    private static final String FIELD_NAME_DE      = "nameDe";
    private static final String FIELD_DESC_EN      = "descriptionEn";
    private static final String FIELD_DESC_DE      = "descriptionDe";
    private static final String FIELD_NODE_ID      = "_nodeId";

    /** Per-field analyzer: English for EN fields, German for DE fields. */
    private final Analyzer analyzer;

    /** The in-memory Lucene index (rebuilt on each data load). */
    private volatile Directory directory;

    /** Flat node DTO cache keyed by node code (used to return results without re-querying JPA). */
    private final Map<String, TaxonomyNodeDto> nodeCache = new ConcurrentHashMap<>();

    public SearchService() {
        Map<String, Analyzer> fieldAnalyzers = new LinkedHashMap<>();
        fieldAnalyzers.put(FIELD_NAME_EN,  new EnglishAnalyzer());
        fieldAnalyzers.put(FIELD_DESC_EN,  new EnglishAnalyzer());
        fieldAnalyzers.put(FIELD_NAME_DE,  new GermanAnalyzer());
        fieldAnalyzers.put(FIELD_DESC_DE,  new GermanAnalyzer());
        this.analyzer = new PerFieldAnalyzerWrapper(new StandardAnalyzer(), fieldAnalyzers);
    }

    /**
     * Build (or rebuild) the in-memory Lucene index from all taxonomy nodes.
     * Called by {@link TaxonomyService} after the data has been persisted.
     */
    public void buildIndex(Collection<TaxonomyNode> nodes) {
        try {
            Directory newDirectory = new ByteBuffersDirectory();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(newDirectory, config)) {
                Map<String, TaxonomyNodeDto> newCache = new LinkedHashMap<>();
                for (TaxonomyNode node : nodes) {
                    TaxonomyNodeDto dto = toFlatDto(node);
                    String key = node.getCode();
                    newCache.put(key, dto);
                    writer.addDocument(buildDocument(node, key));
                }
                nodeCache.clear();
                nodeCache.putAll(newCache);
            }
            // Swap in the new directory
            this.directory = newDirectory;
            log.info("Lucene index built with {} documents.", nodes.size());
        } catch (IOException e) {
            log.error("Failed to build Lucene search index", e);
        }
    }

    private Document buildDocument(TaxonomyNode node, String key) {
        Document doc = new Document();
        doc.add(new StringField(FIELD_NODE_ID,    key,                                  Field.Store.YES));
        doc.add(new StringField(FIELD_CODE,        nullToEmpty(node.getCode()),          Field.Store.NO));
        doc.add(new StringField(FIELD_UUID,        nullToEmpty(node.getUuid()),          Field.Store.NO));
        doc.add(new StringField(FIELD_EXTERNAL_ID, nullToEmpty(node.getExternalId()),   Field.Store.NO));
        doc.add(new TextField(FIELD_NAME_EN,       nullToEmpty(node.getNameEn()),        Field.Store.NO));
        doc.add(new TextField(FIELD_NAME_DE,       nullToEmpty(node.getNameDe()),        Field.Store.NO));
        doc.add(new TextField(FIELD_DESC_EN,       nullToEmpty(node.getDescriptionEn()), Field.Store.NO));
        doc.add(new TextField(FIELD_DESC_DE,       nullToEmpty(node.getDescriptionDe()), Field.Store.NO));
        return doc;
    }

    /**
     * Search the taxonomy index and return up to {@code maxResults} flat (no children) DTOs.
     */
    public List<TaxonomyNodeDto> search(String queryString, int maxResults) {
        if (directory == null) {
            log.warn("Search invoked before index was built; returning empty result.");
            return Collections.emptyList();
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query query = buildQuery(queryString);
            TopDocs topDocs = searcher.search(query, maxResults);
            List<TaxonomyNodeDto> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                String nodeId = doc.get(FIELD_NODE_ID);
                TaxonomyNodeDto dto = nodeCache.get(nodeId);
                if (dto != null) {
                    results.add(dto);
                }
            }
            return results;
        } catch (IOException e) {
            log.error("Lucene search failed", e);
            return Collections.emptyList();
        } catch (ParseException e) {
            log.warn("Invalid search query '{}': {}", queryString, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Query buildQuery(String queryString) throws ParseException {
        String[] fullTextFields = { FIELD_NAME_EN, FIELD_NAME_DE, FIELD_DESC_EN, FIELD_DESC_DE };
        String escaped = QueryParser.escape(queryString);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        // Full-text query across EN/DE name and description fields (with stemming/stop-words)
        MultiFieldQueryParser fullTextParser = new MultiFieldQueryParser(fullTextFields, analyzer);
        fullTextParser.setDefaultOperator(QueryParser.Operator.OR);
        try {
            Query ftQuery = fullTextParser.parse(queryString);
            builder.add(ftQuery, BooleanClause.Occur.SHOULD);
        } catch (ParseException e) {
            log.debug("Full-text parse failed for '{}', falling back to escaped form", queryString);
        }

        // Exact / prefix match on keyword fields (code, uuid, externalId)
        for (String kf : new String[]{ FIELD_CODE, FIELD_UUID, FIELD_EXTERNAL_ID }) {
            builder.add(new TermQuery(new Term(kf, queryString.toLowerCase(Locale.ROOT))),
                    BooleanClause.Occur.SHOULD);
            builder.add(new PrefixQuery(new Term(kf, queryString.toUpperCase(Locale.ROOT))),
                    BooleanClause.Occur.SHOULD);
            builder.add(new PrefixQuery(new Term(kf, queryString.toLowerCase(Locale.ROOT))),
                    BooleanClause.Occur.SHOULD);
        }

        BooleanQuery query = builder.build();
        if (query.clauses().isEmpty()) {
            return new MultiFieldQueryParser(fullTextFields, analyzer).parse(escaped);
        }
        return query;
    }

    /** Convert a {@link TaxonomyNode} to a flat DTO (no children). */
    private TaxonomyNodeDto toFlatDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setUuid(node.getUuid());
        dto.setNameEn(node.getNameEn());
        dto.setNameDe(node.getNameDe());
        dto.setDescriptionEn(node.getDescriptionEn());
        dto.setDescriptionDe(node.getDescriptionDe());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
        dto.setDataset(node.getDataset());
        dto.setExternalId(node.getExternalId());
        dto.setSource(node.getSource());
        dto.setReference(node.getReference());
        dto.setSortOrder(node.getSortOrder());
        dto.setState(node.getState());
        return dto;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
