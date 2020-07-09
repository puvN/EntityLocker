package com.puvn;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A concurrent implementation of {@link EntityLocker} using {@link java.util.concurrent.ConcurrentHashMap}
 * and {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * @param <I> entityId
 */
public class ConcurrentReentrantLocker<I, V> implements EntityLocker<I, V> {

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
            lock(entityId, null, null);
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
    public V tryToLockInTimeAndDoWithEntity(I entityId, Callable<V> callable, long timeout, TimeUnit unit)
            throws Exception {
        V result;
        if (lock(entityId, timeout, unit)) {
            result = callable.call();
        } else {
            return null;
        }
        unlock(entityId);
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
     *
     * @param entityId entityId to be locked
     * @param timeout  timeout value
     * @param unit     timeout unit
     * @throws InterruptedException exception in case of exception in {@link ReentrantLock#tryLock(long, TimeUnit)}
     *                              or {@link ReentrantLock#lockInterruptibly()}
     */
    private boolean lock(I entityId, Long timeout, TimeUnit unit) throws InterruptedException {
        if (entityId == null) {
            throw new IllegalArgumentException();
        }
        boolean lockInMap = false;
        do {
            var lock = computeOrGetFromMap(entityId);
            if (timeout != null && unit != null) {
                if (timeout <= 0) {
                    throw new IllegalArgumentException("Timeout must be greater than 0");
                }
                if (!lock.tryLock(timeout, unit)) {
                    return false;
                }
            } else {
                lock.lockInterruptibly();
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
        }
    }

}