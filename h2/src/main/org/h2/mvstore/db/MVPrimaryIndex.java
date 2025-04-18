/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.api.ErrorCode;
import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.Database;
import org.h2.engine.SessionLocal;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.index.SingleRowCursor;
import org.h2.message.DbException;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.tx.Transaction;
import org.h2.mvstore.tx.TransactionMap;
import org.h2.mvstore.tx.TransactionMap.TMIterator;
import org.h2.mvstore.type.LongDataType;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueDecfloat;
import org.h2.value.ValueLob;
import org.h2.value.VersionedValue;

/**
 * A table stored in a MVStore.
 */
public final class MVPrimaryIndex extends MVIndex<Long, SearchRow> {

    private final MVTable mvTable;
    private final String mapName;
    private final TransactionMap<Long, SearchRow> dataMap;
    private final AtomicLong lastKey = new AtomicLong();
    private int mainIndexColumn = SearchRow.ROWID_INDEX;

    public MVPrimaryIndex(Database db, MVTable table, int id, IndexColumn[] columns, IndexType indexType) {
        super(table, id, table.getName() + "_DATA", columns, 0, indexType);
        this.mvTable = table;
        RowDataType valueType = table.getRowFactory().getRowDataType();
        mapName = "table." + getId();
        Transaction t = mvTable.getTransactionBegin();
        dataMap = t.openMap(mapName, LongDataType.INSTANCE, valueType);
        dataMap.map.setVolatile(!table.isPersistData() || !indexType.isPersistent());
        if (!db.isStarting()) {
            dataMap.clear();
        }
        t.commit();
        Long k = dataMap.map.lastKey();    // include uncommitted keys as well
        lastKey.set(k == null ? 0 : k);
    }

    @Override
    public String getCreateSQL() {
        return null;
    }

    @Override
    public String getPlanSQL() {
        return table.getSQL(new StringBuilder(), TRACE_SQL_FLAGS).append(".tableScan").toString();
    }

    public void setMainIndexColumn(int mainIndexColumn) {
        this.mainIndexColumn = mainIndexColumn;
    }

    public int getMainIndexColumn() {
        return mainIndexColumn;
    }

    @Override
    public void close(SessionLocal session) {
        // ok
    }

    @Override
    public void add(SessionLocal session, Row row) {
        if (mainIndexColumn == SearchRow.ROWID_INDEX) {
            if (row.getKey() == 0) {
                row.setKey(lastKey.incrementAndGet());
            }
        } else {
            long c = row.getValue(mainIndexColumn).getLong();
            row.setKey(c);
        }

        if (mvTable.getContainsLargeObject()) {
            for (int i = 0, len = row.getColumnCount(); i < len; i++) {
                Value v = row.getValue(i);
                if (v instanceof ValueLob) {
                    ValueLob lob = ((ValueLob) v).copy(database, getId());
                    session.removeAtCommitStop(lob);
                    if (v != lob) {
                        row.setValue(i, lob);
                    }
                }
            }
        }

        TransactionMap<Long,SearchRow> map = getMap(session);
        long rowKey = row.getKey();
        try {
            Row old = (Row)map.putIfAbsent(rowKey, row);
            if (old != null) {
                int errorCode = ErrorCode.CONCURRENT_UPDATE_1;
                if (map.getImmediate(rowKey) != null || map.getFromSnapshot(rowKey) != null) {
                    // committed
                    errorCode = ErrorCode.DUPLICATE_KEY_1;
                }
                DbException e = DbException.get(errorCode,
                        getDuplicatePrimaryKeyMessage(mainIndexColumn).append(' ').append(old).toString());
                e.setSource(this);
                throw e;
            }
        } catch (MVStoreException e) {
            throw mvTable.convertException(e);
        }
        // because it's possible to directly update the key using the _rowid_
        // syntax
        long last;
        while (rowKey > (last = lastKey.get())) {
            if(lastKey.compareAndSet(last, rowKey)) break;
        }
    }

    @Override
    public void remove(SessionLocal session, Row row) {
        if (mvTable.getContainsLargeObject()) {
            for (int i = 0, len = row.getColumnCount(); i < len; i++) {
                Value v = row.getValue(i);
                if (v instanceof ValueLob) {
                    session.removeAtCommit((ValueLob) v);
                }
            }
        }
        TransactionMap<Long,SearchRow> map = getMap(session);
        try {
            Row existing = (Row)map.remove(row.getKey());
            if (existing == null) {
                StringBuilder builder = new StringBuilder();
                getSQL(builder, TRACE_SQL_FLAGS).append(": ").append(row.getKey());
                throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, builder.toString());
            }
        } catch (MVStoreException e) {
            throw mvTable.convertException(e);
        }
    }

    @Override
    public void update(SessionLocal session, Row oldRow, Row newRow) {
        if (mainIndexColumn != SearchRow.ROWID_INDEX) {
            long c = newRow.getValue(mainIndexColumn).getLong();
            newRow.setKey(c);
        }
        long key = oldRow.getKey();
        assert mainIndexColumn != SearchRow.ROWID_INDEX || key != 0;
        assert key == newRow.getKey() : key + " != " + newRow.getKey();
        if (mvTable.getContainsLargeObject()) {
            for (int i = 0, len = oldRow.getColumnCount(); i < len; i++) {
                Value oldValue = oldRow.getValue(i);
                Value newValue = newRow.getValue(i);
                if (oldValue != newValue) {
                    if (oldValue instanceof ValueLob) {
                        session.removeAtCommit((ValueLob) oldValue);
                    }
                    if (newValue instanceof ValueLob) {
                        ValueLob lob = ((ValueLob) newValue).copy(database, getId());
                        session.removeAtCommitStop(lob);
                        if (newValue != lob) {
                            newRow.setValue(i, lob);
                        }
                    }
                }
            }
        }

        TransactionMap<Long,SearchRow> map = getMap(session);
        try {
            Row existing = (Row)map.put(key, newRow);
            if (existing == null) {
                StringBuilder builder = new StringBuilder();
                getSQL(builder, TRACE_SQL_FLAGS).append(": ").append(key);
                throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1, builder.toString());
            }
        } catch (MVStoreException e) {
            throw mvTable.convertException(e);
        }


        // because it's possible to directly update the key using the _rowid_
        // syntax
        if (newRow.getKey() > lastKey.get()) {
            lastKey.set(newRow.getKey());
        }
    }

    /**
     * Lock a single row.
     *
     * @param session database session
     * @param row to lock
     * @param timeoutMillis
     *            timeout in milliseconds, {@code -1} for default, {@code -2} to
     *            skip locking if row is already locked by another session
     * @return row object if it exists
     */
    Row lockRow(SessionLocal session, Row row, int timeoutMillis) {
        TransactionMap<Long,SearchRow> map = getMap(session);
        long key = row.getKey();
        return lockRow(map, key, timeoutMillis);
    }

    private Row lockRow(TransactionMap<Long,SearchRow> map, long key, int timeoutMillis) {
        try {
            return setRowKey((Row) map.lock(key, timeoutMillis), key);
        } catch (MVStoreException ex) {
            throw mvTable.convertLockException(ex);
        }
    }

    @Override
    public Cursor find(SessionLocal session, SearchRow first, SearchRow last, boolean reverse) {
        Long min, max;
        Value v;
        if (first == null) {
            min = null;
        } else if (mainIndexColumn == SearchRow.ROWID_INDEX || (v = first.getValue(mainIndexColumn)) == null) {
            min = first.getKey();
        } else {
            switch (v.getValueType()) {
            case Value.NULL:
                return SingleRowCursor.EMPTY;
            case Value.REAL:
            case Value.DOUBLE: {
                double d = v.getDouble();
                if (Double.isNaN(d)) {
                    return SingleRowCursor.EMPTY;
                } else {
                    min = (long) d;
                }
                break;
            }
            case Value.DECFLOAT:
                if (!((ValueDecfloat) v).isFinite()) {
                    if (v == ValueDecfloat.NEGATIVE_INFINITY) {
                        min = null;
                    } else {
                        return SingleRowCursor.EMPTY;
                    }
                    break;
                }
                //$FALL-THROUGH$
            case Value.NUMERIC: {
                BigDecimal bd = v.getBigDecimal();
                if (bd.compareTo(Value.MAX_LONG_DECIMAL) > 0) {
                    return SingleRowCursor.EMPTY;
                } else if (bd.compareTo(Value.MIN_LONG_DECIMAL) < 0) {
                    min = null;
                } else {
                    min = bd.longValue();
                }
                break;
            }
            default:
                min = v.getLong();
            }
        }
        if (last == null) {
            max = null;
        } else if (mainIndexColumn == SearchRow.ROWID_INDEX || (v = last.getValue(mainIndexColumn)) == null) {
            max = last.getKey();
        } else {
            switch (v.getValueType()) {
            case Value.NULL:
                return SingleRowCursor.EMPTY;
            case Value.REAL:
            case Value.DOUBLE: {
                double d = v.getDouble();
                if (Double.isNaN(d)) {
                    max = null;
                } else {
                    max = (long) d;
                }
                break;
            }
            case Value.DECFLOAT:
                if (!((ValueDecfloat) v).isFinite()) {
                    if (v == ValueDecfloat.NEGATIVE_INFINITY) {
                        return SingleRowCursor.EMPTY;
                    } else {
                        max = null;
                    }
                    break;
                }
                //$FALL-THROUGH$
            case Value.NUMERIC: {
                BigDecimal bd = v.getBigDecimal();
                if (bd.compareTo(Value.MAX_LONG_DECIMAL) > 0) {
                    max = null;
                } else if (bd.compareTo(Value.MIN_LONG_DECIMAL) < 0) {
                    return SingleRowCursor.EMPTY;
                } else {
                    max = bd.longValue();
                }
                break;
            }
            default:
                max = v.getLong();
            }
        }
        TransactionMap<Long,SearchRow> map = getMap(session);
        if (min != null && max != null && min.longValue() == max.longValue()) {
            return new SingleRowCursor(setRowKey((Row) map.getFromSnapshot(min), min));
        }
        return new MVStoreCursor(map.entryIterator(min, max, reverse));
    }

    @Override
    public MVTable getTable() {
        return mvTable;
    }

    @Override
    public Row getRow(SessionLocal session, long key) {
        TransactionMap<Long,SearchRow> map = getMap(session);
        Row row = (Row) map.getFromSnapshot(key);
        if (row == null) {
            throw DbException.get(ErrorCode.ROW_NOT_FOUND_IN_PRIMARY_INDEX, getTraceSQL(), String.valueOf(key));
        }
        return setRowKey(row, key);
    }

    @Override
    public double getCost(SessionLocal session, int[] masks,
            TableFilter[] filters, int filter, SortOrder sortOrder,
            AllColumnsForPlan allColumnsSet, boolean isSelectCommand) {
        try {
            return 10 * getCostRangeIndex(masks, dataMap.sizeAsLongMax(),
                    filters, filter, sortOrder, true, allColumnsSet, isSelectCommand);
        } catch (MVStoreException e) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED, e);
        }
    }

    @Override
    public int getColumnIndex(Column col) {
        // can not use this index - use the delegate index instead
        return -1;
    }

    @Override
    public boolean isFirstColumn(Column column) {
        return column.getColumnId() == SearchRow.ROWID_INDEX && column.getTable() == table;
    }

    @Override
    public void remove(SessionLocal session) {
        TransactionMap<Long,SearchRow> map = getMap(session);
        if (!map.isClosed()) {
            Transaction t = session.getTransaction();
            t.removeMap(map);
        }
    }

    @Override
    public void truncate(SessionLocal session) {
        if (mvTable.getContainsLargeObject()) {
            database.getLobStorage().removeAllForTable(table.getId());
        }
        getMap(session).clear();
    }

    @Override
    public boolean canGetFirstOrLast() {
        return true;
    }

    @Override
    public Cursor findFirstOrLast(SessionLocal session, boolean first) {
        TransactionMap<Long, SearchRow> map = getMap(session);
        Entry<Long, SearchRow> entry = first ? map.firstEntry() : map.lastEntry();
        return entry != null ? new SingleRowCursor(setRowKey((Row) entry.getValue(), entry.getKey()))
                : SingleRowCursor.EMPTY;
    }

    @Override
    public boolean needRebuild() {
        return false;
    }

    @Override
    public long getRowCount(SessionLocal session) {
        return getMap(session).sizeAsLong();
    }

    /**
     * The maximum number of rows, including uncommitted rows of any session.
     *
     * @return the maximum number of rows
     */
    public long getRowCountMax() {
        return dataMap.sizeAsLongMax();
    }

    @Override
    public long getRowCountApproximation(SessionLocal session) {
        return getRowCountMax();
    }

    public String getMapName() {
        return mapName;
    }

    @Override
    public void addRowsToBuffer(List<Row> rows, String bufferName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addBufferedRows(List<String> bufferNames) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRowIdIndex() {
        return true;
    }

    /**
     * Get the map to store the data.
     *
     * @param session the session
     * @return the map
     */
    TransactionMap<Long,SearchRow> getMap(SessionLocal session) {
        if (session == null) {
            return dataMap;
        }
        Transaction t = session.getTransaction();
        return dataMap.getInstance(t);
    }

    @Override
    public MVMap<Long, VersionedValue<SearchRow>> getMVMap() {
        return dataMap.map;
    }

    private static Row setRowKey(Row row, long key) {
        if (row != null && row.getKey() == 0) {
            row.setKey(key);
        }
        return row;
    }

    /**
     * A cursor.
     */
    static final class MVStoreCursor implements Cursor {

        private final TMIterator<Long, SearchRow, Entry<Long, SearchRow>> it;
        private Entry<Long, SearchRow> current;
        private Row row;

        public MVStoreCursor(TMIterator<Long, SearchRow, Entry<Long, SearchRow>> it) {
            this.it = it;
        }

        @Override
        public Row get() {
            if (row == null) {
                if (current != null) {
                    row = (Row)current.getValue();
                    if (row.getKey() == 0) {
                        row.setKey(current.getKey());
                    }
                }
            }
            return row;
        }

        @Override
        public SearchRow getSearchRow() {
            return get();
        }

        @Override
        public boolean next() {
            current = it.fetchNext();
            row = null;
            return current != null;
        }

        @Override
        public boolean previous() {
            throw DbException.getUnsupportedException("previous");
        }
    }
}
