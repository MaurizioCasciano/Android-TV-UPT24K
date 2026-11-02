package com.gathin.tvhome;

import java.util.Collections;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestAutoStartTest {
    private SpeedTestAutoStart fresh(){return new SpeedTestAutoStart(7,-1,Collections.emptySet(),-1,1000);}

    @Test public void waitsForValidatedConnectionAndClaimsOnlyOnce(){
        SpeedTestAutoStart policy=fresh();
        assertEquals(-1,policy.delay("",1000));
        assertEquals(0,policy.delay("100:false",1000));
        policy.claim("100:false",1000);
        assertEquals(-1,policy.delay("100:false",1_000_000));
    }
    @Test public void returningHomeOrRecreatingTheProcessDoesNotRerunTheSameConnection(){
        SpeedTestAutoStart before=fresh();before.claim("100:false",1000);
        SpeedTestAutoStart restored=new SpeedTestAutoStart(7,before.boot(),before.attempted(),before.lastAttempt(),2000);
        assertEquals(-1,restored.delay("100:false",2000));
    }
    @Test public void newBootAllowsFreshTestEvenWhenNetworkHandleRepeats(){
        SpeedTestAutoStart before=fresh();before.claim("100:false",1000);
        SpeedTestAutoStart reboot=new SpeedTestAutoStart(8,7,before.attempted(),before.lastAttempt(),500);
        assertEquals(0,reboot.delay("100:false",500));
    }
    @Test public void vpnOrNewNetworkGetsOneTestWithCrossNetworkCooldown(){
        SpeedTestAutoStart policy=fresh();policy.claim("100:false",1000);
        assertEquals(59_000,policy.delay("101:true",2000));
        assertEquals(0,policy.delay("101:true",61_000));
        policy.claim("101:true",61_000);
        assertEquals(-1,policy.delay("101:true",120_000));
        assertEquals(-1,policy.delay("100:false",120_000));
    }
    @Test public void interruptedFirstAttemptCanResumeWithoutARepeatedImmediateTransfer(){
        SpeedTestAutoStart policy=fresh();policy.claim("100:false",1000);
        policy.releaseUnfinished("100:false");
        assertEquals(59_000,policy.delay("100:false",2000));
        assertEquals(0,policy.delay("100:false",61_000));
    }
    @Test public void restoreDoesNotKeepRawOrInvalidNetworkIdentifiers(){
        SpeedTestAutoStart policy=new SpeedTestAutoStart(7,7,Set.of("100:false","invalid","wifi-name"),1000,2000);
        assertEquals(Set.of("100:false"),policy.attempted());
    }
    @Test public void elapsedClockResetCannotKeepAnOldCooldownForever(){
        SpeedTestAutoStart policy=new SpeedTestAutoStart(7,7,Set.of("100:false"),100_000,1000);
        assertEquals(0,policy.delay("100:false",1000));
    }
}
