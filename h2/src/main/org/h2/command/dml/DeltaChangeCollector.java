package org.h2.command.dml;

import org.h2.command.query.Returning;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.result.LocalResult;
import org.h2.result.ResultTarget;
import org.h2.table.Table;
import org.h2.value.Value;

import java.util.List;

public abstract class DeltaChangeCollector {
	public static DeltaChangeCollector defaultCollector(final SessionLocal session, final Table table) {
		return enrichWithLastIdentity( session, table,
				new NoopDeltaChangeCollector()
		);
	}

	public static DeltaChangeCollector dataChangeDeltaTableCollector(final SessionLocal session, final Table table, final ResultTarget result, final ResultOption statementResultOption) {
		return enrichWithLastIdentity( session, table,
				new DataChangeDeltaTableCollector( statementResultOption, result)
		);
	}

	public static DeltaChangeCollector generatedKeysCollector(final SessionLocal session, final Table table, final int[] indexes, final LocalResult result) {
		return enrichWithLastIdentity( session, table,
				new GeneratedKeysDeltaChangeCollector( indexes, result)
		);
	}

	public static DeltaChangeCollector returningDeltaChangeCollector(SessionLocal session, Returning returning, LocalResult result) {
		return enrichWithLastIdentity(session, returning.statement.getTable(),
				new ReturningDeltaChangeCollector(session, returning.getExpressions(), result)
		);
	}

	private static DeltaChangeCollector enrichWithLastIdentity(final SessionLocal session, final Table table, final DeltaChangeCollector delegate) {
		final Integer identityColumnId = session.getMode().takeInsertedIdentity && table.getIdentityColumn() != null ? table.getIdentityColumn().getColumnId() : null;
		if (identityColumnId != null) {
			return new CompositeDeltaChangeCollector(
					new LastIdentityDeltaChangeCollector(session, identityColumnId),
					delegate
			);
		} else {
			return delegate;
		}
	}

	public enum Action {
		DELETE,
		INSERT,
		UPDATE
	}

	/**
	 * Result option.
	 */
	public enum ResultOption {

		/**
		 * OLD row.
		 */
		OLD,

		/**
		 * NEW row with evaluated default expressions, but before triggers.
		 */
		NEW,

		/**
		 * FINAL rows after triggers.
		 */
		FINAL
	}

	public abstract void trigger(final Action action, final ResultOption resultOption, final Value[] values);

	private static class CompositeDeltaChangeCollector extends DeltaChangeCollector {
		private final List<DeltaChangeCollector> collectors;

		public CompositeDeltaChangeCollector(final DeltaChangeCollector... collectors) {
			this.collectors = List.of(collectors);
		}

		@Override
		public void trigger(Action action, ResultOption resultOption, Value[] values) {
			collectors.forEach(c -> c.trigger(action, resultOption, values));
		}
	}

	private static class GeneratedKeysDeltaChangeCollector extends DeltaChangeCollector {
		private final int[] indexes;
		private final ResultTarget result;

		public GeneratedKeysDeltaChangeCollector(final int[] indexes, final ResultTarget result) {
			this.indexes = indexes;
			this.result = result;
		}

		@Override
		public void trigger(Action action, ResultOption resultOption, Value[] values) {
			if (ResultOption.FINAL.equals(resultOption)) {
				int length = indexes.length;
				Value[] row = new Value[length];
				for ( int i = 0; i < length; i++ ) {
					row[i] = values[indexes[i]];
				}
				result.addRow( row );
			}
		}
	}

	private static class DataChangeDeltaTableCollector extends DeltaChangeCollector {
		private final ResultOption resultOption;
		private final ResultTarget result;

		public DataChangeDeltaTableCollector(final ResultOption resultOption, final ResultTarget result) {
			this.resultOption = resultOption;
			this.result = result;
		}

		@Override
		public void trigger(Action action, ResultOption resultOption, Value[] values) {
			if (this.resultOption.equals( resultOption ) ) {
				result.addRow( values );
			}
		}
	}

	private static class ReturningDeltaChangeCollector extends DeltaChangeCollector {
		private final SessionLocal session;
		private final List<Expression> expressions;
		private final LocalResult result;

        private ReturningDeltaChangeCollector(SessionLocal session, List<Expression> expressions, LocalResult result) {
            this.session = session;
            this.expressions = expressions;
            this.result = result;
        }

        @Override
		public void trigger(final Action action, final ResultOption resultOption, final Value[] values) {
			if (Action.DELETE.equals(action) && ResultOption.OLD.equals(resultOption)) {
				result.addRow(collectRow());
			} else if ((Action.INSERT.equals(action) || Action.UPDATE.equals(action)) && ResultOption.FINAL.equals(resultOption)) {
				result.addRow(collectRow());
			}
		}

		private Value[] collectRow() {
			final Value[] row = new Value[expressions.size()];
			for (int i = 0; i < expressions.size(); i++) {
				row[i] = expressions.get(i).getValue(session);
			}
			return row;
		}
	}

	private static class LastIdentityDeltaChangeCollector extends DeltaChangeCollector {
		private final SessionLocal session;
		private final int identityColumn;

		public LastIdentityDeltaChangeCollector(final SessionLocal session, final int identityColumn) {
			this.session = session;
			this.identityColumn = identityColumn;
		}

		@Override
		public void trigger(Action action, ResultOption resultOption, Value[] values) {
			if (Action.INSERT.equals(action) && ResultOption.FINAL.equals(resultOption)) {
				session.setLastIdentity(values[identityColumn]);
			}
		}
	}

	private static class NoopDeltaChangeCollector extends DeltaChangeCollector {
		@Override
		public void trigger(Action action, ResultOption resultOption, Value[] values) {
		}
	}
}
