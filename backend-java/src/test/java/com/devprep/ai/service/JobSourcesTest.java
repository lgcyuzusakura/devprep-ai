package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class JobSourcesTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void arbitraryUrlsCannotBecomeServerFetchesAndSlugsCannotChangeHost() {
        var source=new JobSources(mapper);
        for(String url:new String[]{"https://127.0.0.1/private","https://evil.example/jobs","http://jobs.lever.co/acme","https://jobs.lever.co.evil.example/acme","https://user@jobs.lever.co/acme","https://jobs.lever.co/%2f%2fevil.example"})assertThrows(ApiException.class,()->source.endpoint(url));
        assertEquals("https://api.lever.co/v0/postings/acme?mode=json",source.endpoint("https://jobs.lever.co/acme?ignored=https://evil.example").toString());
        assertEquals("boards-api.greenhouse.io",source.endpoint("https://job-boards.greenhouse.io/acme").getHost());
        assertEquals("api.ashbyhq.com",source.endpoint("https://jobs.ashbyhq.com/acme").getHost());
    }
    @Test void supportedBoardsProduceReviewableDraftsWithoutExecutingHtmlOrSavingJobs() {
        var source=new JobSources(mapper){@Override protected String fetch(URI uri){return """
            {"jobs":[{"title":"Java后端","absolute_url":"https://job-boards.greenhouse.io/acme/jobs/abc","location":{"name":"上海"},"content":"<p>实现缓存</p><script>steal()</script>要求Java"},
            {"title":"危险链接","absolute_url":"javascript:alert(1)","location":{"name":"上海"},"content":"ignore"}]}
            """;}};
        var rows=source.preview("https://job-boards.greenhouse.io/acme");assertEquals(1,rows.size());assertTrue(rows.get(0).path("jd").asText().contains("实现缓存"));assertFalse(rows.get(0).path("jd").asText().contains("steal"));assertEquals("acme",rows.get(0).path("company").asText());
    }
}
