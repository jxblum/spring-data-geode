/*
 * Copyright 2020 the original author or authors.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import org.apache.geode.cache.Region;

/**
 * Unit Tests for {@link PagedQueryString}.
 *
 * @author John Blum
 * @see org.junit.Test
 * @see org.mockito.Mockito
 * @see org.apache.geode.cache.Region
 * @see org.springframework.data.gemfire.repository.query.PagedQueryString
 * @since 2.4.0
 */
public class PagedQueryStringUnitTests {

	@Test
	public void basicKeysAndValuesQueriesAreCorrect() {

		String query = "SELECT * FROM /Example e";

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("/Example").when(mockRegion).getFullPath();

		PagedQueryString queryString = PagedQueryString.of(query);

		assertThat(queryString).isNotNull();
		assertThat(queryString.getQuery()).isEqualTo(query);
		assertThat(queryString.getQueryMethod().orElse(null)).isNull();
		assertThat(queryString.getRegion().orElse(null)).isNull();
		assertThat(queryString.getRepositoryQuery().orElse(null)).isNull();

		String expectedKeysQuery = "SELECT DISTINCT entry.key FROM /Example.entrySet entry ORDER BY entry.key ASC";
		String actualKeysQuery = queryString.getKeysQuery(mockRegion);

		assertThat(actualKeysQuery).isEqualTo(expectedKeysQuery);

		String expectedValuesQuery = "SELECT DISTINCT *"
			+ " FROM /Example.entrySet entry"
			+ " WHERE entry.key IN SET (2, 4, 8)"
			+ " ORDER BY entry.key ASC";

		Iterable<Integer> keys = Arrays.asList(2, 4, 8);

		String actualValuesQuery = queryString.getValuesQuery(mockRegion, keys);

		assertThat(actualValuesQuery).isEqualTo(expectedValuesQuery);

		verify(mockRegion, atLeastOnce()).getFullPath();
	}

	@Test
	public void filteredAndOrderedKeysAndValuesQueriesAreCorrect() {

		String query = "SELECT * FROM /Example e WHERE e.id = $1 ORDER BY e.name ASC";

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("/Example").when(mockRegion).getFullPath();

		PagedQueryString queryString = PagedQueryString.of(query);

		assertThat(queryString).isNotNull();
		assertThat(queryString.getQuery()).isEqualTo(query);
		assertThat(queryString.getQueryMethod().orElse(null)).isNull();
		assertThat(queryString.getRegion().orElse(null)).isNull();
		assertThat(queryString.getRepositoryQuery().orElse(null)).isNull();

		String expectedKeysQuery = "SELECT DISTINCT entry.key"
			+ " FROM /Example.entrySet entry"
			+ " WHERE entry.value.id = $1"
			+ " ORDER BY entry.value.name ASC";

		String actualKeysQuery = queryString.getKeysQuery(mockRegion);

		assertThat(actualKeysQuery).isEqualTo(expectedKeysQuery);

		String expectedValuesQuery = "SELECT DISTINCT *"
			+ " FROM /Example.entrySet entry"
			+ " WHERE entry.key IN SET (1, 2)"
			+ " ORDER BY entry.value.name ASC";

		List<Integer> keys = Arrays.asList(1, 2);

		String actualValuesQuery = queryString.getValuesQuery(mockRegion, keys);

		assertThat(actualValuesQuery).isEqualTo(expectedValuesQuery);

		verify(mockRegion, atLeastOnce()).getFullPath();
	}

	@Test
	public void metadataBasedFilteredOrderedAndLimitedKeysAndValuesQueriesIsCorrect() {

		String query = "<TRACE> <HINT stateIdx> IMPORT example.Customer"
			+ " SELECT c.name, c.dob, c.city"
			+ " FROM /Customers c"
			+ " WHERE c.age >= $1 AND c.state = $2"
			+ " ORDER BY c.name ASC, c.dob DESC"
			+ " LIMIT 20";

		Region<?, ?> mockRegion = mock(Region.class);

		doReturn("/Customers").when(mockRegion).getFullPath();

		PagedQueryString queryString = PagedQueryString.of(query)
			.withRegion(mockRegion);

		assertThat(queryString).isNotNull();
		assertThat(queryString.getQuery()).isEqualTo(query);
		assertThat(queryString.getQueryMethod().orElse(null)).isNull();
		assertThat(queryString.getRegion().orElse(null)).isEqualTo(mockRegion);
		assertThat(queryString.getRepositoryQuery().orElse(null)).isNull();

		String expectedKeysQuery = "<TRACE> <HINT stateIdx> IMPORT example.Customer"
			+ " SELECT DISTINCT entry.key"
			+ " FROM /Customers.entrySet entry"
			+ " WHERE entry.value.age >= $1 AND entry.value.state = $2"
			+ " ORDER BY entry.value.name ASC, entry.value.dob DESC"
			+ " LIMIT 20";

		String actualKeysQuery = queryString.getKeysQuery();

		assertThat(actualKeysQuery).isEqualTo(expectedKeysQuery);

		String expectedValuesQuery = "<TRACE> <HINT stateIdx> IMPORT example.Customer"
			+ " SELECT DISTINCT entry.value.name, entry.value.dob, entry.value.city"
			+ " FROM /Customers.entrySet entry"
			+ " WHERE entry.key IN SET (1, 2, 3)"
			+ " ORDER BY entry.value.name ASC, entry.value.dob DESC"
			+ " LIMIT 20";

		String actualValuesQuery = queryString.getValuesQuery(1, 2, 3);

		assertThat(actualValuesQuery).isEqualTo(expectedValuesQuery);

		verify(mockRegion, atLeastOnce()).getFullPath();
	}
}
