# Lessons Learned

> **Read this when**: You hit a recurring bug, need to understand a past fix, or are working on JGit, DSL, or Hibernate areas.
> **This is a living document.** Copilot agents **must read it before starting work** in these areas and **must append new entries** when they discover non-obvious pitfalls, architectural constraints, or debugging insights that would save future agents significant time. Each entry should be concise, actionable, and include the date.

---

## JGit DfsBlockCache is a JVM-global singleton — pack names must be unique (2026-03-12)

**Problem:** `DfsBlockCache` is a **static singleton** shared across the entire JVM. It caches pack index data keyed by `(DfsRepositoryDescription name + pack file name)`. When multiple Spring test contexts create `HibernateObjDatabase` instances in the same JVM (which happens because `@SpringBootTest` caches and reuses contexts), a **static** `PACK_ID_COUNTER` generates identical pack names (`pack-1-INSERT`, `pack-2-INSERT`, …). The second context writes *different* pack data to the database under the same name, but `DfsBlockCache` serves the *stale* cached index from the first context. This causes `RefUpdate` to return `REJECTED_MISSING_OBJECT` because the cached index doesn't contain the new commit's object ID, which then corrupts the reftable state (`Invalid reftable file`).

**Fix:** The `packIdCounter` in `HibernateObjDatabase` is **per-instance** (not static), initialized with `System.nanoTime() & 0x7FFF_FFFF` to guarantee unique pack names across contexts.

**Key takeaway:** Any `DfsObjDatabase` implementation that can be instantiated multiple times in the same JVM **must** generate globally unique pack names to avoid `DfsBlockCache` collisions.

---

## HibernateRepository constructor must not call clearAll()/close() (2026-03-12)

**Problem:** Calling `objdb.clearAll()` + `refdb.close()` in the `HibernateRepository` constructor was intended to clean stale data on startup. However, `refdb.close()` leaves the `DfsReftableDatabase` in a state that cannot recover — subsequent reftable operations fail with `Invalid reftable file`.

**Fix:** Removed both calls. With `ddl-auto=create` in tests, tables are already recreated fresh. In production, persistent data is expected and should not be cleared.

---

## Spring test context caching + ddl-auto=create can cause subtle state issues (2026-03-12)

**Problem:** Spring Boot tests with `@SpringBootTest` cache application contexts for reuse across test classes. Combined with `spring.jpa.hibernate.ddl-auto=create` (which drops and recreates tables on each *new* context), this means:
- Context A creates tables and writes data
- Context B (different config) creates *new* tables, wiping Context A's data
- Context A is reused later — its beans still hold stale references to data that no longer exists

**Key takeaway:** Beans that maintain internal state tied to database contents (like JGit's `DfsBlockCache`) must handle table recreation gracefully. Use unique identifiers, avoid static mutable state, or use `@DirtiesContext` as a last resort.

---

## HSQLDB in-memory + Hibernate SessionFactory for JGit (2026-03-12)

The `DslGitRepository` uses `HibernateRepository` backed by the same `SessionFactory` that Spring Boot manages. JGit pack data is stored in `git_packs` (via `GitPackEntity`) and reftable data is stored as pack extensions. The `HibernatePackOutputStream.flush()` opens **its own Hibernate session** and commits independently — this is safe because HSQLDB's `READ_COMMITTED` isolation ensures new sessions can read committed data immediately.

---

## Never invent taxonomy node codes — use real codes from the Excel workbook (2026-03-13)

**Problem:** Tests (e.g., `captureProposalAccepted`) used invented codes like `CP-3` and `CR-5`. These do not exist in the taxonomy Excel workbook. The API returns `400 Bad Request`, the JavaScript promise chain fails silently, and Selenium times out after 15 seconds.

**Fix:** Always discover real codes at runtime by querying `GET /api/taxonomy`. Walk the returned tree to find nodes that actually exist. Real codes follow the `XX-XXXX` format (two uppercase letters, hyphen, four digits) — e.g., `CP-1023`, `CR-1047` — but not all four-digit numbers exist. The Excel workbook is the only source of truth.

**Key takeaway:** Never hardcode specific taxonomy codes in tests or documentation examples. Always query the live taxonomy and use what comes back.
