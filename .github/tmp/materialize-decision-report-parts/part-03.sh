old_controller="$old_dir/DecisionRationaleReportController.java"
versioning_dir='taxonomy-app/src/main/java/com/taxonomy/versioning/controller'
mkdir -p "$versioning_dir"
mv "$old_controller" "$versioning_dir/"
controller="$versioning_dir/DecisionRationaleReportController.java"
perl -0pi -e \
  's/package com\.taxonomy\.architecture\.decision;\n\n/package com.taxonomy.versioning.controller;\n\nimport com.taxonomy.architecture.decision.DecisionRationaleReport;\nimport com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;\nimport com.taxonomy.architecture.decision.DecisionRationaleReportService;\n/' \
  "$controller"
grep -Fq 'package com.taxonomy.versioning.controller;' "$controller"
grep -Fq \
  'import com.taxonomy.architecture.decision.DecisionRationaleReportService;' \
  "$controller"

help_controller='taxonomy-app/src/main/java/com/taxonomy/shared/controller/HelpController.java'
python3 - "$help_controller" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding='utf-8')
anchor = (
    '        new String[]{"DECISION_PIPELINE",         "🔬", '
    '"help.toc.DECISION_PIPELINE",             "help.audience.developers"},\n'
)
entry = (
    anchor
    + '        new String[]{"DECISION_RATIONALE_REPORT", "🧭", '
      '"help.toc.DECISION_RATIONALE_REPORT", "help.audience.everyone"},\n'
)
if anchor not in source:
    raise SystemExit('Decision-pipeline help metadada anchor not found')
if '"DECISION_RATIONALE_REPORT"' not in source:
    source = source.replace(anchor, entry, 1)
path.write_text(source, encoding='utf-8')
PY

messages='taxonomy-app/src/main/resources/i18n/messages.properties'
messages_de='taxonomy-app/src/main/resources/i18n/messages_de.properties'
grep -q '^help.toc.DECISION_RATIONALE_REPORT=' "$messages" \
  || printf '\nhelp.toc.DECISION_RATIONALE_REPORT=Decision rationale report\n' >> "$messages"
grep -q '^help.toc.DECISION_RATIONALE_REPORT=' "$messages_de" \
  || printf '\nhelp.toc.DECISION_RATIONALE_REPORT=Hierarchischer Entscheidungs- und Begründungsbericht\n' \
    >> "$messages_de"
