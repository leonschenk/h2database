/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import java.util.HashSet;

import org.h2.api.Trigger;
import org.h2.command.CommandInterface;
import org.h2.command.dml.DeltaChangeCollector.ResultOption;
import org.h2.command.query.AllColumnsForPlan;
import org.h2.engine.DbObject;
import org.h2.engine.Right;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.message.DbException;
import org.h2.result.LocalResult;
import org.h2.result.Row;
import org.h2.table.PlanItem;
import org.h2.table.Table;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueNull;

import static org.h2.command.dml.DeltaChangeCollector.Action.DELETE;

/**
 * This class represents the statement
 * DELETE
 */
public final class Delete extends FilteredDataChangeStatement {

    public Delete(SessionLocal session) {
        super(session);
    }

    @Override
    public long update(final DeltaChangeCollector deltaChangeCollector) {
        targetTableFilter.startQuery(session);
        targetTableFilter.reset();
        Table table = targetTableFilter.getTable();
        session.getUser().checkTableRight(table, Right.DELETE);
        table.fire(session, Trigger.DELETE, true);
        table.lock(session, Table.WRITE_LOCK);
        long limitRows = -1;
        if (fetchExpr != null) {
            Value v = fetchExpr.getValue(session);
            if (v == ValueNull.INSTANCE || (limitRows = v.getLong()) < 0) {
                throw DbException.getInvalidValueException("FETCH", v);
            }
        }
        try (LocalResult rows = LocalResult.forTable(session, table)) {
            setCurrentRowNumber(0);
            long count = 0;
            while (nextRow(limitRows, count)) {
                Row row = lockAndRecheckCondition();
                if (row != null) {
                    deltaChangeCollector.trigger(DELETE, ResultOption.OLD, row.getValueList());
                    if (!table.fireRow() || !table.fireBeforeRow(session, row, null)) {
                        rows.addRowForTable(row);
                    }
                    count++;
                }
            }
            rows.done();
            long rowScanCount = 0;
            while (rows.next()) {
                if ((++rowScanCount & 127) == 0) {
                    checkCanceled();
                }
                Row row = rows.currentRowForTable();
                table.removeRow(session, row);
            }
            if (table.fireRow()) {
                for (rows.reset(); rows.next(); ) {
                    table.fireAfterRow(session, rows.currentRowForTable(), null, false);
                }
            }
            table.fire(session, Trigger.DELETE, false);
            return count;
        }
    }

    @Override
    public StringBuilder getPlanSQL(StringBuilder builder, int sqlFlags) {
        targetTableFilter.getPlanSQL(builder.append("DELETE FROM "), false, sqlFlags);
        return appendFilterCondition(builder, sqlFlags);
    }

    @Override
    void doPrepare() {
        if (condition != null) {
            condition.mapColumns(targetTableFilter, 0, Expression.MAP_INITIAL);
            condition = condition.optimizeCondition(session);
            if (condition != null) {
                condition.createIndexConditions(session, targetTableFilter);
            }
        }
        TableFilter[] filters = new TableFilter[]{targetTableFilter};
        PlanItem item = targetTableFilter.getBestPlanItem(session, filters, 0, new AllColumnsForPlan(filters),
                /* isSelectCommand */false);
        targetTableFilter.setPlanItem(item);
        targetTableFilter.prepare();
    }

    @Override
    public int getType() {
        return CommandInterface.DELETE;
    }

    @Override
    public String getStatementName() {
        return "DELETE";
    }

    @Override
    public void collectDependencies(HashSet<DbObject> dependencies) {
        ExpressionVisitor visitor = ExpressionVisitor.getDependenciesVisitor(dependencies);
        if (condition != null) {
            condition.isEverything(visitor);
        }
    }

}
