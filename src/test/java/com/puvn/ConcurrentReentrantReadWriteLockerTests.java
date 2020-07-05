package com.puvn;


import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConcurrentReentrantReadWriteLocker} tests.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ConcurrentReentrantReadWriteLockerTests {

    private static final long TIMEOUT = 2000L;

    private static final long AVOID_RACE_CONDITION_TIME = 50L;

    private static final long LONG_WORK_TIME = 5000L;

    private static final long ODDS_TIME = 100L;

    private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

    private static final int THREAD_POOL = 100;

    private ExecutorService executorService;

    private final EntityLocker entityLocker = new ConcurrentReentrantReadWriteLocker();

    private static final class Entity<K, V> {
        private final K key;
        private V value;

        public Entity(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Simple test for checking locker in single thread environment.
     *
     * @throws InterruptedException exception on latch
     */
    @Test
    public void testLockerInSingleThreadWithSingleEntity() throws InterruptedException {
        var testEntity = new Entity<>("alice", 100);

        executorService = Executors.newSingleThreadExecutor();
        var countDownLatch = new CountDownLatch(1);

        executorService.submit(() -> {
            try {
                entityLocker.doWithEntity(testEntity.key, getIncrementalIntegerBusinessTask(testEntity, 200));
                countDownLatch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                executorService.shutdownNow();
            }
        });
        countDownLatch.await(TIMEOUT, TIMEOUT_UNIT);

        assertEquals(300, testEntity.value);
    }

    /**
     * Testing locker in concurrency environment. 10 threads with
     * {@link ConcurrentReentrantReadWriteLockerTests#getIncrementalIntegerBusinessTask}
     */
    @Test
    public void testLockerInSeveralThreadsWithSingleEntity() throws InterruptedException {
        var testEntity = new Entity<>("alice", 100);

        executorService = Executors.newFixedThreadPool(THREAD_POOL);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREAD_POOL; i++) {
            tasks.add(() -> {
                entityLocker.doWithEntity(testEntity.key, getIncrementalIntegerBusinessTask(testEntity, 100));
                return null;
            });
        }
        executorService.invokeAll(tasks);
        assertEquals(10100, testEntity.value); //increment 100 times by 100, plus initial value
    }

    /**
     * Checking that writes are locked while performing another write action with entity.
     */
    @Test
    public void testWriteLockInSeveralThreadsWithSingleEntity() throws InterruptedException {
        var testEntity = new Entity<>("alice", 100);

        Runnable longRunnable = () -> {
            try {
                entityLocker.doWithEntity(testEntity.key,
                        getLongWorkBusinessTask(testEntity, 500));
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        Runnable fastRunnable = () -> {
            try {
                entityLocker.doWithEntity(testEntity.key, getIncrementalIntegerBusinessTask(testEntity, 100));
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        var longThread = new Thread(longRunnable);
        var fastThread = new Thread(fastRunnable); //fastThread waiting while longThread do its job
        longThread.start();
        Thread.sleep(AVOID_RACE_CONDITION_TIME); //avoid race condition between longThread & fastThread
        fastThread.start();

        Thread.sleep(ODDS_TIME); //waiting for fastThread to start

        boolean fastThreadWasWaiting = false;
        if (fastThread.getState().equals(Thread.State.WAITING)) {
            fastThreadWasWaiting = true;
        }

        longThread.join();
        fastThread.join();

        assertEquals(600, testEntity.value); //both threads did their jobs
        assertTrue(fastThreadWasWaiting);
    }

    /**
     * Simple fast business task for concurrency check. Classic incremental function.
     *
     * @return callable fast business task
     */
    private <K> Callable getIncrementalIntegerBusinessTask(Entity<K, Integer> entity, Integer increment) {
        return () -> {
            entity.value += increment;
            return entity;
        };
    }

    /**
     * Simple long business task fo concurrency check. Method waits for 5 seconds
     * and set specified value to the entity.
     *
     * @param entity entity
     * @param value value to set to the entity
     * @return callable long business task
     */
    private Callable getLongWorkBusinessTask(Entity entity, Integer value) {
        return () -> {
            Thread.sleep(LONG_WORK_TIME);
            entity.value = value;
            return null;
        };
    }

}