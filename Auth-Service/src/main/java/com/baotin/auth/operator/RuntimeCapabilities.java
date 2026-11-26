package com.baotin.auth.operator;

import java.sql.DriverManager;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Offline image/DB compatibility inspection; does not start HTTP or read credentials into output. */
public final class RuntimeCapabilities {
    private RuntimeCapabilities(){}
    public static void main(String[] args)throws Exception{
        var mapper=JsonMapper.builder().build();
        if(args.length==0){try(var input=RuntimeCapabilities.class.getClassLoader().getResourceAsStream("META-INF/baotin-runtime.json")){if(input==null)throw new IllegalStateException("Runtime capabilities resource missing");System.out.println(mapper.readTree(input).toString());}return;}
        if(args.length!=1||!"--database".equals(args[0]))throw new IllegalArgumentException("Only --database is supported");
        var result=new TreeMap<String,Object>();
        try(var connection=DriverManager.getConnection(SecretFiles.required("DB_URL"),SecretFiles.required("DB_USER"),SecretFiles.required("DB_PASSWORD"));var statement=connection.createStatement();var rows=statement.executeQuery("SELECT name,value FROM auth.runtime_metadata ORDER BY name")){
            while(rows.next())result.put(rows.getString(1),rows.getString(2));
        }
        if(!result.keySet().containsAll(Set.of("schemaRevision","readerFloor","cipherFloor")))throw new IllegalStateException("Database compatibility metadata missing");
        System.out.println(mapper.writeValueAsString(result));
    }
}
