package org.springframework.data.gemfire.repository.query;

@FunctionalInterface
interface Predicate {

	String toString(String alias);

}
