package com.baotin.auth.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@EnableScheduling
public class DeliveryRetention {
    private final IdentityStore store;private final EmailMfa emailMfa;public DeliveryRetention(IdentityStore store,EmailMfa emailMfa){this.store=store;this.emailMfa=emailMfa;}
    @Scheduled(fixedDelay=3600000,initialDelay=3600000) @Transactional
    public void clean(){
        store.jdbc().update("DELETE FROM auth.mail_outbox WHERE created_at<now()-interval '1 day' AND (lease_until IS NULL OR lease_until<now())");
        store.jdbc().update("DELETE FROM auth.rate_limits WHERE created_at<now()-interval '2 days'");
        store.jdbc().update("DELETE FROM auth.oauth_tickets WHERE expires_at<now()-interval '1 hour'");
        store.jdbc().update("DELETE FROM auth.oauth_requests WHERE expires_at<now()-interval '1 hour'");
        if(emailMfa.available())store.jdbc().update("DELETE FROM auth.email_mfa_challenges WHERE expires_at<now()-interval '1 hour'");
    }
}
