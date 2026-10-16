package com.devprep.ai.auth;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name = "devprep.auth-enabled", havingValue = "true")
public class WorkspaceDatabaseConfiguration {
    @Bean(name = "physicalDataSource", destroyMethod = "close")
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource physicalDataSource(DataSourceProperties properties) {
        var source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        source.setSchema("public");
        return source;
    }
    @Bean @Primary
    DataSource dataSource(@Qualifier("physicalDataSource") DataSource physical) {
        return new WorkspaceDataSource(physical, WorkspaceContext::schema);
    }
}
