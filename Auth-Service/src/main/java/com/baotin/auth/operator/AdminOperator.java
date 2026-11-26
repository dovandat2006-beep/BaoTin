package com.baotin.auth.operator;

import java.sql.DriverManager;
import java.util.UUID;

/** Dedicated CLI, not a Spring bean/HTTP endpoint. Credentials are read from env. */
public final class AdminOperator {
    private AdminOperator(){}
    private static String required(String key){return SecretFiles.required(key);}
    public static void main(String[] args)throws Exception{
        if(args.length!=3 || !java.util.Set.of("request","activate","recover","revoke").contains(args[0]))throw new IllegalArgumentException("Usage: AdminOperator request|activate|recover|revoke USER_UUID REASON");
        UUID target=UUID.fromString(args[1]);
        try(var connection=DriverManager.getConnection(required("OPERATOR_DB_URL"),required("OPERATOR_DB_USER"),required("OPERATOR_DB_PASSWORD"))){
            try(var statement=connection.createStatement();var role=statement.executeQuery("SELECT session_user")){
                role.next();if(!"baotin_auth_operator".equals(role.getString(1)))throw new IllegalStateException("Dedicated operator role required");
            }
            try(var statement=connection.prepareStatement("SELECT auth.operator_admin(?,?,?)")){
                statement.setString(1,args[0]);statement.setObject(2,target);statement.setString(3,args[2]);statement.execute();
            }
            System.out.println("OPERATOR_ADMIN_OK action="+args[0]+" target="+target);
        }
    }
}
