package com.devprep.ai.data;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuestionContentLocaleMigrationTest {
    @Test
    void upgradeBackfillsContentLanguageWithoutReplacingSourceOrAttempts() {
        String url = "jdbc:h2:mem:content-locale-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("4").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.update("""
                INSERT INTO knowledge_points(id,domain,title,summary,prerequisites_json,created_at)
                VALUES('topic','测试','测试知识点','测试摘要','[]','2026-10-08T04:00:00Z')
                """);
        for (String locale : List.of("zh-CN", "zh", "en")) {
            jdbc.update("""
                    INSERT INTO questions(id,knowledge_point_id,question_type,stem,options_json,answer_json,
                                          explanation,rubric_json,source_locale)
                    VALUES(?,'topic','short_answer',?,'[]','"Original answer"','原解释','[]',?)
                    """, locale, "原题 " + locale, locale);
        }
        jdbc.update("""
                INSERT INTO questions(id,knowledge_point_id,question_type,stem,options_json,answer_json,
                                      explanation,rubric_json)
                VALUES('unknown','topic','short_answer','Unknown question','[]','"Original answer"','原解释','[]')
                """);
        jdbc.update("""
                INSERT INTO attempts(id,question_id,knowledge_point_id,answer_json,score,is_correct,rubric_hits_json,created_at)
                VALUES('attempt','en','topic','"Original response"',1,1,'[]','2026-10-08T04:00:00Z')
                """);

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        assertEquals("zh-CN", jdbc.queryForObject("SELECT content_locale FROM questions WHERE id='zh-CN'", String.class));
        assertEquals("zh-CN", jdbc.queryForObject("SELECT content_locale FROM questions WHERE id='zh'", String.class));
        assertEquals("en", jdbc.queryForObject("SELECT content_locale FROM questions WHERE id='en'", String.class));
        assertNull(jdbc.queryForObject("SELECT content_locale FROM questions WHERE id='unknown'", String.class));
        assertEquals("en", jdbc.queryForObject("SELECT source_locale FROM questions WHERE id='en'", String.class));
        assertEquals("原题 en", jdbc.queryForObject("SELECT stem FROM questions WHERE id='en'", String.class));
        assertEquals("\"Original response\"", jdbc.queryForObject("SELECT answer_json FROM attempts WHERE id='attempt'", String.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM questions
                WHERE LOWER(COALESCE(content_locale,source_locale,'')) LIKE 'zh%'
                """, Integer.class));
    }
}
