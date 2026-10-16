package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Only fixed, public ATS read endpoints. Arbitrary saved URLs are never fetched by the server. */
@Service
public class JobSources {
    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public JobSources(ObjectMapper mapper){this.mapper=mapper;}
    public List<Map<String,String>> links() {return List.of(
            Map.of("name","BOSS直聘","url","https://www.zhipin.com/","hint","登录后查看岗位，复制JD或用浏览器助手导入"),
            Map.of("name","牛客招聘","url","https://www.nowcoder.com/jobs","hint","校招与实习"),
            Map.of("name","猎聘","url","https://www.liepin.com/","hint","社招岗位"),
            Map.of("name","智联招聘","url","https://www.zhaopin.com/","hint","综合招聘"),
            Map.of("name","前程无忧","url","https://www.51job.com/","hint","综合招聘"),
            Map.of("name","LinkedIn","url","https://www.linkedin.com/jobs/","hint","海外与跨国岗位"));}
    public List<ObjectNode> preview(String url) {
        URI target=endpoint(url);JsonNode data;
        try {data=mapper.readTree(fetch(target));} catch(ApiException ex){throw ex;} catch(Exception ex){throw new ApiException(502,"job_source_unavailable","招聘源暂时无法读取，可以直接粘贴JD；没有创建任何投递记录。");}
        String provider=target.getHost();List<ObjectNode> result=new ArrayList<>();
        JsonNode rows=provider.equals("api.lever.co")?data:data.path("jobs");
        if(!rows.isArray())throw ApiException.badRequest("invalid_job_source","没有找到公开的岗位列表，请核对公司招聘页链接。");
        for(JsonNode row:rows) {
            if(result.size()>=200)break;
            String title,link,description,location;
            if(provider.equals("api.lever.co")) {
                title=row.path("text").asText();link=row.path("hostedUrl").asText();location=row.path("categories").path("location").asText();
                StringBuilder text=new StringBuilder(row.path("descriptionPlain").asText(row.path("description").asText()));
                for(JsonNode item:row.path("lists"))text.append("\n").append(item.path("text").asText()).append("\n").append(item.path("content").asText());
                description=text+"\n"+row.path("additionalPlain").asText();
            } else if(provider.equals("boards-api.greenhouse.io")) {
                title=row.path("title").asText();link=row.path("absolute_url").asText();location=row.path("location").path("name").asText();description=row.path("content").asText();
            } else {
                if(!row.path("isListed").asBoolean(true))continue;
                title=row.path("title").asText();link=row.path("jobUrl").asText();location=row.path("location").asText();description=row.path("descriptionPlain").asText();
            }
            try {JobService.validateLink(link);}catch(ApiException ex){continue;}
            if(title.isBlank()||link.isBlank())continue;
            String company=URI.create(url).getPath().split("/")[1];
            ObjectNode node=mapper.createObjectNode().put("title",limit(plain(title),160)).put("company",company).put("url",link).put("location",limit(plain(location),160))
                    .put("jd",limit(plain(description),20000)).put("salary","").put("notes","来源："+url).put("preferences","");
            result.add(node);
        }
        return result;
    }
    public URI endpoint(String url) {
        JobService.validateLink(url);
        try {
            URI input=URI.create(url);String host=input.getHost().toLowerCase(Locale.ROOT);String[] parts=input.getPath().split("/");
            if(parts.length<2||!parts[1].matches("[a-zA-Z0-9_-]{1,100}"))throw new IllegalArgumentException();
            String slug=parts[1];
            return switch(host) {
                case "jobs.lever.co" -> URI.create("https://api.lever.co/v0/postings/"+slug+"?mode=json");
                case "boards.greenhouse.io","job-boards.greenhouse.io" -> URI.create("https://boards-api.greenhouse.io/v1/boards/"+slug+"/jobs?content=true");
                case "jobs.ashbyhq.com" -> URI.create("https://api.ashbyhq.com/posting-api/job-board/"+slug);
                default -> throw new IllegalArgumentException();
            };
        } catch(Exception ex){throw ApiException.badRequest("unsupported_job_source","公开列表导入支持Lever、Greenhouse、Ashby公司招聘页；其他网站请使用粘贴JD或浏览器助手。");}
    }
    protected String fetch(URI target) throws Exception {
        // Fixed hosts + HTTPS certificate validation + no redirects; still reject nonpublic resolution.
        for(InetAddress address:InetAddress.getAllByName(target.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())throw new IllegalArgumentException();
        var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).header("Accept","application/json").header("User-Agent","DevPrep-JobWorkspace/1.0").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()!=200)throw new IllegalStateException();
            byte[] body=stream.readNBytes(3_000_001);if(body.length>3_000_000)throw new IllegalStateException();
            return new String(body,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static String plain(String value) {return value.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>","").replaceAll("(?i)<(?:br|/p|/div|/li)[^>]*>","\n").replaceAll("<[^>]*>","").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'").replace("&amp;","&").strip();}
    private static String limit(String value,int length) {return value.length()>length?value.substring(0,length):value;}
}
