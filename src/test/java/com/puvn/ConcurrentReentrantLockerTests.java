package com.puvn;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConcurrentReentrantLocker} tests.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ConcurrentReentrantLockerTests {

    private static final long TIMEOUT = 2000L;

    private static final long AVOID_RACE_CONDITION_TIME = 50L;

    private static final long LONG_WORK_TIME = 5000L;

    private static final long ODDS_TIME = 100L;

    private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

    private static final int THREAD_POOL = 100;

    private ExecutorService executorService;

    private EntityLocker entityLocker;

    private static final class Entity<K, V> {
        private final K key;
        private V value;

        public Entity(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public synchronized V getValue() {
            return value;
        }

        public synchronized void setValue(V value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entity<?, ?> entity = (Entity<?, ?>) o;
            return key.equals(entity.key) &&
                    Objects.equals(value, entity.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }

    @BeforeEach
    public void createEntityLocker() {
        entityLocker = new ConcurrentReentrantLocker();
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
     * Testing locker in concurrency environment. {@code THREAD_POOL} threads with 1000
     * {@link ConcurrentReentrantLockerTests#getIncrementalIntegerBusinessTask}
     */
    @Test
    public void testLockerInSeveralThreadsWithSingleEntity() throws InterruptedException {
        var testEntity = new Entity<>("alice", 100);

        executorService = Executors.newFixedThreadPool(THREAD_POOL);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tasks.add(() -> {
                entityLocker.doWithEntity(testEntity.key,
                        getIncrementalIntegerBusinessTask(testEntity, 100));
                return null;
            });
        }
        executorService.invokeAll(tasks);
        assertEquals(100100, testEntity.value);
    }

    /**
     * Testing multiple entities in multiple threads.
     *
     * @throws InterruptedException error
     */
    @Test
    public void testLockerInSeveralThreadsWithSeveralEntities() throws InterruptedException {
        var testEntity1 = new Entity<>("alice", 100);
        var testEntity2 = new Entity<>("bob", 200);
        var testEntity3 = new Entity<>("charlie", 300);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i <= 500; i++) {
            tasks.add(() -> {
                entityLocker.doWithEntity(testEntity3.key,
                        getIncrementalIntegerBusinessTask(testEntity3, 100));
                entityLocker.doWithEntity(testEntity2.key,
                        getIncrementalIntegerBusinessTask(testEntity2, 100));
                entityLocker.doWithEntity(testEntity1.key,
                        getIncrementalIntegerBusinessTask(testEntity1, 200));
                entityLocker.doWithEntity(testEntity3.key,
                        getIncrementalIntegerBusinessTask(testEntity3, -100));
                return null;
            });
        }

        executorService = Executors.newFixedThreadPool(THREAD_POOL);
        executorService.invokeAll(tasks);

        assertEquals(300, testEntity3.value);
        assertEquals(50300, testEntity2.value);
        assertEquals(100300, testEntity1.value);
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
        if (fastThread.getState().equals(Thread.State.WAITING)) { //fastThread is waiting on lock
            fastThreadWasWaiting = true;
        }

        longThread.join();
        fastThread.join();

        assertEquals(600, testEntity.value); //both threads did their jobs
        assertTrue(fastThreadWasWaiting);
    }

    /**
     * Checking that reads are locked while writing in progress.
     *
     * @throws InterruptedException exception
     */
    @Test
    public void testReadsAreLockedWhileWritingIsInProgress() throws InterruptedException {
        var testEntity = new Entity<>("alice", 100);

        Runnable longRunnable = () -> {
            try {
                entityLocker.doWithEntity(testEntity.key,
                        getLongWorkBusinessTask(testEntity, 600));
            } catch (Exception e) {
                e.printStackTrace();
            }
        };

        Runnable readRunnable = () -> {
            try {
                entityLocker.doWithEntity(testEntity.key, testEntity::getValue);
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        var writeThread = new Thread(longRunnable);
        var readThread1 = new Thread(readRunnable);
        var readThread2 = new Thread(readRunnable);
        writeThread.start();
        Thread.sleep(AVOID_RACE_CONDITION_TIME);
        readThread1.start();
        readThread2.start();

        Thread.sleep(ODDS_TIME);
        boolean readsWereWaiting = false;
        if (readThread1.getState().equals(Thread.State.WAITING)
                && readThread1.getState().equals(readThread2.getState())) {
            readsWereWaiting = true;
        }

        writeThread.join();
        assertEquals(600, testEntity.value);
        assertTrue(readsWereWaiting);
    }

    /**
     * Testing that {@link EntityLocker#tryToLockInTimeAndDoWithEntity(Object, Callable, long, TimeUnit)} could not
     * get lock in time and returned null.
     *
     * @throws Exception exception
     */
    @Test
    public void testCouldNotGetLockInTimeAndReturnNull() throws Exception {
        var testEntity = new Entity<>("alice", 100);
        new Thread(() -> {
            try {
                entityLocker.doWithEntity(testEntity.key,
                        getLongWorkBusinessTask(testEntity, 100)); // 5 seconds
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        Thread.sleep(ODDS_TIME); //avoid race condition
        Object result = entityLocker.tryToLockInTimeAndDoWithEntity(testEntity.key,
                getIncrementalIntegerBusinessTask(testEntity, 100), 500L, TimeUnit.MILLISECONDS);
        assertNull(result);
    }

    /**
     * Testing that {@link EntityLocker#tryToLockInTimeAndDoWithEntity(Object, Callable, long, TimeUnit)}
     * successfully acquired lock and dit his job. (like as a second thread)
     *
     * @throws Exception exception
     */
    @Test
    public void acquireLockInTimeAndReturnValue() throws Exception {
        var testEntity = new Entity<>("alice", 100);
        var thread = new Thread(() -> {
            try {
                Thread.sleep(1500);
                entityLocker.doWithEntity(testEntity.key,
                        getIncrementalIntegerBusinessTask(testEntity, 100));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.start();
        entityLocker.tryToLockInTimeAndDoWithEntity(testEntity.key,
                getIncrementalIntegerBusinessTask(testEntity, 100), 500L, TimeUnit.MILLISECONDS);
        thread.join();
        assertEquals(300, testEntity.getValue());
    }

    /**
     * Simple fast business task for concurrency check. Classic incremental function.
     *
     * @return callable fast business task
     */
    private <K> Callable getIncrementalIntegerBusinessTask(Entity<K, Integer> entity, Integer increment) {
        return () -> {
            entity.setValue(entity.getValue() + increment);
            return entity;
        };
    }

    /**
     * Simple long business task fo concurrency check. Method waits for 5 seconds
     * and set specified value to the entity.
     *
     * @param entity entity
     * @param value  value to set to the entity
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