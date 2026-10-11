# conv-ae: S2 (b) tests and (c) docs/examples (ktm-lang-6e)

Rebuild the branch after a converter change (from feat/conversion d3d514e originals, REF=d3d514e):
  ~/opensource/ktm-scratch/s2/regen-raw.sh /tmp/raw && ~/opensource/ktm-scratch/s2/replay.sh /tmp/raw
Commits: the raw converter output, the scripted fixes (s2/fix-*.py: test module, name clashes,
one import line, old names in comments), then the manual patches s2/manual/*.patch (dropped
stdlib names; codes and runtime-internal tests; docs prose; fixtures; example comments; no %D/%T/%F;
own names next to the stdlib; tests after a build; libc rules on the stdlib libc; no stdlib redeclaration;
error-text tests; embedded programs; library guide, reference and LSP; example runner), then
docs/tools/build.py regenerates the pages.
Verified on d3d514e (--sqlite build): all 417 numbered tests pass JIT and AOT (420-443
included), LSP 68/68, docs check-examples 106/106, docs/examples/run.sh 3/3.
Left: nothing for S2 (b)/(c); the converter is no longer needed by this branch after the merge.
Deleted before the final merge.
