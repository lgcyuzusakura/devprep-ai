package com.devprep.ai.api;

import com.devprep.ai.data.JobRepository;
import com.devprep.ai.service.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobRepository repository;private final JobService service;private final JobSources sources;private final ResumeDocuments documents;private final ObjectMapper mapper;
    public JobController(JobRepository repository,JobService service,JobSources sources,ResumeDocuments documents,ObjectMapper mapper){this.repository=repository;this.service=service;this.sources=sources;this.documents=documents;this.mapper=mapper;}
    @GetMapping public Object list(){return Map.of("jobs",repository.jobs(),"profile",repository.profile(),"sources",sources.links());}
    @PostMapping("/profile") public Object profile(@RequestBody JsonNode input){return service.saveProfile(input);}
    @PostMapping("/sources/preview") public Object preview(@RequestBody JsonNode input){return Map.of("jobs",sources.preview(JobService.text(input,"url",2048,true)));}
    @GetMapping("/{id}") public Object detail(@PathVariable String id){return Map.of("job",repository.job(id),"materials",repository.materials(id),"events",repository.events(id));}
    @PostMapping public Object create(@RequestBody JsonNode input){return service.saveJob(null,input);}
    @PostMapping("/{id}") public Object save(@PathVariable String id,@RequestBody JsonNode input){return service.saveJob(id,input);}
    @PostMapping("/{id}/status") public Object status(@PathVariable String id,@RequestBody JsonNode input){return service.status(id,input);}
    @PostMapping("/{id}/delete") public Object delete(@PathVariable String id,@RequestBody JsonNode input){repository.delete(id,JobService.integer(input,"revision",0));return Map.of("deleted",true);}
    @PostMapping("/{id}/generate") public Object generate(@PathVariable String id,@RequestBody JsonNode input){return service.generate(id,input);}
    @PostMapping("/{id}/materials/{version}") public Object edit(@PathVariable String id,@PathVariable String version,@RequestBody JsonNode input){return service.edit(id,version,input);}
    @GetMapping("/{id}/materials/{version}/attachments") public Object attachments(@PathVariable String id,@PathVariable String version){return Map.of("attachments",repository.attachments(id,version));}
    @PostMapping("/{id}/materials/{version}/attachments") public Object attach(@PathVariable String id,@PathVariable String version,@RequestBody JsonNode input){service.attach(id,version,input);return attachments(id,version);}
    @PostMapping("/{id}/materials/{version}/attachments/{attachment}/delete") public Object remove(@PathVariable String id,@PathVariable String version,@PathVariable String attachment){repository.removeAttachment(id,version,attachment);return attachments(id,version);}
    @GetMapping("/{id}/materials/{version}/export/{format}") public ResponseEntity<byte[]> export(@PathVariable String id,@PathVariable String version,@PathVariable String format) throws IOException {
        var material=repository.material(id,version);JsonNode content=material.content();
        String messages="招聘聊天开场白\n"+content.path("greeting").asText()+"\n\n岗位自我介绍\n"+content.path("self_introduction").asText()+"\n\n跟进草稿（请按实际沟通情况修改）\n"+content.path("follow_up").asText();
        byte[] bytes;String type,extension;
        switch(format) {
            case "docx" -> {bytes=documents.docx(content.path("markdown").asText());type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";extension="docx";}
            case "md" -> {bytes=content.path("markdown").asText().getBytes(StandardCharsets.UTF_8);type="text/markdown; charset=UTF-8";extension="md";}
            case "messages" -> {bytes=messages.getBytes(StandardCharsets.UTF_8);type="text/plain; charset=UTF-8";extension="txt";}
            case "zip" -> {
                var buffer=new ByteArrayOutputStream();
                try(var zip=new ZipOutputStream(buffer,StandardCharsets.UTF_8)) {
                    add(zip,"resume.docx",documents.docx(content.path("markdown").asText()));add(zip,"resume.md",content.path("markdown").asText().getBytes(StandardCharsets.UTF_8));
                    add(zip,"messages.txt",messages.getBytes(StandardCharsets.UTF_8));
                    // Snapshot, not the live job: editing the JD never relabels an older package.
                    add(zip,"job-and-version.json",mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("id",material.id(),"job_id",material.job_id(),"job",material.inputs().path("job"),"master_revision",material.inputs().path("profile").path("revision"),"model",material.model(),"created_at",material.created_at(),"report",content)));
                    int index=0;for(var item:repository.attachmentData(id,version)) {String name=item.get("filename").toString();add(zip,"attachments/"+(++index)+"-"+name,Base64.getDecoder().decode(item.get("content_base64").toString()));}
                }
                bytes=buffer.toByteArray();type="application/zip";extension="zip";
            }
            default -> throw ApiException.badRequest("invalid_export","支持Word、Markdown、话术和ZIP材料包。");
        }
        String company=material.inputs().path("job").path("company").asText("岗位"),title=material.inputs().path("job").path("title").asText("材料");
        String name=(company+"-"+title).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]","_");if(name.length()>90)name=name.substring(0,90);
        return ResponseEntity.ok().header("Content-Type",type).header("Content-Disposition",ContentDisposition.attachment().filename(name+"-"+material.id().substring(0,8)+"."+extension,StandardCharsets.UTF_8).build().toString()).body(bytes);
    }
    private static void add(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
}
