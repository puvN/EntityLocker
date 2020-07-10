package com.puvn;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A concurrent implementation of {@link EntityLocker} using {@link java.util.concurrent.ConcurrentHashMap}
 * and {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * @param <I> entityId
 */
public class ConcurrentReentrantLocker<I, V> implements EntityLocker<I, V> {

    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();

    private final ConcurrentMap<I, ReentrantLock> locksMap;

    public ConcurrentReentrantLocker() {
        locksMap = new ConcurrentHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V doWithEntity(I entityId, Callable<V> callable) throws Exception {
        V result;
        try {
            lock(entityId, Long.MIN_VALUE);
            result = callable.call();
        } finally {
            unlock(entityId);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V tryToLockInTimeAndDoWithEntity(I entityId, Callable<V> callable, long timeout)
            throws Exception {
        V result;
        if (lock(entityId, timeout)) {
            result = callable.call();
        } else {
            return null;
        }
        unlock(entityId);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V doWithGlobalLock(Callable<V> callable) throws Exception {
        V result;
        globalLock.writeLock().lock();
        result = callable.call();
        globalLock.writeLock().unlock();
        return result;
    }

    /**
     * Method validates entity and lock it. Information about lock gets into locks map.
     *
     * @param entityId id to be locked
     * @return lock
     */
    private ReentrantLock computeOrGetFromMap(I entityId) {
        return locksMap.computeIfAbsent(entityId, v -> new ReentrantLock());
    }

    /**
     * Method locks specified entityId with the possibility of timeout.
     * TODO: It is possible to implement a better calculation of timeout.
     *
     * @param entityId entityId to be locked
     * @param nanoseconds  timeout value
     * @throws InterruptedException exception in case of exception in {@link ReentrantLock#tryLock(long, TimeUnit)}
     *                              or {@link ReentrantLock#lockInterruptibly()}
     */
    private boolean lock(I entityId, long nanoseconds) throws InterruptedException {
        if (entityId == null) {
            throw new IllegalArgumentException();
        }
        boolean lockInMap = false;
        do {
            var lock = computeOrGetFromMap(entityId);
            if (nanoseconds == Long.MIN_VALUE) {
                globalLock.readLock().lock();
                lock.lockInterruptibly();
            } else {
                if (nanoseconds <= 0) {
                    return false;
                }
                var tick = System.nanoTime();
                if (!globalLock.readLock().tryLock(nanoseconds, TimeUnit.NANOSECONDS)) {
                    return false;
                }
                nanoseconds -= System.nanoTime() - tick;
                tick = System.nanoTime();
                if (nanoseconds <= 0 || !lock.tryLock(nanoseconds, TimeUnit.NANOSECONDS)) {
                    return false;
                }
                nanoseconds -= System.nanoTime() - tick;
            }
            if (lock == computeOrGetFromMap(entityId)) {
                lockInMap = true;
            } else {
                lock.unlock();
            }
        } while (!lockInMap);
        return true;
    }

    /**
     * Unlock specified entity.
     *
     * @param entityId id to be unlocked
     */
    private void unlock(I entityId) {
        var lock = locksMap.get(entityId);
        if (lock != null) {
            if (!lock.isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException();
            }
            if (!lock.hasQueuedThreads()) {
                locksMap.remove(entityId);
            }
            lock.unlock();
            globalLock.readLock().unlock();
        }
    }

}