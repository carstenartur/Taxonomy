# Copilot review corrections and stacked merge

The user authorizes correcting the open reviews and merging PRs #1084–#1087.
Keep #1075 open: synthetic contracts do not constitute Sparx product evidence.

1. On #1084, reproduce broken package endpoint references and inconsistent
   element/relation mappings. Preserve the documented EA transport types and
   require their declared canonical meaning to agree before serialization.
2. Carry the correction forward into #1085. Restrict Sparx relation remaps in
   the UI and server; handle missing profile metadata without an exception.
3. On #1086, retain localized browser titles while exports get readable titles;
   clarify the separate browser/Docker acceptance requirements.
4. Carry all corrections into #1087, including its extracted shared validator.
   Run focused regressions, then the mandatory full Maven gate and relevant CI.
5. Reply to and resolve corrected review threads. Merge in dependency order,
   retaining ancestry and checking each expected head SHA and CI status.
6. Record an honest Word feature/quality assessment and distinguish remaining
   implementable #1075 contracts from actual EA/PCS compatibility execution.

Validation is evidence based: reproducing tests must fail before fixes; no
test exclusions, weakened baselines or synthetic product compatibility claims.
