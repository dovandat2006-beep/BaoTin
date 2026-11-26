package com.baotin.auth.application;

import java.util.UUID;
import java.util.Optional;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityStore {
    public record User(UUID id,String username,String email,String passwordHash,String displayName,String role,
        String status,boolean emailVerified,long version,byte[] mfaSecret,boolean mfaEnabled,long lastStep,String phone,boolean phoneVerified,boolean emailMfaEnabled) {
        public User {mfaSecret=mfaSecret==null?null:mfaSecret.clone();}
        public byte[] mfaSecret(){return mfaSecret==null?null:mfaSecret.clone();}
        private Object[] valueComponents(){return new Object[]{id,username,email,passwordHash,displayName,role,status,emailVerified,version,mfaSecret,mfaEnabled,lastStep,phone,phoneVerified,emailMfaEnabled};}
        @Override public boolean equals(Object other){return this==other || other instanceof User u && Arrays.deepEquals(valueComponents(),u.valueComponents());}
        @Override public int hashCode(){return Arrays.deepHashCode(valueComponents());}
        @Override public String toString(){return "User[id="+id+", role="+role+", status="+status+", credentials=<redacted>]";}
        public boolean anyMfa(){return mfaEnabled || emailMfaEnabled;}
        public String mfaMethod(){return mfaEnabled?"TOTP":emailMfaEnabled?"EMAIL":"NONE";}
    }
    private static boolean emailMode(java.sql.ResultSet rs)throws java.sql.SQLException{
        // Reader2 bridge must still operate on pre-migration schema9.
        var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)if("email_mfa_enabled".equals(meta.getColumnLabel(i)))return rs.getBoolean(i);
        return false;
    }
    private static final RowMapper<User> USER=(rs,n)->new User(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("email"),
        rs.getString("password_hash"),rs.getString("display_name"),rs.getString("role"),rs.getString("status"),rs.getBoolean("email_verified"),
        rs.getLong("security_version"),rs.getBytes("mfa_secret"),rs.getBoolean("mfa_enabled"),rs.getLong("last_totp_step"),rs.getString("phone"),rs.getBoolean("phone_verified"),emailMode(rs));
    private final JdbcTemplate jdbc;
    public IdentityStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public JdbcTemplate jdbc(){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
            var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet){
                Object correlation=servlet.getRequest().getAttribute("correlationId");
                if(correlation instanceof String id)jdbc.queryForObject("SELECT set_config('baotin.correlation_id',?,true)",String.class,id);
            }
        }return jdbc;
    }
    public Optional<User> byIdentifier(String value){return jdbc.query("SELECT * FROM auth.users WHERE username=? OR email=? OR phone=?",USER,value,value,value).stream().findFirst();}
    public Optional<User> byId(UUID id){return jdbc.query("SELECT * FROM auth.users WHERE id=?",USER,id).stream().findFirst();}
    public Optional<User> locked(UUID id){return jdbc.query("SELECT * FROM auth.users WHERE id=? FOR UPDATE",USER,id).stream().findFirst();}
    public void audit(UUID id,String event){jdbc.update("INSERT INTO auth.audit_events(id,user_id,event_type) VALUES (?,?,?)",UUID.randomUUID(),id,event);}
}
