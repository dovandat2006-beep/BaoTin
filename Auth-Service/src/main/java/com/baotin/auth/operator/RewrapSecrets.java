package com.baotin.auth.operator;

import com.baotin.auth.security.EncryptionKeyRing;
import org.springframework.core.env.StandardEnvironment;
import java.sql.*;
import java.util.*;

/** One-time migration CLI; fixed table/column allowlist, no HTTP endpoint. */
public final class RewrapSecrets {
    private static final String[][] TARGETS={{"users","id","mfa_secret"},{"mfa_enrollments","user_id","encrypted_secret"},{"mail_outbox","id","encrypted_payload"},{"oauth_requests","state_hash","encrypted_verifier"},{"oauth_tickets","ticket_hash","encrypted_payload"}};
    private RewrapSecrets(){}
    private static String required(String key){return SecretFiles.required(key);}
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No table/SQL arguments accepted");
        var ring=EncryptionKeyRing.load(new StandardEnvironment());int changed=0;
        try(var connection=DriverManager.getConnection(required("DB_URL"),required("DB_MIGRATION_USER"),required("DB_MIGRATION_PASSWORD"))){
            try(var statement=connection.createStatement();var role=statement.executeQuery("SELECT session_user")){role.next();if(!"baotin_auth_migrator".equals(role.getString(1)))throw new IllegalStateException("Dedicated migrator role required");}
            connection.setAutoCommit(false);
            for(String[] target:TARGETS){String cursor="";
                for(;;){int count=0;String last=cursor;
                    String select="SELECT "+target[1]+","+target[2]+" FROM auth."+target[0]+" WHERE "+target[1]+"::text COLLATE \"C\">? AND octet_length("+target[2]+")>0 ORDER BY "+target[1]+"::text COLLATE \"C\" LIMIT 100 FOR UPDATE";
                    try(var statement=connection.prepareStatement(select)){statement.setString(1,cursor);
                        try(var rows=statement.executeQuery()){
                            while(rows.next()){Object id=rows.getObject(1);byte[] value=rows.getBytes(2);last=id.toString();count++;
                                byte[] plaintext=ring.decrypt(value);
                                try{
                                    if(!ring.isCurrent(value))try(var update=connection.prepareStatement("UPDATE auth."+target[0]+" SET "+target[2]+"=? WHERE "+target[1]+"=?")){update.setBytes(1,ring.encrypt(plaintext));update.setObject(2,id);update.executeUpdate();changed++;}
                                }finally{Arrays.fill(plaintext,(byte)0);}
                            }
                        }
                    }catch(Exception e){connection.rollback();throw e;}
                    connection.commit();cursor=last;if(count<100)break;
                }
            }
        }
        System.out.println("SECRET_REWRAP_OK changed="+changed);
    }
}
