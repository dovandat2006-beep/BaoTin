package com.baotin.auth.security;

import java.time.Clock;
import com.baotin.auth.api.ApiFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Component
public class AttemptLimits implements AutoCloseable {
    private final JdbcTemplate jdbc;private final Clock clock;
    private final HikariDataSource dataSource;
    public AttemptLimits(@Value("${spring.datasource.url}") String url,@Value("${spring.datasource.username}") String user,@Value("${spring.datasource.password}") String password,Clock clock){
        // Independent autocommit pool keeps counters durable on caller rollback and
        // avoids REQUIRES_NEW exhausting the same pool held by login transactions.
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);config.setPoolName("AuthAttemptLimits");config.setMaximumPoolSize(4);config.setMinimumIdle(1);config.setConnectionTimeout(2000);
        dataSource=new HikariDataSource(config);jdbc=new JdbcTemplate(dataSource);this.clock=clock;
    }
    public void check(String category,String principal,int limit,int seconds){
        long window=clock.instant().getEpochSecond()/seconds;
        int attempts=jdbc.queryForObject("INSERT INTO auth.rate_limits(bucket_key,window_id,attempts) VALUES (?,?,1) ON CONFLICT(bucket_key,window_id) DO UPDATE SET attempts=auth.rate_limits.attempts+1 RETURNING attempts",Integer.class,AuthCrypto.hash(category+":"+principal),window);
        if(attempts>limit)throw new ApiFailure(429,"RATE_LIMITED","Too many attempts; retry after the current window");
    }
    @Override public void close(){dataSource.close();}
}
