package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateNotFoundException;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Immutable submitted originals and sample outcomes, outside the published templates repository. */
@Component
final class DocumentTemplateCandidateStore implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Repository repository;
    private final HibernateGitStorage storage;

    @Autowired
    DocumentTemplateCandidateStore(HibernateRepositoryFactory factory) {
        storage = factory.open(new RepositoryName("taxonomy-document-template-candidates"));
        repository = storage.repository();
    }

    DocumentTemplateCandidateStore(Repository repository) {
        this.repository = repository;
        storage = null;
    }

    record Metadata(String templateId, String displayName, String baseVersion, String originalSha256,
                    String packageSha256) { }

    String save(Metadata metadata, byte[] original) throws IOException {
        synchronized (repository) {
            try (var inserter = repository.newObjectInserter()) {
                var tree = new TreeFormatter();
                tree.append("candidate.json", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, JSON.writeValueAsBytes(metadata)));
                tree.append("original.dotx", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, original));
                var commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(tree));
                var author = new PersonIdent("Template preflight", "template-preflight@taxonomy.local");
                commit.setAuthor(author);
                commit.setCommitter(author);
                commit.setMessage("Preflight " + metadata.templateId() + " " + java.util.UUID.randomUUID());
                var version = inserter.insert(commit);
                inserter.flush();
                publish(ref(metadata.templateId(), version.name()), version);
                return version.name();
            }
        }
    }

    Metadata metadata(String templateId, String version) throws IOException {
        return JSON.readValue(read(templateId, version, "candidate.json", 8192), Metadata.class);
    }

    byte[] original(String templateId, String version) throws IOException {
        return read(templateId, version, "original.dotx", OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES);
    }

    void certify(String templateId, String version, byte[] sample) throws IOException {
        synchronized (repository) {
            try (var inserter = repository.newObjectInserter()) {
                var blob = inserter.insert(Constants.OBJ_BLOB, sample);
                inserter.flush();
                publish(sampleRef(templateId, version), blob);
            }
        }
    }

    boolean certified(String templateId, String version) throws IOException {
        var sample = repository.exactRef(sampleRef(templateId, version));
        return sample != null && repository.getObjectDatabase().has(sample.getObjectId());
    }

    byte[] sample(String templateId, String version) throws IOException {
        metadata(templateId, version);
        var sample = repository.exactRef(sampleRef(templateId, version));
        if (sample == null) throw new TemplateNotFoundException(templateId, version);
        return repository.open(sample.getObjectId(), Constants.OBJ_BLOB).getBytes(OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES);
    }

    private void publish(String name, ObjectId object) throws IOException {
        var update = repository.updateRef(name);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(object);
        var result = update.update();
        if (result == RefUpdate.Result.NEW || result == RefUpdate.Result.NO_CHANGE) return;
        var current = repository.exactRef(name);
        if (current == null || !object.equals(current.getObjectId())) throw new IOException("Could not retain the candidate revision");
    }

    private static String sampleRef(String templateId, String version) {
        return Constants.R_TAGS + "preflight/" + templateId + "/" + version;
    }

    private byte[] read(String templateId, String version, String part, int maximum) throws IOException {
        DocumentTemplateGitRepository.validateTemplateId(templateId);
        if (version == null || !ObjectId.isId(version)) throw new IllegalArgumentException("Choose a complete candidate revision");
        var ref = repository.exactRef(ref(templateId, version));
        if (ref == null || !version.equals(ref.getObjectId().name())) throw new TemplateNotFoundException(templateId, version);
        try (var walk = new RevWalk(repository);
             var path = TreeWalk.forPath(repository, part, walk.parseCommit(ref.getObjectId()).getTree())) {
            if (path == null) throw new TemplateNotFoundException(templateId, version);
            return repository.open(path.getObjectId(0), Constants.OBJ_BLOB).getBytes(maximum);
        }
    }

    private static String ref(String templateId, String version) {
        return Constants.R_HEADS + templateId + "/" + version;
    }

    @Override
    public void close() {
        if (storage != null) storage.close();
    }
}
