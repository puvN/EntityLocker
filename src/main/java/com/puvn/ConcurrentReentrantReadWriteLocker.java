package com.puvn;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A concurrent implementation of {@link EntityLocker} using {@link java.util.concurrent.ConcurrentHashMap}
 * and {@link java.util.concurrent.locks.ReentrantReadWriteLock} with the possibility to create read-lock or write-lock
 * for entity id.
 *
 * @param <I> entityId
 */
public class ConcurrentReentrantReadWriteLocker<I, V> implements EntityLocker<I, V> {

    private final ConcurrentMap<I, ReentrantReadWriteLock> locksMap;

    public ConcurrentReentrantReadWriteLocker() {
        locksMap = new ConcurrentHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doWithEntity(I entityId, Callable<V> callable) throws Exception {
        var lock = validateAndLock(entityId);
        lock.writeLock().lock();
        try {
            callable.call();
        } finally {
            lock.writeLock().unlock();
            locksMap.remove(entityId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean tryToDoWithEntityInTime(I entityId, Callable<V> callable, long timeout, TimeUnit unit)
            throws Exception {
        var lock = validateAndLock(entityId);
        var writeLock = lock.writeLock();
        if (!writeLock.tryLock(timeout, unit)) {
            return false;
        }
        try {
            callable.call();
        } finally {
            lock.writeLock().unlock();
            locksMap.remove(entityId);
        }
        return true;
    }

    /**
     * Method validates entity and lock it. Information about lock gets into locks map.
     *
     * @param entityId id to be locked
     * @return lock
     */
    private ReentrantReadWriteLock validateAndLock(I entityId) {
        if (entityId == null) {
            throw new IllegalArgumentException("EntityId must not be null");
        }
        return locksMap.computeIfAbsent(entityId, v -> new ReentrantReadWriteLock());
    }

}
