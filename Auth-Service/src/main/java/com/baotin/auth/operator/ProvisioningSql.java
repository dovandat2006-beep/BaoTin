package com.baotin.auth.operator;

/** Exports only the reviewed provisioning resources from the actual release image. */
public final class ProvisioningSql {
    private ProvisioningSql(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=1||!java.util.Set.of("before","after").contains(args[0]))throw new IllegalArgumentException("Only before|after supported");
        try(var input=ProvisioningSql.class.getClassLoader().getResourceAsStream("db/provision/"+args[0]+"-migrate.sql")){if(input==null)throw new IllegalStateException("Provisioning resource missing");System.out.print(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
