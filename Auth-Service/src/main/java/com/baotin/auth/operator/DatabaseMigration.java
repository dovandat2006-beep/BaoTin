package com.baotin.auth.operator;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

/** Migration job entry point; runtime app does not receive these credentials. */
public final class DatabaseMigration {
    private DatabaseMigration(){}
    private static String required(String key){return SecretFiles.required(key);}
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No migration SQL/location arguments accepted");
        String url=required("DB_URL"),user=required("DB_MIGRATION_USER"),password=required("DB_MIGRATION_PASSWORD");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();var role=statement.executeQuery("SELECT session_user")){
            role.next();if(!"baotin_auth_migrator".equals(role.getString(1)))throw new IllegalStateException("Dedicated migrator role required");
        }
        var result=Flyway.configure().dataSource(url,user,password).schemas("auth").defaultSchema("auth").createSchemas(false).load().migrate();
        System.out.println("AUTH_MIGRATION_OK count="+result.migrationsExecuted);
    }
}
