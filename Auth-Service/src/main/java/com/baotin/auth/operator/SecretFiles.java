package com.baotin.auth.operator;

import java.nio.file.*;

public final class SecretFiles {
    private SecretFiles(){}
    public static String required(String name){
        String value=System.getenv(name),path=System.getenv(name+"_FILE");
        if(value!=null&&!value.isBlank()&&path!=null&&!path.isBlank())throw new IllegalArgumentException("Use either "+name+" or "+name+"_FILE, not both");
        if(path!=null&&!path.isBlank())try{value=Files.readString(Path.of(path)).stripTrailing();}catch(Exception e){throw new IllegalArgumentException("Cannot read secret file for "+name);}
        if(value==null||value.isBlank())throw new IllegalArgumentException(name+" or its file required");return value;
    }
}
