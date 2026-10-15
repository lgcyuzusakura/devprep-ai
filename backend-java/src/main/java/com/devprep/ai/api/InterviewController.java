package com.devprep.ai.api;

import com.devprep.ai.data.InterviewRepository;
import com.devprep.ai.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewRepository repository;
    private final InterviewService service;
    public InterviewController(InterviewRepository repository,InterviewService service) {this.repository=repository;this.service=service;}
    @GetMapping public Object list() {return Map.of("sessions",repository.list());}
    @GetMapping("/catalog") public Object catalog() {return service.catalog();}
    @PostMapping public Object start(@RequestBody JsonNode body) {return service.start(body);}
    @GetMapping("/{id}") public Object detail(@PathVariable String id) {return service.detail(id);}
    @PostMapping("/{id}/answer") public Object answer(@PathVariable String id,@RequestBody JsonNode body) {return service.answer(id,body);}
    @PostMapping("/{id}/skip") public Object skip(@PathVariable String id,@RequestBody JsonNode body) {return service.skip(id,body);}
    @PostMapping("/{id}/finish") public Object finish(@PathVariable String id,@RequestBody JsonNode body) {return service.finish(id,body);}
    @PostMapping("/{id}/run") public Object run(@PathVariable String id,@RequestBody JsonNode body) {return service.run(id,body);}
    @PostMapping("/{id}/delete") public Object delete(@PathVariable String id,@RequestBody JsonNode body) {service.delete(id,body);return Map.of("deleted",true);}
    @GetMapping("/{id}/report.md") public ResponseEntity<byte[]> report(@PathVariable String id) {
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=interview-report.md")
                .header("Content-Type","text/markdown; charset=utf-8").body(service.markdown(id).getBytes(StandardCharsets.UTF_8));
    }
}
