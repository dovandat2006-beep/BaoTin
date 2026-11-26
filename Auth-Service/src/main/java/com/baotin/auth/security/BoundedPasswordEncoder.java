package com.baotin.auth.security;

import com.baotin.auth.api.ApiFailure;
import java.util.concurrent.*;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Argon2 memory budget is bounded even when many requests arrive simultaneously. */
public final class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;private final Semaphore slots;
    public BoundedPasswordEncoder(PasswordEncoder delegate,int parallelism){this.delegate=delegate;slots=new Semaphore(parallelism,true);}
    private <T>T execute(java.util.function.Supplier<T> action){
        try{
            if(!slots.tryAcquire(2,TimeUnit.SECONDS))throw new ApiFailure(429,"AUTH_BUSY","Authentication capacity reached; retry shortly");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiFailure(503,"AUTH_UNAVAILABLE","Authentication temporarily unavailable");}
        try{return action.get();}finally{slots.release();}
    }
    @Override public String encode(CharSequence value){return execute(()->delegate.encode(value));}
    @Override public boolean matches(CharSequence raw,String encoded){if(raw==null||encoded==null)return false;return execute(()->delegate.matches(raw,encoded));}
    @Override public boolean upgradeEncoding(String encoded){return delegate.upgradeEncoding(encoded);}
}
