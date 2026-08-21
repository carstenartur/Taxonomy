renderer='taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleDocxRenderer.java'
grep -Fq \
  'CTTblPr properties = tableXml.isSetTblPr() ? tableXml.getTblPr() : tableXml.addNewTblPr();' \
  "$renderer"
perl -0pi -e \
  's/        CTTblPr properties = tableXml\.isSetTblPr\(\) \? tableXml\.getTblPr\(\) : tableXml\.addNewTblPr\(\);/        CTTblPr properties = tableXml.getTblPr();\n        if (properties == null) {\n            properties = tableXml.addNewTblPr();\n        }/' \
  "$renderer"
test "$(grep -Fc 'paragraph.setKeepNext(true);' "$renderer")" -eq 2
perl -0pi -e \
  's/        paragraph\.setKeepNext\(true\);/        if (paragraph.getCTP().getPPr() == null) {\n            paragraph.getCTP().addNewPPr();\n        }\n        paragraph.setKeepNext(true);/g' \
  "$renderer"

report_service='taxonomy-app/src/main/java/com/taxonomy/architecture/decision/DecisionRationaleReportService.java'
grep -Fq 'return value == null ? "" : String.valueOf(value).strip();' "$report_service"
sed -i \
  's/return value == null ? "" : String.valueOf(value)\.strip();/return value == null ? "" : String.valueOf(value);/' \
  "$report_service"

old_dir='taxonomy-app/src/main/java/com/taxonomy/architecture/decision'
portfolio_dir='taxonomy-app/src/main/java/com/taxonomy/portfolio/report'
mkdir -p "$portfolio_dir"
mv "$old_dir/DecisionRationaleSnapshotReportService.java" "$portfolio_dir/"
mv "$old_dir/DecisionRationaleSnapshotReportController.java" "$portfolio_dir/"
python3 - \
  "$portfolio_dir/DecisionRationaleSnapshotReportService.java" \
  "$portfolio_dir/DecisionRationaleSnapshotReportController.java" <<'PY'
from pathlib import Path
import sys

service = Path(sys.argv[1])
text = service.read_text(encoding='utf-8')
old = 'package com.taxonomy.architecture.decision;\n\n'
new = (
    'package com.taxonomy.portfolio.report;\n\n'
    'import com.taxonomy.architecture.decision.DecisionRationaleReport;\n'
    'import com.taxonomy.architecture.decision.DecisionRationaleReportService;\n'
)
if old not in text:
    raise SystemExit('Snapshot service package anchor not found')
service.write_text(text.replace(old, new, 1), encoding='utf-8')

controller = Path(sys.argv[2])
text = controller.read_text(encoding='utf-8')
new = (
    'package com.taxonomy.portfolio.report;\n\n'
    'import com.taxonomy.architecture.decision.DecisionRationaleReport;\n'
    'import com.taxonomy.architecture.decision.DecisionRationaleReportPlugin;\n'
)
if old not in text:
    raise SystemExit('Snapshot controller package anchor not found')
controller.write_text(text.replace(old, new, 1), encoding='utf-8')
PY

