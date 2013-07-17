/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Florent Guillaume
 *     Benoit Delbosc
 */
package org.nuxeo.ecm.core.storage.sql;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServer;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.management.ManagementService;
import net.sf.ehcache.transaction.manager.TransactionManagerLookup;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.javasimon.Counter;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.javasimon.Stopwatch;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.ACLRow.ACLRowPositionComparator;
import org.nuxeo.ecm.core.storage.sql.Invalidations.InvalidationsPair;
import org.nuxeo.runtime.api.Framework;

/**
 * A {@link RowMapper} that use an unified ehcache.
 * <p>
 * The cache only holds {@link Row}s that are known to be identical to what's in
 * the underlying {@link RowMapper}.
 */
public class UnifiedCachingRowMapper implements RowMapper {

    private static final Log log = LogFactory.getLog(UnifiedCachingRowMapper.class);

    private static final String ABSENT = "__ABSENT__\0\0\0";

    private static CacheManager cacheManager = null;

    protected static boolean isXA;

    private Cache cache;

    private Model model;

    /**
     * The {@link RowMapper} to which operations that cannot be processed from
     * the cache are delegated.
     */
    private RowMapper rowMapper;

    /**
     * The local invalidations due to writes through this mapper that should be
     * propagated to other sessions at post-commit time.
     */
    private final Invalidations localInvalidations;

    /**
     * The queue of cache invalidations received from other session, to process
     * at pre-transaction time.
     */
    private final InvalidationsQueue cacheQueue;

    /**
     * The propagator of invalidations to other mappers.
     */
    private InvalidationsPropagator cachePropagator;

    /**
     * The queue of invalidations used for events, a single queue is shared by
     * all mappers corresponding to the same client repository.
     */
    private InvalidationsQueue eventQueue;

    /**
     * The propagator of event invalidations to all event queues.
     */
    private InvalidationsPropagator eventPropagator;

    /**
     * The session, used for event propagation.
     */
    private SessionImpl session;

    protected boolean forRemoteClient;

    /**
     * Cache statistics
     */
    // JavaSimpon Counter Names
    private static final String CN_ACCESS = "org.nuxeo.ecm.core.storage.sql.row.cache.access";

    private static final String CN_HITS = "org.nuxeo.ecm.core.storage.sql.row.cache.hits";

    private static final String CN_SIZE = "org.nuxeo.ecm.core.storage.sql.row.cache.size";

    // Stop watch for cache access
    private static final String SW_CACHE = "org.nuxeo.ecm.core.storage.sql.row.cache.get";

    // Stop watch for SOR access (System Of Record i.e the db access)
    private static final String SW_SOR = "org.nuxeo.ecm.core.storage.sql.row.sor.gets";

    // Property to enable stop watch
    private static final String CACHE_STATS_PROP = "org.nuxeo.vcs.cache.statistics";

    private static final String CACHE_NAME = "unifiedVCSCache";

    private static final String CACHE_SIZE_PROP = "maxEntriesLocalHeap";

    private static final String CACHE_DISK_SIZE_PROP = "maxEntriesLocalDisk";

    private static final String CACHE_ETERNAL_PROP = "eternal";

    private static final String CACHE_OVERFLOW_TO_DISK_PROP = "overflowToDisk";

    private static final String CACHE_TIME_TO_LIVE_PROP = "timeToLiveSeconds";

    private static final String CACHE_TIME_TO_IDLE_PROP = "timeToIdleSeconds";

    private static final String CACHE_STATISTICS_PROP = "statistics";

    private static final String CACHE_DISK_PERSISTENT_PROP = "diskPersistent";

    private static final String EHCACHE_FILE_PROP = "ehcacheFilePath";

    private static AtomicInteger rowMapperCount = new AtomicInteger();

    private long accessCount;

    private long hitsCount;

    private long cacheSize;

    private boolean cacheStatistics;

    public UnifiedCachingRowMapper() {
        localInvalidations = new Invalidations();
        cacheQueue = new InvalidationsQueue("mapper-" + this);
        forRemoteClient = false;
        String prop = Framework.getProperty(CACHE_STATS_PROP, "false");
        cacheStatistics = Boolean.parseBoolean(prop);
    }

    synchronized public void initialize(Model model, RowMapper rowMapper,
            InvalidationsPropagator cachePropagator,
            InvalidationsPropagator eventPropagator,
            InvalidationsQueue repositoryEventQueue,
            Map<String, String> properties) {
        this.model = model;
        this.rowMapper = rowMapper;
        this.cachePropagator = cachePropagator;
        cachePropagator.addQueue(cacheQueue);
        eventQueue = repositoryEventQueue;
        this.eventPropagator = eventPropagator;
        eventPropagator.addQueue(repositoryEventQueue);
        if (cacheManager == null) {
            if (properties.containsKey(EHCACHE_FILE_PROP)) {
                String value = properties.get(EHCACHE_FILE_PROP);
                log.info("Creating ehcache manager for VCS, using ehcache file: "
                        + value);
                cacheManager = CacheManager.create(value);
            } else {
                log.info("Creating ehcache manager for VCS, No ehcache file provided");
                cacheManager = CacheManager.create();
            }
            isXA = cacheManager.getConfiguration().getCacheConfigurations().get(
                    CACHE_NAME).isXaTransactional();
            // Exposes cache to JMX
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ManagementService.registerMBeans(cacheManager, mBeanServer, true,
                    true, true, true);
        }
        rowMapperCount.incrementAndGet();
        cache = cacheManager.getCache(CACHE_NAME);
    }

    public void close() throws StorageException {
        cachePropagator.removeQueue(cacheQueue);
        eventPropagator.removeQueue(eventQueue); // TODO can be overriden
        logCacheStat();
        rowMapperCount.decrementAndGet();
    }

    /*
     * ----- ehcache -----
     */

    protected boolean hasTransaction() {
        TransactionManagerLookup transactionManagerLookup = cache.getTransactionManagerLookup();
        if (transactionManagerLookup == null) {
            return false;
        }
        TransactionManager transactionManager = transactionManagerLookup.getTransactionManager();
        if (transactionManager == null) {
            return false;
        }
        Transaction transaction;
        try {
            transaction = transactionManager.getTransaction();
        } catch (SystemException e) {
            throw new RuntimeException(e);
        }
        return transaction != null;
    }

    protected boolean useEhCache() {
        return isXA && hasTransaction();
    }

    protected void ehCachePut(Element element) {
        if (useEhCache()) {
            cache.put(element);
        }
    }

    protected Element ehCacheGet(Serializable key) {
        if (useEhCache()) {
            return cache.get(key);
        }
        return null;
    }

    protected int ehCacheGetSize() {
        if (useEhCache()) {
            return cache.getSize();
        }
        return 0;
    }

    protected boolean ehCacheRemove(Serializable key) {
        if (useEhCache()) {
            return cache.remove(key);
        }
        return false;
    }

    protected void ehCacheRemoveAll() {
        if (useEhCache()) {
            cache.removeAll();
        }
    }

    /*
     * ----- Cache -----
     */

    protected static boolean isAbsent(Row row) {
        return row.tableName == ABSENT; // == is ok
    }

    protected void cachePut(Row row) {
        row = row.clone();
        // for ACL collections, make sure the order is correct
        // (without the cache, the query to get a list of collection does an
        // ORDER BY pos, so users of the cache must get the same behavior)
        if (row.isCollection() && row.values.length > 0
                && row.values[0] instanceof ACLRow) {
            row.values = sortACLRows((ACLRow[]) row.values);
        }
        Element element = new Element(new RowId(row), row);
        ehCachePut(element);
    }

    protected ACLRow[] sortACLRows(ACLRow[] acls) {
        List<ACLRow> list = new ArrayList<ACLRow>(Arrays.asList(acls));
        Collections.sort(list, ACLRowPositionComparator.INSTANCE);
        ACLRow[] res = new ACLRow[acls.length];
        return list.toArray(res);
    }

    protected void cachePutAbsent(RowId rowId) {
        Element element = new Element(new RowId(rowId), new Row(ABSENT,
                (Serializable) null));
        ehCachePut(element);
    }

    protected void cachePutAbsentIfNull(RowId rowId, Row row) {
        if (row != null) {
            cachePut(row);
        } else {
            cachePutAbsent(rowId);
        }
    }

    protected void cachePutAbsentIfRowId(RowId rowId) {
        if (rowId instanceof Row) {
            cachePut((Row) rowId);
        } else {
            cachePutAbsent(rowId);
        }
    }

    protected Row cacheGet(RowId rowId) {
        Split split = null;
        if (cacheStatistics) {
            Stopwatch stopWatch = SimonManager.getStopwatch(SW_CACHE);
            split = stopWatch.start();
        }
        Element element = ehCacheGet(rowId);
        Row row = null;
        if (element != null) {
            row = (Row) element.getObjectValue();
        }
        if (row != null && !isAbsent(row)) {
            row = row.clone();
        }
        if (split != null) {
            split.stop();
        }
        updateCacheStat(row);
        return row;
    }

    private void updateCacheStat(Row row) {
        if (row != null) {
            hitsCount++;
        }
        if ((++accessCount % 200) == 0) {
            Counter accessCounter = SimonManager.getCounter(CN_ACCESS);
            accessCounter.increase(accessCount);
            accessCount = 0;
            Counter hitsCounter = SimonManager.getCounter(CN_HITS);
            hitsCounter.increase(hitsCount);
            hitsCount = 0;
            Counter sizeCounter = SimonManager.getCounter(CN_SIZE);
            long delta = ehCacheGetSize() - cacheSize;
            if (delta > 0) {
                sizeCounter.increase(delta);
            } else if (delta < 0) {
                sizeCounter.decrease(-1 * delta);
            }
            cacheSize = ehCacheGetSize();
        }
    }

    private void logCacheStat() {
        if (cacheStatistics) {
            Stopwatch stopWatch = SimonManager.getStopwatch(SW_CACHE);
            log.info(stopWatch);
            stopWatch = SimonManager.getStopwatch(SW_SOR);
            log.info(stopWatch);
        }
        Counter counter = SimonManager.getCounter(CN_ACCESS);
        log.info(counter);
        counter = SimonManager.getCounter(CN_HITS);
        log.info(counter);
        counter = SimonManager.getCounter(CN_SIZE);
        log.info(counter);
    }

    protected void cacheRemove(RowId rowId) {
        ehCacheRemove(rowId);
    }

    /*
     * ----- Invalidations / Cache Management -----
     */

    @Override
    public InvalidationsPair receiveInvalidations() throws StorageException {
        // invalidations from the underlying mapper (remote, cluster)
        InvalidationsPair invals = rowMapper.receiveInvalidations();

        // add local accumulated invalidations to remote ones
        Invalidations invalidations = cacheQueue.getInvalidations();
        if (invals != null) {
            invalidations.add(invals.cacheInvalidations);
        }

        // add local accumulated events to remote ones
        Invalidations events = eventQueue.getInvalidations();
        if (invals != null) {
            events.add(invals.eventInvalidations);
        }

        // invalidate our cache
        if (invalidations.all) {
            clearCache();
        }

        // nothing to do on modified or delete, because there is only one cache

        if (invalidations.isEmpty() && events.isEmpty()) {
            return null;
        }
        return new InvalidationsPair(invalidations.isEmpty() ? null
                : invalidations, events.isEmpty() ? null : events);
    }

    // propagate invalidations
    @Override
    public void sendInvalidations(Invalidations invalidations)
            throws StorageException {
        // add local invalidations
        if (!localInvalidations.isEmpty()) {
            if (invalidations == null) {
                invalidations = new Invalidations();
            }
            invalidations.add(localInvalidations);
            localInvalidations.clear();
        }

        if (invalidations != null && !invalidations.isEmpty()) {
            // send to underlying mapper
            rowMapper.sendInvalidations(invalidations);

            // queue to other local mappers' caches
            cachePropagator.propagateInvalidations(invalidations, cacheQueue);

            // queue as events for other repositories
            eventPropagator.propagateInvalidations(invalidations, eventQueue);

            // send event to local repository (synchronous)
            // only if not the server-side part of a remote client
            if (!forRemoteClient) {
                session.sendInvalidationEvent(invalidations, true);
            }
        }
    }

    /**
     * Used by the server to associate each mapper to a single event
     * invalidations queue per client repository.
     */
    public void setEventQueue(InvalidationsQueue eventQueue) {
        // don't remove the original global repository queue
        this.eventQueue = eventQueue;
        eventPropagator.addQueue(eventQueue);
        forRemoteClient = true;
    }

    /**
     * Sets the session, used for event propagation.
     */
    public void setSession(SessionImpl session) {
        this.session = session;
    }

    @Override
    public void clearCache() {
        ehCacheRemoveAll();
        localInvalidations.clear();
        rowMapper.clearCache();
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        try {
            rowMapper.rollback(xid);
        } finally {
            ehCacheRemoveAll();
            localInvalidations.clear();
        }
    }

    /*
     * ----- Batch -----
     */

    /*
     * Use those from the cache if available, read from the mapper for the rest.
     */
    @Override
    public List<? extends RowId> read(Collection<RowId> rowIds)
            throws StorageException {
        List<RowId> res = new ArrayList<RowId>(rowIds.size());
        // find which are in cache, and which not
        List<RowId> todo = new LinkedList<RowId>();
        for (RowId rowId : rowIds) {
            Row row = cacheGet(rowId);
            if (row == null) {
                todo.add(rowId);
            } else if (isAbsent(row)) {
                res.add(new RowId(rowId));
            } else {
                res.add(row);
            }
        }
        if (!todo.isEmpty()) {
            Split split = null;
            if (cacheStatistics) {
                Stopwatch stopWatch = SimonManager.getStopwatch(SW_SOR);
                split = stopWatch.start();
            }
            // ask missing ones to underlying row mapper
            List<? extends RowId> fetched = rowMapper.read(todo);
            // add them to the cache
            for (RowId rowId : fetched) {
                cachePutAbsentIfRowId(rowId);
            }
            // merge results
            res.addAll(fetched);
            if (split != null) {
                split.stop();
            }
        }
        return res;
    }

    /*
     * Save in the cache then pass all the writes to the mapper.
     */
    @Override
    public void write(RowBatch batch) throws StorageException {
        // we avoid gathering invalidations for a write-only table: fulltext
        for (Row row : batch.creates) {
            cachePut(row);
            if (!Model.FULLTEXT_TABLE_NAME.equals(row.tableName)) {
                // we need to send modified invalidations for created
                // fragments because other session's ABSENT fragments have
                // to be invalidated
                localInvalidations.addModified(new RowId(row));
            }
        }
        for (RowUpdate rowu : batch.updates) {
            cachePut(rowu.row);
            if (!Model.FULLTEXT_TABLE_NAME.equals(rowu.row.tableName)) {
                localInvalidations.addModified(new RowId(rowu.row));
            }
        }
        for (RowId rowId : batch.deletes) {
            if (rowId instanceof Row) {
                throw new AssertionError();
            }
            cachePutAbsent(rowId);
            if (!Model.FULLTEXT_TABLE_NAME.equals(rowId.tableName)) {
                localInvalidations.addDeleted(rowId);
            }
        }

        // propagate to underlying mapper
        rowMapper.write(batch);
    }

    /*
     * ----- Read -----
     */

    @Override
    public Row readSimpleRow(RowId rowId) throws StorageException {
        Row row = cacheGet(rowId);
        if (row == null) {
            row = rowMapper.readSimpleRow(rowId);
            cachePutAbsentIfNull(rowId, row);
            return row;
        } else if (isAbsent(row)) {
            return null;
        } else {
            return row;
        }
    }

    @Override
    public Serializable[] readCollectionRowArray(RowId rowId)
            throws StorageException {
        Row row = cacheGet(rowId);
        if (row == null) {
            Serializable[] array = rowMapper.readCollectionRowArray(rowId);
            assert array != null;
            row = new Row(rowId.tableName, rowId.id, array);
            cachePut(row);
            return row.values;
        } else if (isAbsent(row)) {
            return null;
        } else {
            return row.values;
        }
    }

    // TODO this API isn't cached well...
    @Override
    public Row readChildHierRow(Serializable parentId, String childName,
            boolean complexProp) throws StorageException {
        Row row = rowMapper.readChildHierRow(parentId, childName, complexProp);
        if (row != null) {
            cachePut(row);
        }
        return row;
    }

    // TODO this API isn't cached well...
    @Override
    public List<Row> readChildHierRows(Serializable parentId,
            boolean complexProp) throws StorageException {
        List<Row> rows = rowMapper.readChildHierRows(parentId, complexProp);
        for (Row row : rows) {
            cachePut(row);
        }
        return rows;
    }

    // TODO this API isn't cached well...
    @Override
    public List<Row> getVersionRows(Serializable versionableId)
            throws StorageException {
        List<Row> rows = rowMapper.getVersionRows(versionableId);
        for (Row row : rows) {
            cachePut(row);
        }
        return rows;
    }

    // TODO this API isn't cached well...
    @Override
    public List<Row> getProxyRows(Serializable searchId, boolean byTarget,
            Serializable parentId) throws StorageException {
        List<Row> rows = rowMapper.getProxyRows(searchId, byTarget, parentId);
        for (Row row : rows) {
            cachePut(row);
        }
        return rows;
    }

    /*
     * ----- Copy -----
     */

    @Override
    public CopyHierarchyResult copyHierarchy(IdWithTypes source,
            Serializable destParentId, String destName, Row overwriteRow)
            throws StorageException {
        CopyHierarchyResult result = rowMapper.copyHierarchy(source,
                destParentId, destName, overwriteRow);
        Invalidations invalidations = result.invalidations;
        if (invalidations.modified != null) {
            for (RowId rowId : invalidations.modified) {
                cacheRemove(rowId);
            }
        }
        if (invalidations.deleted != null) {
            for (RowId rowId : invalidations.deleted) {
                cacheRemove(rowId);
            }
        }
        return result;
    }

}
