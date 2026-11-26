package com.baotin.auth.application;

import com.baotin.auth.security.SigningKeys;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityNotices {
    private final IdentityStore store;private final SigningKeys keys;
    public SecurityNotices(IdentityStore store,SigningKeys keys){this.store=store;this.keys=keys;}
    @Transactional public void materialize(){
        var rows=store.jdbc().queryForList("SELECT n.id,n.user_id,n.event_type,u.email,u.email_verified FROM auth.security_notices n JOIN auth.users u ON u.id=n.user_id WHERE n.handled_at IS NULL ORDER BY n.created_at,n.id LIMIT 50 FOR UPDATE OF n SKIP LOCKED");
        for(var row:rows){
            if(row.get("email")!=null && Boolean.TRUE.equals(row.get("email_verified"))){
                String payload=row.get("email")+"\nBaoTin security alert\nAccount security event: "+row.get("event_type")+". If this was not you, contact the project operator immediately. No password, OTP or token is included in this alert.";
                store.jdbc().update("INSERT INTO auth.mail_outbox(id,notice_id,encrypted_payload) VALUES (?,?,?) ON CONFLICT(notice_id) DO NOTHING",java.util.UUID.randomUUID(),row.get("id"),keys.encrypt(payload.getBytes(StandardCharsets.UTF_8)));
            }
            store.jdbc().update("UPDATE auth.security_notices SET handled_at=now() WHERE id=?",row.get("id"));
        }
    }
}
