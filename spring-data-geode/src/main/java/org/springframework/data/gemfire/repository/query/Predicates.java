/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.gemfire.repository.query;

import java.util.Iterator;

import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.Part.Type;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;

class Predicates implements Predicate {

	protected static final String AND_TEMPLATE = "%1$s AND %2$s";
	protected static final String OR_TEMPLATE = "%1$s OR %2$s";

	/**
	 * Factory method used to construct a new {@link Predicates} for the given {@link Part}
	 * and collection of {@link Iterator Indexes}.
	 *
	 * @param part {@link Part} of the query; must not be {@literal null}.
	 * @param indexes {@link Iterator} of indexes refering to parameter placeholders in the query;
	 * must not be {@literal null}.
	 * @return a new instance of {@link Predicates} wrapping the {@literal WHERE} clause condition expression
	 * ({@link Part}).
	 * @throws IllegalArgumentException if {@link Part} or {@link Iterator Indexes} are {@literal null}.
	 * @see org.springframework.data.repository.query.parser.Part
	 * @see java.util.Iterator
	 * @see AtomicPredicate
	 * @see #Predicates(Predicate)
	 */
	public static Predicates create(@NonNull Part part, @NonNull Iterator<Integer> indexes) {
		return new Predicates(new AtomicPredicate(part, indexes));
	}

	private static Predicates create(@NonNull Predicate predicate) {
		return new Predicates(predicate);
	}

	@NonNull
	private final Predicate current;

	/**
	 * Constructs a new instance of {@link Predicates} initialized with the given {@link Predicate}
	 * as the current instance.
	 *
	 * @param predicate {@link Predicate} used as the current instance; must not be {@literal null}.
	 * @see org.springframework.data.gemfire.repository.query.Predicate
	 */
	private Predicates(@NonNull Predicate predicate) {
		this.current = predicate;
	}

	/**
	 * {@literal AND} concatenates the given {@link Predicate} to the current {@link Predicate}.
	 *
	 * @param predicate {@link Predicate} to concatenate with the current one; must not be {@literal null}.
	 * @return a new instance of {@link Predicates} representing an {@literal AND} condition.
	 * @see org.springframework.data.gemfire.repository.query.Predicates
	 * @see org.springframework.data.gemfire.repository.query.Predicate
	 */
	public Predicates and(@NonNull Predicate predicate) {
		return concatenate(predicate, AND_TEMPLATE);
	}

	/**
	 * {@literal OR} concatenates the given {@link Predicate} to the current {@link Predicate}.
	 *
	 * @param predicate {@link Predicate} to concatenate with the current one; must not be {@literal null}.
	 * @return a new instance of {@link Predicates} representing an {@literal OR} condition.
	 * @see org.springframework.data.gemfire.repository.query.Predicates
	 * @see org.springframework.data.gemfire.repository.query.Predicate
	 */
	public Predicates or(@NonNull Predicate predicate) {
		return concatenate(predicate, OR_TEMPLATE);
	}

	private Predicates concatenate(Predicate predicate, String template) {
		return create(alias -> String.format(template,
			Predicates.this.current.toString(alias), predicate.toString(alias)));
	}

	/**
	 * @inheritDoc
	 */
	@Override
	public String toString(String alias) {
		return this.current.toString(alias);
	}

	/**
	 * Predicate type used to create a query predicate expression for a {@link Part}.
	 *
	 * @author Oliver Gierke
	 * @author John Blum
	 */
	public static class AtomicPredicate implements Predicate {

		private final Iterator<Integer> indexes;

		private final Part part;

		/**
		 * Constructs a new instance of {@link AtomicPredicate} initialized with the query predicate {@link Part}
		 * and {@link Iterator} of indexes referencing query parameters.
		 *
		 * @param part must not be {@literal null}.
		 * @param indexes must not be {@literal null}.
		 */
		public AtomicPredicate(Part part, Iterator<Integer> indexes) {

			Assert.notNull(part, "Query Predicate Part must not be null");
			Assert.notNull(indexes, "Iterator of numeric-based, indexed query parameter placeholders must not be null");

			this.part = part;
			this.indexes = indexes;
		}

		/**
		 * Builds a {@link String conditional expression} as a query predicate for the entity property
		 * in the {@literal WHERE} clause of the OQL query statement.
		 *
		 * @see org.springframework.data.gemfire.repository.query.Predicate#toString(java.lang.String)
		 */
		@Override
		public String toString(String alias) {

			if (isIgnoreCase()) {
				return String.format("%s.equalsIgnoreCase($%d)", resolveProperty(alias), this.indexes.next());
			}
			else {

				Type partType = this.part.getType();

				switch (partType) {
					case IS_NULL:
					case IS_NOT_NULL:
						return String.format("%s %s NULL", resolveProperty(alias), resolveOperator(partType));
					case FALSE:
					case TRUE:
						return String.format("%s %s %s", resolveProperty(alias), resolveOperator(partType),
							Type.TRUE.equals(partType));
					default:
						return String.format("%s %s $%d", resolveProperty(alias), resolveOperator(partType),
							this.indexes.next());
				}
			}
		}

		boolean isIgnoreCase() {

			switch (this.part.shouldIgnoreCase()) {
				case ALWAYS:
				case WHEN_POSSIBLE:
					return true;
				case NEVER:
				default:
					return false;
			}
		}

		String resolveAlias(String alias) {
			return alias != null ? alias : QueryBuilder.DEFAULT_ALIAS;
		}

		/**
		 * Resolves the given {@link Type} as an GemFire OQL operator.
		 *
		 * @param partType the conditional expression (e.g. 'IN') in the query method name.
		 * @return a GemFire OQL operator.
		 */
		String resolveOperator(Type partType) {

			switch (partType) {
				// Equality - Is
				case FALSE:
				case IS_NULL:
				case SIMPLE_PROPERTY:
				case TRUE:
					return "=";
				// Equality - Is Not
				case IS_NOT_NULL:
				case NEGATING_SIMPLE_PROPERTY:
					return "!=";
				// Relational Comparison
				case GREATER_THAN:
					return ">";
				case GREATER_THAN_EQUAL:
					return ">=";
				case LESS_THAN:
					return "<";
				case LESS_THAN_EQUAL:
					return "<=";
				// Set Containment
				case IN:
					return "IN SET";
				case NOT_IN:
					return "NOT IN SET";
				// Wildcard Matching
				case LIKE:
				case STARTING_WITH:
				case ENDING_WITH:
				case CONTAINING:
					return "LIKE";
				default:
					throw new IllegalArgumentException(String.format("Unsupported operator [%s]", partType));
			}
		}

		String resolveProperty(String alias) {
			return String.format("%1$s.%2$s", resolveAlias(alias), part.getProperty().toDotPath());
		}
	}
}
