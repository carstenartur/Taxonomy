# Production repository-history dependency review

The ordinary clean Maven test selection at `76c0795adaa193fa6005e53abc708c148406e4ee`
reported one structural change: `app-composition/com.taxonomy.composition.backup`
to `workspace/com.taxonomy.workspace.backup` grew from 13 to 20 class-dependency pairs.
The other 26 architecture checks passed. The complete selection failed overall;
this review is not a claim that the production history acceptance has passed.

The root implementer and an independent read-only reviewer inspected the new
production assembly and the public workspace history API. The assembly creates
`GitHistoryBackupContributor`, `WorkspaceGitHistoryReferences` and the public
`GitHistoryCapture.Limits`; it uses their fenced capture inventory and documents.
Reference closure uses the bounded `DocumentLimits`/`DocumentVisitor` contract to
read the original captured Git documents. Original saved-editor, operation and
checkpoint bodies still come from `WorkspaceBackupContributor` projections.

These dependencies have the established application-composition-to-feature-owner
direction. They add no workspace-to-application dependency, no access to storage
library internals, no alternative Git parser, and no active restore authority.
The public visitor validates the captured commit, path, type, length and digest
and shares its document/byte bounds across the capture.

The reviewed baseline update therefore changes exactly the observed existing edge
from 13 to 20. No other baseline value or architecture assertion is changed. A
fresh normal Maven run must verify the updated baseline and the runtime tests.
