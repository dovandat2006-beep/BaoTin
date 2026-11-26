package com.baotin.report.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="report.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class ReportConfiguration {}
