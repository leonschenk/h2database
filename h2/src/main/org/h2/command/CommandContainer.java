/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.h2.api.DatabaseEventListener;
import org.h2.api.ErrorCode;
import org.h2.command.dml.DataChangeStatement;
import org.h2.command.dml.DeltaChangeCollector;
import org.h2.engine.Database;
import org.h2.engine.DbObject;
import org.h2.engine.DbSettings;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.Parameter;
import org.h2.expression.ParameterInterface;
import org.h2.index.Index;
import org.h2.message.DbException;
import org.h2.result.LocalResult;
import org.h2.result.ResultInterface;
import org.h2.result.ResultWithGeneratedKeys;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.util.StringUtils;
import org.h2.util.Utils;

/**
 * Represents a single SQL statements.
 * It wraps a prepared statement.
 */
public class CommandContainer extends Command {

    private Prepared prepared;
    private boolean readOnlyKnown;
    private boolean readOnly;

    public CommandContainer(SessionLocal session, String sql, Prepared prepared) {
        super(session, sql);
        prepared.setCommand(this);
        this.prepared = prepared;
    }

    @Override
    public ArrayList<? extends ParameterInterface> getParameters() {
        ArrayList<Parameter> parameters = prepared.getParameters();
        if (!parameters.isEmpty() && prepared.isWithParamValues()) {
            parameters = new ArrayList<>();
        }
        return parameters;
    }

    @Override
    public boolean isTransactional() {
        return prepared.isTransactional();
    }

    @Override
    public boolean isQuery() {
        return prepared.isQuery();
    }

    private void recompileIfRequired() {
        if (prepared.needRecompile()) {
            // TODO test with 'always recompile'
            prepared.setModificationMetaId(0);
            String sql = prepared.getSQL();
            ArrayList<Token> tokens = prepared.getSQLTokens();
            Parser parser = new Parser(session);
            parser.setSuppliedParameters(prepared.getParameters());
            prepared = parser.parse(sql, tokens);
            long mod = prepared.getModificationMetaId();
            prepared.setModificationMetaId(0);
            prepared.prepare();
            prepared.setModificationMetaId(mod);
        }
    }

    @Override
    public ResultWithGeneratedKeys update(Object generatedKeysRequest) {
        recompileIfRequired();
        Database database = getDatabase();
        setProgress(database, DatabaseEventListener.STATE_STATEMENT_START);
        start();
        prepared.checkParameters();
        ResultWithGeneratedKeys result;
        if (generatedKeysRequest != null && !Boolean.FALSE.equals(generatedKeysRequest)) {
            if (prepared instanceof DataChangeStatement && prepared.getType() != CommandInterface.DELETE) {
                result = executeUpdateWithGeneratedKeys((DataChangeStatement) prepared,
                        generatedKeysRequest);
            } else {
                result = new ResultWithGeneratedKeys.WithKeys(prepared.update(), new LocalResult());
            }
        } else {
            result = ResultWithGeneratedKeys.of(prepared.update());
        }
        prepared.trace(database, startTimeNanos, result.getUpdateCount());
        setProgress(database, DatabaseEventListener.STATE_STATEMENT_END);
        return result;
    }

    private ResultWithGeneratedKeys executeUpdateWithGeneratedKeys(DataChangeStatement statement,
            Object generatedKeysRequest) {
        Database db = getDatabase();
        Table table = statement.getTable();
        ArrayList<ExpressionColumn> expressionColumns;
        if (Boolean.TRUE.equals(generatedKeysRequest)) {
            expressionColumns = Utils.newSmallArrayList();
            Column[] columns = table.getColumns();
            Index primaryKey = table.findPrimaryKey();
            for (Column column : columns) {
                Expression e;
                if (column.isIdentity()
                        || ((e = column.getEffectiveDefaultExpression()) != null && !e.isConstant())
                        || (primaryKey != null && primaryKey.getColumnIndex(column) >= 0)) {
                    expressionColumns.add(new ExpressionColumn(db, column));
                }
            }
        } else if (generatedKeysRequest instanceof int[]) {
            int[] indexes = (int[]) generatedKeysRequest;
            Column[] columns = table.getColumns();
            int cnt = columns.length;
            expressionColumns = new ArrayList<>(indexes.length);
            for (int idx : indexes) {
                if (idx < 1 || idx > cnt) {
                    throw DbException.get(ErrorCode.COLUMN_NOT_FOUND_1, "Index: " + idx);
                }
                expressionColumns.add(new ExpressionColumn(db, columns[idx - 1]));
            }
        } else if (generatedKeysRequest instanceof String[]) {
            String[] names = (String[]) generatedKeysRequest;
            expressionColumns = new ArrayList<>(names.length);
            for (String name : names) {
                Column column = table.findColumn(name);
                if (column == null) {
                    DbSettings settings = db.getSettings();
                    if (settings.databaseToUpper) {
                        column = table.findColumn(StringUtils.toUpperEnglish(name));
                    } else if (settings.databaseToLower) {
                        column = table.findColumn(StringUtils.toLowerEnglish(name));
                    }
                    search: if (column == null) {
                        for (Column c : table.getColumns()) {
                            if (c.getName().equalsIgnoreCase(name)) {
                                column = c;
                                break search;
                            }
                        }
                        throw DbException.get(ErrorCode.COLUMN_NOT_FOUND_1, name);
                    }
                }
                expressionColumns.add(new ExpressionColumn(db, column));
            }
        } else {
            throw DbException.getInternalError();
        }
        int columnCount = expressionColumns.size();
        if (columnCount == 0) {
            return new ResultWithGeneratedKeys.WithKeys(statement.update(), new LocalResult());
        }
        int[] indexes = new int[columnCount];
        ExpressionColumn[] expressions = expressionColumns.toArray(new ExpressionColumn[0]);
        for (int i = 0; i < columnCount; i++) {
            indexes[i] = expressions[i].getColumn().getColumnId();
        }
        LocalResult result = new LocalResult(session, expressions, columnCount, columnCount);
        return new ResultWithGeneratedKeys.WithKeys( statement.update(DeltaChangeCollector.generatedKeysCollector( session, table, indexes, result)), result);
    }

    @Override
    public ResultInterface query(long maxrows) {
        recompileIfRequired();
        Database database = getDatabase();
        setProgress(database, DatabaseEventListener.STATE_STATEMENT_START);
        start();
        prepared.checkParameters();
        ResultInterface result = prepared.query(maxrows);
        prepared.trace(database, startTimeNanos, result.isLazy() ? 0 : result.getRowCount());
        setProgress(database, DatabaseEventListener.STATE_STATEMENT_END);
        return result;
    }

    @Override
    public boolean isReadOnly() {
        if (!readOnlyKnown) {
            readOnly = prepared.isReadOnly();
            readOnlyKnown = true;
        }
        return readOnly;
    }

    @Override
    public ResultInterface queryMeta() {
        return prepared.queryMeta();
    }

    @Override
    public boolean isCacheable() {
        return prepared.isCacheable();
    }

    @Override
    public int getCommandType() {
        return prepared.getType();
    }

    @Override
    public Set<DbObject> getDependencies() {
        HashSet<DbObject> dependencies = new HashSet<>();
        prepared.collectDependencies(dependencies);
        return dependencies;
    }

    @Override
    protected boolean isRetryable() {
        return prepared.isRetryable();
    }

}
