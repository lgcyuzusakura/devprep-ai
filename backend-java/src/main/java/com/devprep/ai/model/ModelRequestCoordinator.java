package com.devprep.ai.model;

import com.devprep.ai.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** One provider request at a time across chat, resume editing and model discovery. */
@Component
public class ModelRequestCoordinator {
    private final Semaphore active = new Semaphore(1);
    private final long intervalMillis;
    private long finishedAt;
    public ModelRequestCoordinator(@Value("${devprep.agent-min-interval-millis:10000}") long intervalMillis) {
        this.intervalMillis = Math.max(0, intervalMillis);
    }
    public <T> T call(Supplier<T> action) {
        if (!active.tryAcquire()) throw new ApiException(429, "model_busy", "模型正在处理另一项任务，请等它完成后再试。");
        boolean sent = false;
        try {
            long remaining = intervalMillis - (System.currentTimeMillis() - finishedAt);
            if (remaining > 0) Thread.sleep(remaining);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            sent = true;
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(409, "model_cancelled", "已中断模型任务。");
        } finally {
            if (sent) finishedAt = System.currentTimeMillis();
            active.release();
        }
    }
}
