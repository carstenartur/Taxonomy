package com.taxonomy.preferences.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PreferencesGitRepository} using an in-memory JGit repository.
 * No Spring context or database is required.
 */
class PreferencesGitRepositoryTest {

    private PreferencesGitRepository repo;

    private static final String SAMPLE_JSON = """
            {"llm.rpm":5,"llm.timeout.seconds":30,"rate-limit.per-minute":10}
            """.strip();

    private static final String SAMPLE_JSON_V2 = """
            {"llm.rpm":10,"llm.timeout.seconds":60,"rate-limit.per-minute":5}
            """.strip();

    @BeforeEach
    void setUp() {
        repo = new PreferencesGitRepository();
    }

    @Test
    void initialReadHeadReturnsNull() throws IOException {
        assertNull(repo.readHead(), "readHead() should return null when no commits exist");
    }

    @Test
    void initialHistoryIsEmpty() throws IOException {
        assertTrue(repo.getHistory().isEmpty(), "getHistory() should return empty list initially");
    }

    @Test
    void commitAndReadBack() throws IOException {
        String commitId = repo.commit(SAMPLE_JSON, "tester", "initial preferences");

        assertNotNull(commitId);
        assertEquals(40, commitId.length(), "Git SHA should be 40 hex chars");

        String readBack = repo.readHead();
        assertEquals(SAMPLE_JSON, readBack);
    }

    @Test
    void secondCommitUpdatesHead() throws IOException {
        repo.commit(SAMPLE_JSON, "tester", "initial");
        repo.commit(SAMPLE_JSON_V2, "tester", "updated rpm");

        assertEquals(SAMPLE_JSON_V2, repo.readHead());
    }

    @Test
    void historyShowsAllCommitsNewestFirst() throws IOException {
        repo.commit(SAMPLE_JSON, "alice", "initial");
        repo.commit(SAMPLE_JSON_V2, "bob", "updated");

        List<PreferencesCommit> history = repo.getHistory();
        assertEquals(2, history.size());

        assertEquals("bob", history.get(0).author());
        assertEquals("updated", history.get(0).message());

        assertEquals("alice", history.get(1).author());
        assertEquals("initial", history.get(1).message());
    }

    @Test
    void commitNullAuthorUsesDefault() throws IOException {
        String commitId = repo.commit(SAMPLE_JSON, null, "system init");
        assertNotNull(commitId);
        List<PreferencesCommit> history = repo.getHistory();
        assertEquals(1, history.size());
        assertEquals("taxonomy", history.get(0).author());
    }

    @Test
    void isDatabaseBackedReturnsFalseForInMemory() {
        assertFalse(repo.isDatabaseBacked());
    }
}
