package com.baotin.gateway;

import java.net.*;
import java.util.*;

/** Literal-only IP/CIDR parsing: never resolve an untrusted header as a hostname. */
public final class ProxyAddresses {
    private record Network(byte[] bytes,int bits) {
        private Network {bytes=bytes.clone();}
        public byte[] bytes(){return bytes.clone();}
        @Override public boolean equals(Object other){return this==other || other instanceof Network n && bits==n.bits && Arrays.equals(bytes,n.bytes);}
        @Override public int hashCode(){return 31*Arrays.hashCode(bytes)+Integer.hashCode(bits);}
        @Override public String toString(){return "Network[bytes="+Arrays.toString(bytes)+", bits="+bits+"]";}
    }
    private final List<Network> networks;
    public ProxyAddresses(String csv){
        var result=new ArrayList<Network>();
        for(String item:csv.split(",")){
            if(item.isBlank())continue;String[] pair=item.trim().split("/",2);InetAddress ip=literal(pair[0]);int bits=pair.length==2?Integer.parseInt(pair[1]):ip.getAddress().length*8;
            if(bits<16 || bits>ip.getAddress().length*8 || (ip.getAddress().length==16&&bits<64))throw new IllegalArgumentException("Trust only narrowly scoped proxy networks");
            result.add(new Network(ip.getAddress(),bits));
        }networks=List.copyOf(result);
    }
    public static InetAddress literal(String text){
        if(text==null||text.isBlank()||text.length()>45||!text.matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException("Invalid literal IP address");
        if(!text.contains(":")){
            String[] parts=text.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException("Invalid IPv4");
            for(String part:parts){int value;try{value=Integer.parseInt(part);}catch(Exception e){throw new IllegalArgumentException("Invalid IPv4");}
                if(value<0||value>255||!Integer.toString(value).equals(part))throw new IllegalArgumentException("Invalid IPv4");}
        }
        try{return InetAddress.getByName(text);}catch(Exception e){throw new IllegalArgumentException("Invalid literal IP address");}
    }
    public boolean trusted(String address){
        byte[] value;try{value=literal(address).getAddress();}catch(Exception e){return false;}
        for(Network n:networks){
            if(value.length!=n.bytes.length)continue;int full=n.bits/8,partial=n.bits%8;boolean matches=true;
            for(int i=0;i<full;i++)if(value[i]!=n.bytes[i]){matches=false;break;}
            if(matches&&partial!=0){int mask=255<<(8-partial);matches=(value[full]&mask)==(n.bytes[full]&mask);}
            if(matches)return true;
        }return false;
    }
    public String client(String remote,String forwarded){
        if(!trusted(remote)||forwarded==null||forwarded.isBlank())return remote;
        String[] hops=forwarded.split(",",-1);if(hops.length>8||forwarded.length()>1024)throw new IllegalArgumentException("Forwarded chain too long");
        String current=remote;
        for(int i=hops.length-1;i>=0;i--){
            if(!trusted(current))return current;
            current=literal(hops[i].trim()).getHostAddress();
        }return current;
    }
}

