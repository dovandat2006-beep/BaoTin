package com.baotin.gateway;

public final class RuntimeCapabilities {
    private RuntimeCapabilities(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=0)throw new IllegalArgumentException("No arguments supported");
        try(var input=RuntimeCapabilities.class.getClassLoader().getResourceAsStream("META-INF/baotin-runtime.json")){if(input==null)throw new IllegalStateException("Capabilities resource missing");System.out.println(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());}
    }
}
