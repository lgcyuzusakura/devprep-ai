package com.devprep.ai.api;

import com.devprep.ai.data.ResumeRepository;
import com.devprep.ai.service.ResumeDocuments;
import com.devprep.ai.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeRepository repository;
    private final ResumeService service;
    private final ResumeDocuments documents;
    public ResumeController(ResumeRepository repository,ResumeService service,ResumeDocuments documents){this.repository=repository;this.service=service;this.documents=documents;}
    @GetMapping public Object list(){return Map.of("workspaces",repository.list());}
    @GetMapping("/{id}") public Object detail(@PathVariable String id){return Map.of("workspace",repository.require(id),"versions",repository.versions(id));}
    @PostMapping public Object create(@RequestBody JsonNode body){return service.save(null,body);}
    @PostMapping("/{id}") public Object save(@PathVariable String id,@RequestBody JsonNode body){return service.save(id,body);}
    @PostMapping("/{id}/delete") public Object delete(@PathVariable String id,@RequestBody JsonNode body){repository.delete(id,ResumeService.revision(body));return Map.of("deleted",true);}
    @PostMapping("/{id}/analyze") public Object analyze(@PathVariable String id,@RequestBody JsonNode body){return service.analyze(id,body);}
    @PostMapping("/{id}/rewrite") public Object rewrite(@PathVariable String id,@RequestBody JsonNode body){return service.rewrite(id,body);}
    @PostMapping("/{id}/draft") public Object draft(@PathVariable String id,@RequestBody JsonNode body){return service.draft(id,body);}
    @PostMapping("/{id}/expand") public Object expand(@PathVariable String id,@RequestBody JsonNode body){return service.expand(id,body);}
    @PostMapping("/{id}/versions/{versionId}") public Object edit(@PathVariable String id,@PathVariable String versionId,@RequestBody JsonNode body){return service.edit(id,versionId,body);}
    @PostMapping("/extract") public Object extract(@RequestBody JsonNode body){
        if(!body.path("filename").isTextual()||!body.path("content").isTextual())throw ApiException.badRequest("invalid_resume_file","缺少文件名或内容。");
        return Map.of("text",documents.extract(body.path("filename").asText(),body.path("content").asText()));
    }
    @GetMapping("/{id}/versions/{versionId}/docx") public ResponseEntity<byte[]> docx(@PathVariable String id,@PathVariable String versionId){
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=resume.docx")
                .header("Content-Type","application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .body(documents.docx(repository.version(id,versionId).markdown(),repository.version(id,versionId).layout()));
    }
}
