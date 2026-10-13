-- Descriptive metadata on the template identity (docs/procedures-v3.html
-- DetailsTab): categorization, filing and scheduling. Identity, not content —
-- editing these never creates a version. All free text except availability
-- (PROPERTY | ORG); vocabularies are the organization's to grow.
ALTER TABLE procedure_template
    ADD COLUMN category     VARCHAR(50),
    ADD COLUMN sub_category VARCHAR(50),
    ADD COLUMN frequency    VARCHAR(30),
    ADD COLUMN priority     VARCHAR(20),
    ADD COLUMN code         VARCHAR(30),
    ADD COLUMN standard     VARCHAR(50),
    ADD COLUMN availability VARCHAR(20),
    ADD COLUMN folder       VARCHAR(50);
