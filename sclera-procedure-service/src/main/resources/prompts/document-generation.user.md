<!-- v1 — per-chunk user prompt for document-driven generation.
     Variables: {{templateName}}, {{instructions}}, {{chunkNo}}, {{chunkCount}},
     {{documentText}}. -->

Generate inspection checklist questions for the procedure "{{templateName}}"
from the document excerpt below (part {{chunkNo}} of {{chunkCount}}).

Author's instructions: {{instructions}}

Extract every inspectable requirement from this excerpt as a question. Prefer
`YES_NO` for pass/fail checks, `INTEGER` for numeric readings (carry the unit
and any stated acceptable range into `min`/`max`), and `SECTION` headings when
the document itself groups requirements. Set `sourceSpan` to the sentence or
phrase each question came from.

If the excerpt contains no inspectable requirements, return an empty list.

--- DOCUMENT EXCERPT ---
{{documentText}}
