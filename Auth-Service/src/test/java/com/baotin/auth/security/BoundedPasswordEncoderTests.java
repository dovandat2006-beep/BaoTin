package com.baotin.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedPasswordEncoderTests {
    @Test void passwordWorkCannotExceedConfiguredMemorySlots()throws Exception{
        var active=new AtomicInteger();var peak=new AtomicInteger();
        var delegate=new PasswordEncoder(){
            @Override public String encode(CharSequence raw){int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);try{Thread.sleep(20);return "encoded";}catch(InterruptedException e){throw new IllegalStateException(e);}finally{active.decrementAndGet();}}
            @Override public boolean matches(CharSequence raw,String encoded){return encode(raw).equals(encoded);}
        };
        var bounded=new BoundedPasswordEncoder(delegate,2);
        try(var executor=Executors.newFixedThreadPool(8)){
            var tasks=new java.util.ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)tasks.add(executor.submit(()->bounded.matches("input","encoded")));
            for(var task:tasks)assertTrue(task.get(5,TimeUnit.SECONDS));
        }
        assertTrue(peak.get()<=2);assertFalse(bounded.matches("input",null));
    }
}
