package com.baotin.auth.phone;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import com.baotin.auth.api.ApiFailure;

class PhonePolicyTests {
    MockEnvironment env(String... profiles){var env=new MockEnvironment().withProperty("auth.firebase.enabled","true").withProperty("auth.firebase.project-id","phone-dev").withProperty("auth.firebase.test-phones","+84900000001");env.setActiveProfiles(profiles);return env;}
    @Test void freePhoneIsNeverEnabledInTeamProdOrUnspecifiedProfile(){
        for(String[] profiles:new String[][]{{"prod"},{"team"},{"dev","prod"},{}})assertThrows(IllegalStateException.class,()->new PhonePolicy(env(profiles)));
        assertDoesNotThrow(()->new PhonePolicy(env("dev")));
    }
    @Test void onlyConfiguredFictionalE164NumbersAreAccepted(){var policy=new PhonePolicy(env("test"));policy.require("+84900000001");assertThrows(ApiFailure.class,()->policy.require("+84900000002"));assertThrows(ApiFailure.class,()->policy.require("0900000001"));assertThrows(ApiFailure.class,()->new PhonePolicy(new MockEnvironment()).require("+84900000001"));}
}
