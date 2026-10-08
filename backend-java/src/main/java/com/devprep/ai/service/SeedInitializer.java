package com.devprep.ai.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class SeedInitializer implements ApplicationRunner {
    private final SeedService seeds;
    public SeedInitializer(SeedService seeds) { this.seeds = seeds; }
    @Override
    public void run(ApplicationArguments args) { seeds.initialize(); }
}
