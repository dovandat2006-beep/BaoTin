package com.baotin.auth.operator;

import java.sql.DriverManager;
import java.util.Map;
import java.util.TreeMap;

/** Read-only deployment compatibility probe, never a provisioning credential. */
public final class RuntimeDatabaseState {
    private RuntimeDatabaseState(){}
    public static Map<String,String> read(String url,String user,String password)throws Exception{
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            try(var role=statement.executeQuery("SELECT session_user")){
                role.next();if(!"baotin_auth".equals(role.getString(1)))throw new IllegalStateException("Dedicated runtime role required");
            }
            var values=new TreeMap<String,String>();
            try(var result=statement.executeQuery("SELECT name,value FROM auth.runtime_metadata WHERE name IN ('schemaRevision','readerFloor','cipherFloor')")){
                while(result.next())values.put(result.getString(1),result.getString(2));
            }
            if(values.size()!=3||!values.get("schemaRevision").matches("[1-9][0-9]{0,8}")||!values.get("readerFloor").matches("[1-9][0-9]{0,8}")||!values.get("cipherFloor").matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalStateException("Invalid database compatibility metadata");
            return values;
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No SQL arguments accepted");
        var state=read(SecretFiles.required("DB_URL"),SecretFiles.required("DB_USER"),SecretFiles.required("DB_PASSWORD"));
        System.out.println("{\"schemaRevision\":\""+state.get("schemaRevision")+"\",\"readerFloor\":\""+state.get("readerFloor")+"\",\"cipherFloor\":\""+state.get("cipherFloor")+"\"}");
    }
}
