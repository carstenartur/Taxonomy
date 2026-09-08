# Independent exchange fixtures

`strictdoc.reqif` and `eclipse-rmf.reqif` are the unmodified semantic samples
`sample2_sdoc.reqif` and `sample3_eclipse_rmf.reqif` from
https://github.com/strictdoc-project/reqif/tree/b36694051d6b0502682bcd8f83567d4e1f387d1b/tests/integration/examples/04_convert_reqif_to_json
distributed under the accompanying Apache-2.0 `LICENSE.reqif.txt`.
Their source-tool headers identify independent StrictDoc and Eclipse RMF producers.

`polarion.reqif` and `valid-tool.reqif` are also unmodified upstream samples from
that pinned ReqIF repository. The StrictDoc sample lacks a required LAST-CHANGE,
the Polarion sample repeats an XML ID, and `valid-tool.reqif` contains an unresolved
IDREF. These three are deliberate negative schema tests, not silently fixed input.

`archi-sample.xml` is `tests/org.opengroup.archimate.xmlexchange.tests/testdata/Sample1.xml`
from https://github.com/archimatetool/archi/tree/f757b06d5f75b5565786bb0679c75450cc3a577f
under the MIT licence retained in `/archimate-3.1/LICENSE.archi.txt`.

`archi-bendpoints.xml` is the view-connection/bendpoint example from the same pinned
Archi test-data directory and licence. Product CI additionally imports the Taxonomy
export into the official Archi 5.10.0 release and exports it again.

`strictdoc-product.sdoc` is an original project-owned acceptance fixture. Product
CI uses StrictDoc 0.29.0's native export and import commands with stable MIDs.
This generated product path is separate from the intentionally invalid historical
StrictDoc ReqIF sample. The CI evidence records actual output hashes and the known
regeneration of declaration, hierarchy and relation IDs by the product.

Golden-file tests establish the supported format contract. They do not by themselves
certify an installed product version or claim that a remote system accepted an export.
