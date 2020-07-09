package com.puvn;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * The task is to create a reusable utility class that provides synchronization
 * mechanism similar to row-level DB locking.
 * <p>
 * The class is supposed to be used by the components that are responsible for managing storage and caching of
 * different type of entities in the application. EntityLocker itself does not deal with the entities,
 * only with the IDs (primary keys) of the entities.
 *
 * @param <I> entityId to be locked
 */
public interface EntityLocker<I, V> {

    /**
     * EntityLocker’s interface should allow the caller to specify which entity does it want to work with
     * (using entity ID), and designate the boundaries of the code that should have exclusive access to the entity.
     *
     * @param entityId entityId to be locked
     * @param callable protected code that should have exclusive access to the entity
     * @return result of protected code's execution
     */
    V doWithEntity(I entityId, Callable<V> callable) throws Exception;

    /**
     * Same as {@link EntityLocker#doWithEntity(I, java.util.concurrent.Callable)} but with
     * possibility to specify timeout for locking entities.
     *
     * @param entityId entityId to be locked for write
     * @param timeout  timeout
     * @param unit     timeout unit
     * @return result of protected code's execution or null if it could not to acquire a lock successfully.
     */
    V tryToLockInTimeAndDoWithEntity(I entityId, Callable<V> callable, long timeout, TimeUnit unit) throws Exception;

}