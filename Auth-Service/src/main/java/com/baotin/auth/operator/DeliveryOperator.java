package com.baotin.auth.operator;

import java.util.UUID;
import java.sql.DriverManager;

/** Audited exhausted delivery retry, never a web endpoint or arbitrary SQL. */
public final class DeliveryOperator {
    private DeliveryOperator(){}
    private static String required(String name){return SecretFiles.required(name);}
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!java.util.Set.of("MAIL","EVENT").contains(args[0]))throw new IllegalArgumentException("Usage: DeliveryOperator MAIL|EVENT UUID REASON");
        UUID target=UUID.fromString(args[1]);
        try(var connection=DriverManager.getConnection(required("OPERATOR_DB_URL"),required("OPERATOR_DB_USER"),required("OPERATOR_DB_PASSWORD"))){
            try(var statement=connection.createStatement();var role=statement.executeQuery("SELECT session_user")){role.next();if(!"baotin_auth_operator".equals(role.getString(1)))throw new IllegalStateException("Dedicated operator role required");}
            try(var statement=connection.prepareStatement("SELECT auth.operator_retry_delivery(?,?,?)")){statement.setString(1,args[0]);statement.setObject(2,target);statement.setString(3,args[2]);statement.execute();}
        }
        System.out.println("DELIVERY_RETRY_OK kind="+args[0]+" id="+target);
    }
}
