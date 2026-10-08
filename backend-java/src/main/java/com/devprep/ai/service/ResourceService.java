package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.ResourceRepository;
import com.devprep.ai.domain.ResourceItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResourceService {
    private final ResourceRepository repository;
    public ResourceService(ResourceRepository repository) { this.repository = repository; }
    public List<ResourceItem> list() {
        try { return repository.list(); }
        catch (ResourceRepository.InvalidResourceException ex) { throw new ApiException(500, "resources_invalid", ex.getMessage()); }
        catch (IllegalStateException ex) { throw new ApiException(500, "resources_unreadable", "公开资源目录暂时无法读取。"); }
    }
}
