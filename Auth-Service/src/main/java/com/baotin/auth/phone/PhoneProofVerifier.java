package com.baotin.auth.phone;

public interface PhoneProofVerifier {
    record Proof(String project,String uid,String phone,String provider,long authTime){}
    Proof verify(String idToken);
}
