package com.baotin.auth.application;

import com.baotin.auth.security.SigningKeys;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="auth.mail.enabled",havingValue="true")
public class MailDelivery {
    private final DeliveryQueue queue;private final SigningKeys keys;private final JavaMailSender sender;private final String from;private final SecurityNotices notices;
    public MailDelivery(DeliveryQueue queue,SigningKeys keys,JavaMailSender sender,@Value("${auth.mail.from}") String from,SecurityNotices notices){
        if(!from.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Valid SMTP_FROM required when mail delivery enabled");
        this.queue=queue;this.keys=keys;this.sender=sender;this.from=from;this.notices=notices;
    }
    @Scheduled(fixedDelayString="${auth.mail.interval-ms:5000}",initialDelay=5000)
    public void deliver(){
        notices.materialize();var rows=queue.claim(DeliveryQueue.Kind.MAIL,10);
        for(var row:rows){
            UUID id=(UUID)row.get("id"),lease=(UUID)row.get("lease_id");int attempts=(Integer)row.get("attempts");
            try{
                String[] payload=new String(keys.decrypt((byte[])row.get("encrypted_payload")),StandardCharsets.UTF_8).split("\n",3);
                var message=sender.createMimeMessage();var helper=new org.springframework.mail.javamail.MimeMessageHelper(message,false,"UTF-8");helper.setFrom(from);helper.setTo(payload[0]);helper.setSubject(payload[1]);helper.setText(payload[2]);message.setHeader("Message-ID","<"+id+"@mail.baotin>");sender.send(message);
                queue.complete(DeliveryQueue.Kind.MAIL,id,lease);
            }catch(Exception e){queue.fail(DeliveryQueue.Kind.MAIL,id,lease,attempts);}
        }
    }
}
