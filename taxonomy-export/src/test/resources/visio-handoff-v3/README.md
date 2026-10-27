# Synthetic VSDX acceptance fixture

`representative.visio.zip` is the retained pre-navigation-fix golden package generated from `VisioHandoffContractTest.fixtureDocument()`. `committedFixtureRetainsEveryPartExceptExplicitOpeningView` compares every uncompressed native part byte-for-byte against it, with one explicit, literal expected replacement for `visio/windows.xml`. The new opening view selects the overview and frames its shapes and connector captions rather than its context text. The bundle manifest may change only in the checksum of the changed VSDX. No graph, source, master, glue or hyperlink part is excluded from the comparison.

`SHA256SUMS` and `CONTENTS.sha256` continue to bind the original reference ZIP and its members. The `main` method now produces a current candidate package, not that historical ZIP. Same-source byte reproducibility across collection order, loss order and host timezones remains covered by the existing separate tests. The normal commit-bound CI report identifies which source revision passed these tests.

From this fixture directory, verify the committed ZIP and then its extracted members in a fresh directory:

```sh
sha256sum -c SHA256SUMS
unzip -q representative.visio.zip -d verified-members
(cd verified-members && sha256sum -c ../CONTENTS.sha256)
```

The profile is `visio-2012-opc-supported-subset-v3`, with eight embedded original native masters, attached one-dimensional connectors, editable context blocks and a complete original-requirement page.

All authority/review/source values are synthetic. In particular the all-zero `authoritativeCommit` is sample data, **not** a release source SHA or a completed product-certification record.

| Object | Fixture value |
| --- | --- |
| `CP-01` | Capability, score 0.9, anchor/impact selected, review `CONFIRMED` |
| `BP-02` | Business process, score 0.7, impact selected, same Unicode display name as `CP-01` |
| `CS-03` | Core service, score 0.4, outside the impact selection |
| `relation/α` | `CP-01` → `BP-02`, `SUPPORTS`, score 0.8 |
| `relation/β` | `BP-02` → `CS-03`, `USES`, score 0.6 |
| Primary page | Three elements and both connectors, with editable source context |
| Impact page | `CP-01`, `BP-02` and `relation/α` |
| Evidence page | Full recorded explanations and complete labels, linked from their graph objects |
| Requirement page | Complete original requirement text, historical author/date, export actor/date and software identity |
| Editing link | Literal `https://taxonomy.example.org/projects/7/requirements/42`; reserved example data, not a live target |

Names include accents, Japanese characters, XML delimiters and an emoji. The human rationale deliberately begins with a formula-like string; tests ensure it is emitted as a literal value without an `F` formula attribute.

The automated secondary consumer is Apache POI XDGF 5.5.1 with XMLBeans 5.3.0 and the full Visio schema JAR. All four pages load and render with Java 25 on Linux. The fixture contains 16 rendered shapes and six connector endpoint records. Observed previews lack Japanese/emoji glyphs and arrowheads; successful POI loading/rendering does not certify visual fidelity or Microsoft Visio edit/save behavior.

Microsoft Visio desktop evidence is **pending**. Follow the [profile and acceptance procedure](../../../../../docs/dev/VISIO_HANDOFF_PROFILE.md) for a candidate release SHA. Retain the original fixture, edited output, exact source/version identities, checksums and screenshots with the result record. No desktop results are pre-filled in this fixture.
