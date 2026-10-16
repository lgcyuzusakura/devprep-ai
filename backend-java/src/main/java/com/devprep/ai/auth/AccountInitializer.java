package com.devprep.ai.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component @Order(1)
@ConditionalOnProperty(name = "devprep.auth-enabled", havingValue = "true")
public class AccountInitializer implements ApplicationRunner {
    private final UserAccounts accounts;
    public AccountInitializer(UserAccounts accounts) { this.accounts = accounts; }
    @Override public void run(ApplicationArguments args) throws Exception { accounts.initialize(); }
}
