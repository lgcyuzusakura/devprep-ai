package com.devprep.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devprep")
public class DevPrepProperties {
    private String root = "..";
    private String seedFile;
    private String learningFile = "classpath:seed/learning.json";
    private String localLearningFile;
    private String resourcesFile;
    private String configFile;
    private long maxBodyBytes = 1_000_000;
    private long modelCacheSeconds = 300;

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
    public String getSeedFile() { return seedFile; }
    public void setSeedFile(String seedFile) { this.seedFile = seedFile; }
    public String getLearningFile() { return learningFile; }
    public void setLearningFile(String learningFile) { this.learningFile = learningFile; }
    public String getLocalLearningFile() { return localLearningFile; }
    public void setLocalLearningFile(String localLearningFile) { this.localLearningFile = localLearningFile; }
    public String getResourcesFile() { return resourcesFile; }
    public void setResourcesFile(String resourcesFile) { this.resourcesFile = resourcesFile; }
    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }
    public long getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public long getModelCacheSeconds() { return modelCacheSeconds; }
    public void setModelCacheSeconds(long modelCacheSeconds) { this.modelCacheSeconds = modelCacheSeconds; }
}
