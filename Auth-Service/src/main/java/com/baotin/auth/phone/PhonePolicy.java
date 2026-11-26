package com.baotin.auth.phone;

import com.baotin.auth.api.ApiFailure;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Spark phone verification is restricted to configured fictional numbers in dev. */
@Component
public class PhonePolicy {
    private final boolean enabled;private final String project;private final Set<String> testNumbers;
    public PhonePolicy(Environment env){
        enabled=env.getProperty("auth.firebase.enabled",Boolean.class,false);project=env.getProperty("auth.firebase.project-id","");
        testNumbers=Set.copyOf(Arrays.stream(env.getProperty("auth.firebase.test-phones","").split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
        if(enabled && (!env.acceptsProfiles(Profiles.of("dev","test")) || env.acceptsProfiles(Profiles.of("prod","team"))))throw new IllegalStateException("Free Firebase phone proof is dev/test only; disable in team/prod");
        if(enabled && (project.isBlank()||testNumbers.isEmpty()||testNumbers.stream().anyMatch(n->!n.matches("\\+[1-9][0-9]{7,14}"))))throw new IllegalStateException("Firebase dev project and fictional test-number allowlist required");
    }
    public boolean enabled(){return enabled;}
    public boolean trusted(String phone){return enabled&&phone!=null&&testNumbers.contains(phone);}
    public String project(){return project;}
    public void require(String phone){
        if(!enabled)throw new ApiFailure(503,"PHONE_VERIFICATION_DISABLED","Phone verification is disabled in this environment");
        if(phone==null||!phone.matches("\\+[1-9][0-9]{7,14}")||!testNumbers.contains(phone))throw new ApiFailure(400,"PHONE_NOT_ALLOWED","Use a configured fictional Firebase test number");
    }
}
