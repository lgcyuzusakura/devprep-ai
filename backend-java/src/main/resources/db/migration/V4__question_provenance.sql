ALTER TABLE questions ADD COLUMN source_name TEXT;
ALTER TABLE questions ADD COLUMN source_url TEXT;
ALTER TABLE questions ADD COLUMN source_path TEXT;
ALTER TABLE questions ADD COLUMN license TEXT;
ALTER TABLE questions ADD COLUMN attribution TEXT;
ALTER TABLE questions ADD COLUMN revision TEXT;
ALTER TABLE questions ADD COLUMN source_sha256 TEXT;
ALTER TABLE questions ADD COLUMN source_locale TEXT;
ALTER TABLE questions ADD COLUMN content_format TEXT NOT NULL DEFAULT 'markdown';
ALTER TABLE questions ADD COLUMN assessment_mode TEXT NOT NULL DEFAULT 'rubric';

ALTER TABLE attempts ADD COLUMN assessment_mode TEXT NOT NULL DEFAULT 'rubric';
ALTER TABLE attempts ADD COLUMN self_assessment BOOLEAN;

UPDATE questions q
SET source_name = (
    SELECT m.title FROM materials m WHERE m.id = q.source_material_id
)
WHERE source_name IS NULL AND source_material_id IS NOT NULL;
