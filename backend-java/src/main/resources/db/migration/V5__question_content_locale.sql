ALTER TABLE questions ADD COLUMN content_locale TEXT;

-- Built-in and already translated Chinese content remains available after upgrading
-- a persistent database. Non-Chinese source content stays excluded until translated.
UPDATE questions
SET content_locale = CASE
    WHEN LOWER(source_locale) LIKE 'zh%' THEN 'zh-CN'
    ELSE source_locale
END
WHERE content_locale IS NULL;

CREATE INDEX idx_questions_content_locale ON questions(content_locale);
