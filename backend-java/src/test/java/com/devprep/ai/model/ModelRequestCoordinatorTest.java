package com.devprep.ai.model;
import com.devprep.ai.api.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ModelRequestCoordinatorTest {
    @Test void rejectsOverlappingRequestAndReleasesAfterFailure(){
        var requests=new ModelRequestCoordinator(0);
        requests.call(()->{assertEquals("model_busy",assertThrows(ApiException.class,()->requests.call(()->"nested")).getCode());return "ok";});
        assertThrows(IllegalStateException.class,()->requests.call(()->{throw new IllegalStateException();}));
        assertEquals("available",requests.call(()->"available"));
    }
    @Test void intervalStartsAfterPriorRequestCompletes(){
        var requests=new ModelRequestCoordinator(55);
        requests.call(()->"first");long start=System.nanoTime();requests.call(()->"second");
        assertTrue((System.nanoTime()-start)/1_000_000>=40);
    }
}
