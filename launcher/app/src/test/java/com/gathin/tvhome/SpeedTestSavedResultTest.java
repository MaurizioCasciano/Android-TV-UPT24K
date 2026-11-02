package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestSavedResultTest {
    private SpeedTestMetrics.Result sample(){return new SpeedTestMetrics.Result(56.5,42.9,46.4,12,123456789,"Roma");}
    @Test public void completedMeasurementRoundTripsAcrossProcessRestarts(){
        SpeedTestMetrics.Result r=SpeedTestSavedResult.decode(SpeedTestSavedResult.encode(sample()));
        assertNotNull(r);assertEquals(56.5,r.download,0);assertEquals(42.9,r.upload,0);
        assertEquals(46.4,r.ping,0);assertEquals(12,r.jitter,0);assertEquals(123456789,r.wallTime);assertEquals("Roma",r.server);
    }
    @Test public void savedRecordContainsOnlyMetricsTimestampServerAndVersion(){
        assertEquals(Set.of("version","download","upload","ping","jitter","time","server"),SpeedTestSavedResult.encode(sample()).keySet());
    }
    @Test public void corruptNonFiniteOrIncompleteRecordsCannotBecomeDisplayedNumbers(){
        for(String bad:new String[]{"NaN","Infinity","-1","0","bad"}){
            Map<String,String> values=new HashMap<>(SpeedTestSavedResult.encode(sample()));values.put("download",bad);
            assertNull(SpeedTestSavedResult.decode(values));
        }
        assertNull(SpeedTestSavedResult.decode(Map.of()));
        Map<String,String> values=new HashMap<>(SpeedTestSavedResult.encode(sample()));values.remove("time");
        assertNull(SpeedTestSavedResult.decode(values));
    }
    @Test public void unknownVersionsAndInvalidServerOrTimestampAreRejected(){
        Map<String,String> values=new HashMap<>(SpeedTestSavedResult.encode(sample()));values.put("version","2");
        assertNull(SpeedTestSavedResult.decode(values));
        values.put("version","1");values.put("server","invalid\nserver");assertNull(SpeedTestSavedResult.decode(values));
        values.put("server","Roma");values.put("time","0");assertNull(SpeedTestSavedResult.decode(values));
    }
}
