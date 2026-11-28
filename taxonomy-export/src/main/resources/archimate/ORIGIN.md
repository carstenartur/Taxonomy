# ArchiMate endpoint constraints

The `endpoints` rows of `profile-v3.tsv` are derived from Archi's ArchiMate 3.2
relationship matrix as distributed by the EAM-Toolfamilie at commit
`783c4c01753a2d168256bdb245cba58a76d8e275`. They cover all ordered pairs of
the native element concepts supported by the Taxonomy workbench export.
Each row lists allowed native relationship names. This is a semantic check in
addition to the ArchiMate 3.1 interchange schema.

Derivation: select the source and target concepts produced by the Taxonomy
element mappings. For every ordered pair, expand the letters of the source
matrix using its key and remove the `Relationship` suffix. The remaining
profile rows define Taxonomy's chosen native projections.

- [Relationship matrix](https://github.com/Mitchel85/EAM-Toolfamilie/blob/783c4c01753a2d168256bdb245cba58a76d8e275/packages/core/archimate/relationships.xml)
- [Relationship key](https://github.com/Mitchel85/EAM-Toolfamilie/blob/783c4c01753a2d168256bdb245cba58a76d8e275/packages/core/archimate/relationships-keys.xml)
- [Upstream third-party notice for Archi and the EPL-2.0 data](https://github.com/Mitchel85/EAM-Toolfamilie/blob/783c4c01753a2d168256bdb245cba58a76d8e275/NOTICE.md)
- [Eclipse Public License 2.0](LICENSE-EPL-2.0.txt)

The Maven regression `ArchiMateEndpointMatrixTest` decompresses the unmodified
upstream XML and key from test resources, verifies their SHA-256 fingerprints,
and compares each ordered pair and its complete set of permitted relationships
with the mapping profile distributed by Taxonomy.
