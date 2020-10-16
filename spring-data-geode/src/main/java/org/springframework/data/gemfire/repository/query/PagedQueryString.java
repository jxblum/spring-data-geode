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

import static org.springframework.data.gemfire.util.RuntimeExceptionFactory.newIllegalStateException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.geode.cache.Region;

import org.springframework.data.gemfire.repository.query.support.OqlKeyword;
import org.springframework.data.gemfire.util.ArrayUtils;
import org.springframework.data.gemfire.util.CollectionUtils;
import org.springframework.data.gemfire.util.RegionUtils;
import org.springframework.data.gemfire.util.SpringUtils;
import org.springframework.data.util.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * {@link QueryString} implementation handling {@literal paging} functionality and behavior.
 *
 * @author John Blum
 * @see java.util.function.Function
 * @see org.apache.geode.cache.Region
 * @see org.springframework.data.gemfire.repository.query.QueryString
 * @see org.springframework.data.gemfire.repository.query.support.OqlKeyword
 * @since 2.4.0
 */
// TODO consider whether to use JavaCC or ANTLR to parse Apache Geode OQL query statements.
//  Using a robust grammar/parser generator would be more reliable than the custom parsing logic now, particularly for
//  Non-Derived OQL query statements, however, would be re-inventing the wheel!
public class PagedQueryString extends QueryString {

	protected static final String ALIAS = "entry";
	protected static final String ALIAS_VALUE = String.format("%s.value", ALIAS);
	protected static final String ALIAS_VALUE_DOT = String.format("%s.", ALIAS_VALUE);
	protected static final String SELECT = OqlKeyword.SELECT.getKeyword();
	protected static final String SELECT_DISTINCT = String.format("%1$s %2$s", OqlKeyword.SELECT, OqlKeyword.DISTINCT);
	protected static final String KEYS_PROJECTION = String.format("%s.key", ALIAS);
	protected static final String FROM = OqlKeyword.FROM.getKeyword();
	protected static final String FROM_REGION_ENTRIES = String.format("%1$s %2$s.entrySet %3$s", FROM, "%s", ALIAS);
	protected static final String WHERE = OqlKeyword.WHERE.getKeyword();
	protected static final String WHERE_KEYS_IN_PREDICATE_CLAUSE = String.format("%1$s %2$s.key IN SET $1", WHERE, ALIAS);
	protected static final String ORDER_BY = OqlKeyword.ORDER_BY.getKeyword();
	protected static final String ORDER_BY_CLAUSE = String.format("%1$s %2$s.key %3$s", ORDER_BY, ALIAS, OqlKeyword.ASC);
	protected static final String LIMIT = OqlKeyword.LIMIT.getKeyword();

	/**
	 * Factory method used to construct a new instance of {@link PagedQueryString} from an existing, {@literal non-null}
	 * {@link QueryString}.
	 *
	 * @param queryString {@link QueryString} on which the {@link PagedQueryString} will be based;
	 * must not be {@literal null}.
	 * @return a new instance of {@link PagedQueryString} initialized with the {@literal OQL query statement}
	 * from the given {@link QueryString}.
	 * @throws IllegalArgumentException if {@link QueryString} is {@literal null}.
	 * @see org.springframework.data.gemfire.repository.query.QueryString
	 * @see #of(String)
	 */
	public static PagedQueryString of(@NonNull QueryString queryString) {

		Assert.notNull(queryString, "QueryString must not be null");

		return of(queryString.getQuery());
	}

	/**
	 * Factory method used to construct a new instance of {@link PagedQueryString} initialized with
	 * the given {@link String OQL query statement}.
	 *
	 * @param query {@link String} containing the OQL query statement; must not be {@literal null} or {@literal empty}.
	 * @return a new instance of {@link PagedQueryString} initialized with the given {@link String OQL query statement}.
	 * @throws IllegalArgumentException if the {@link String OQL query} is {@literal null} or {@literal empty}.
	 * @see #PagedQueryString(String)
	 */
	public static PagedQueryString of(@NonNull String query) {
		return new PagedQueryString(query);
	}

	private final AtomicBoolean keysQueryResolved = new AtomicBoolean(false);

	private final BiFunction<Region<?, ?>, Iterable<?>, String> valuesQuery;

	private final Function<Region<?, ?>, String> keysQuery;

	private Optional<String> queryHintsImportsTrace;
	private Optional<String> queryOrderByClause;

	private GemfireRepositoryQuery repositoryQuery;

	private Region<?, ?> region;

	/**
	 * Constructs a new instance of {@link PagedQueryString} initialized with
	 * the given {@link String OQL query statement}.
	 *
	 * @param query {@link String} containing the OQL query statement; must not be {@literal null} or {@literal empty}.
	 * @throws IllegalArgumentException if the {@link String OQL query} is {@literal null} or {@literal empty}.
	 */
	public PagedQueryString(@NonNull String query) {

		super(query);

		this.keysQuery = keysQueryFunction();
		this.valuesQuery = valuesQueryFunction();
	}

	/**
	 * {@link Function} implementation lazily requiring a {@link Region} returning a {@literal query for keys}
	 * {@link String OQL query statement} in the 2-phased paged query execution.
	 *
	 * @return a {@link Function} implementation lazily returning a {@literal query for keys}
	 * {@link String OQL query statement} in the 2-phased paged query execution.
	 * @throws IllegalArgumentException if {@link Region} is {@literal null}.
	 * @see org.apache.geode.cache.Region
	 * @see java.util.function.Function
	 */
	private @NonNull Function<Region<?, ?>, String> keysQueryFunction() {

		return region -> Lazy.of(() -> {

			Assert.notNull(region, "Region must not be null");

			String query = getQuery();
			String distinctQuery = asDistinct(query);

			this.queryHintsImportsTrace = parseOptionalHintsImportsTraceFrom(distinctQuery);
			this.queryOrderByClause = parseOptionalOrderByClauseFrom(distinctQuery);

			String existingAlias = resolveAlias(distinctQuery, region);

			String optionalWhereOrderLimitClauses =
				parseOptionalWhereOrderByAndLimitClausesFrom(distinctQuery, region).orElse(EMPTY_STRING);

			optionalWhereOrderLimitClauses =
				substituteAlias(optionalWhereOrderLimitClauses, existingAlias, ALIAS_VALUE_DOT);

			String resolvedQueryHintsImportsTrace = this.queryHintsImportsTrace.orElse(EMPTY_STRING);

			// TODO what about 'SELECT .. FROM .. WHERE ... LIMIT #'?
			String keysQuery = concatQueryClauses(resolvedQueryHintsImportsTrace,
				SELECT_DISTINCT,
				KEYS_PROJECTION,
				fromRegionEntries(region),
				optionalWhereOrderLimitClauses,
				imposeOrderByClause(distinctQuery)
			);

			this.keysQueryResolved.set(true);

			return keysQuery;

		}).get();
	}

	/**
	 * Concatenates the clauses of a query into a single, complete and executable {@link String query statement}.
	 *
	 * @param clauses array of {@link String Strings} containing the clauses of a query; must not be {@literal null}.
	 * @return a single, complete and executable {@link String query statement} composed of
	 * the individual query clauses.
	 */
	private @NonNull String concatQueryClauses(@NonNull String... clauses) {

		StringBuilder queryBuilder = new StringBuilder();

		for (String clause : ArrayUtils.nullSafeArray(clauses, String.class)) {
			if (StringUtils.hasText(clause)) {
				queryBuilder.append(clause.trim()).append(SINGLE_SPACE);
			}
		}

		return queryBuilder.toString().trim();
	}

	private int findSelectClauseIndexFrom(@Nullable String query) {

		int selectKeywordIndex = String.valueOf(query).toUpperCase().indexOf(SELECT);

		Assert.isTrue(selectKeywordIndex >= 0,
			() -> String.format("Query [%s] must have a SELECT [DISTINCT] clause", query));

		return selectKeywordIndex;
	}

	private int findFromClauseIndexFrom(@Nullable String query) {

		int fromKeywordIndex = String.valueOf(query).toUpperCase().indexOf(FROM);

		Assert.isTrue(fromKeywordIndex >= 0,
			() -> String.format("Query [%s] must have a FROM clause", query));

		return fromKeywordIndex;
	}

	private int findWhereClauseIndexFrom(@Nullable String query) {
		return String.valueOf(query).toUpperCase().indexOf(WHERE);
	}

	private int findOrderByClauseIndexFrom(@Nullable String query) {
		return String.valueOf(query).toUpperCase().indexOf(ORDER_BY);
	}

	private int findLimitClauseIndexFrom(@Nullable String query) {
		return String.valueOf(query).toUpperCase().indexOf(LIMIT);
	}

	private String fromRegionEntries(@NonNull Region<?, ?> region) {
		return String.format(FROM_REGION_ENTRIES, RegionUtils.toRegionPath(region));
	}

	private String imposeOrderByClause(@NonNull String query) {
		return findOrderByClauseIndexFrom(query) > -1 ? EMPTY_STRING : ORDER_BY_CLAUSE;
	}

	private String imposeOrderByClause(@NonNull String query, @Nullable String defaultOrderByClause) {

		String orderByClause = imposeOrderByClause(query);

		return StringUtils.hasText(orderByClause) ? orderByClause
			: SpringUtils.defaultIfEmpty(defaultOrderByClause, EMPTY_STRING);
	}

	/**
	 * Parses the {@literal query projection} from the given {@link String OQL query statement}.
	 *
	 * @param query {@link String} containing the OQL query statement to evaluate; must not be {@literal null}.
	 * @return a {@link String} containing the {@literal query projection from
	 * the given {@link String OQL query statement}.
	 * @throws IllegalArgumentException if the {@link String OQL query statement}
	 * does contain a valid {@liter FROM clause}.
	 * @see #findSelectClauseIndexFrom(String)
	 * @see #findFromClauseIndexFrom(String)
	 */
	private @NonNull String parseProjectionFrom(@NonNull String query) {

		int selectKeywordLength = String.valueOf(query).toUpperCase().contains(OqlKeyword.DISTINCT.getKeyword())
			? SELECT_DISTINCT.length()
			: SELECT.length();

		int selectIndex = findSelectClauseIndexFrom(query);
		int projectionIndex = selectIndex + selectKeywordLength;
		int fromClauseIndex = findFromClauseIndexFrom(query);

		Assert.isTrue(fromClauseIndex > projectionIndex,
			() -> String.format("Query [%s] must contain both SELECT and FROM clauses in the correct order", query));

		return query.substring(projectionIndex, fromClauseIndex).trim();
	}

	/**
	 * Parses {@link Optional} {@literal <HINT>}, {@literal IMPORT} and {@literal <TRACE>} metadata
	 * from the given {@link String OQL query statement}, which must appear before the {@literal SELECT} clause.
	 *
	 * @param query {@link String} containing the OQL query statement to evaluate; must not be {@literal null}.
	 * @return a {@link Optional} {@link String} containing the {@link String OQL query statement} {@literal <HINT>},
	 * {@literal IMPORT} and {@literal <TRACE>} elements.
	 * @see #findSelectClauseIndexFrom(String)
	 * @see java.util.Optional
	 */
	private Optional<String> parseOptionalHintsImportsTraceFrom(@NonNull String query) {

		int selectIndex = findSelectClauseIndexFrom(query);

		return Optional.ofNullable(query)
			.filter(it -> selectIndex > -1)
			.map(it -> it.substring(0, selectIndex))
			.map(String::trim)
			.filter(StringUtils::hasText);
	}

	/**
	 * Parses {@link Optional} {@literal WHERE}, {@literal ORDER BY} and {@literal LIMIT} clauses
	 * from the given {@link String OQL query statement}.
	 *
	 * Technically, this parse method returns the remainder of the {@link String OQL query statement}
	 * beyond the {@literal FROM /Region} clause for the given subject/target Region, which might include:
	 * '[iteratorDefinition [, iteratorDefinition]]* [WHERE <predicates>] [ORDER BY <sort>] [LIMIT [0..9]+]'
	 *
	 * @param query {@link String} containing the OQL query statement to evaluate; must not be {@literal null}.
	 * @param region {@link Region} that is the subject, or target of the query.
	 * @return an {@link Optional} {@literal String} containing the remainder of the {@link String OQL query}
	 * after the {@literal FROM /Region} clause.
	 * @throws IllegalArgumentException if the {@link Region} is not contained in the {@literal FROM} clause
	 * of the {@link String OQL query}.
	 * @see #findWhereClauseIndexFrom(String)
	 * @see #findOrderByClauseIndexFrom(String)
	 * @see #findLimitClauseIndexFrom(String)
	 * @see java.util.Optional
	 */
	private Optional<String> parseOptionalWhereOrderByAndLimitClausesFrom(@NonNull String query,
			@NonNull Region<?, ?> region) {

		String regionPath = RegionUtils.toRegionPath(region);

		int regionBeginIndex = String.valueOf(query).indexOf(regionPath);
		int regionEndIndex = regionBeginIndex + regionPath.length();

		Assert.isTrue(regionBeginIndex > -1,
			() -> String.format("Query [%1$s] must contain a '%2$s %3$s' clause", query, FROM, regionPath));

		String queryAfterFromRegion = query.substring(regionEndIndex);

		int commaIndex = queryAfterFromRegion.indexOf(COMMA_DELIMITER);
		int whereClauseIndex = findWhereClauseIndexFrom(queryAfterFromRegion);
		int orderByClauseIndex = findOrderByClauseIndexFrom(queryAfterFromRegion);
		int limitClauseIndex = findLimitClauseIndexFrom(queryAfterFromRegion);

		if (commaIndex > -1 && commaIndex < whereClauseIndex) {
			queryAfterFromRegion = queryAfterFromRegion.substring(commaIndex);
			commaIndex = 0;
		}

		int resolvedIndex = getMinimumFirstNonNegativeIndex(commaIndex, whereClauseIndex, orderByClauseIndex,
			limitClauseIndex, queryAfterFromRegion.length());

		return Optional.of(queryAfterFromRegion.substring(resolvedIndex))
			.map(String::trim)
			.filter(StringUtils::hasText);
	}

	/**
	 * Parses an {@link Optional} {@literal ORDER BY} clause from the given {@link String OQL query statement}.
	 *
	 * @param query {@link String} containing the OQL query statement to evaluate; must not be {@literal null}.
	 * @return an {@link Optional} {@link String} containing the {@literal ORDER BY} clause
	 * from the given {@link String OQL query statement}.
	 * @see #findOrderByClauseIndexFrom(String)
	 * @see #findLimitClauseIndexFrom(String)
	 */
	private Optional<String> parseOptionalOrderByClauseFrom(@NonNull String query) {

		int orderByClauseIndex = findOrderByClauseIndexFrom(query);
		int limitClauseIndex = findLimitClauseIndexFrom(query);
		int endIndex = limitClauseIndex > orderByClauseIndex ? limitClauseIndex : nullSafeLength(query);

		return Optional.ofNullable(query)
			.filter(it -> orderByClauseIndex > -1)
			.map(it -> it.substring(orderByClauseIndex, endIndex))
			.map(String::trim)
			.filter(StringUtils::hasText);
	}

	/**
	 * Parses an {@link Optional} {@literal LIMIT} clause from the given {@link String OQL query statement}.
	 *
	 * @param query {@link String} containing the OQL query statement to evaluate; must not be {@literal null}.
	 * @return an {@link Optional} {@link String} containing the {@literal LIMIT} clause
	 * from the given {@link String OQL query statement}.
	 * @see #findLimitClauseIndexFrom(String)
	 * @see java.util.Optional
	 */
	private Optional<String> parseOptionalLimitClauseFrom(@NonNull String query) {

		int limitClauseIndex = findLimitClauseIndexFrom(query);

		return Optional.ofNullable(query)
			.filter(it -> limitClauseIndex > -1)
			.map(it -> {

				Matcher matcher = LIMIT_PATTERN.matcher(it);

				return matcher.find()
					? it.substring(matcher.start(), matcher.end()).trim()
					: EMPTY_STRING;
			})
			.filter(StringUtils::hasText);
	}

	private int getMinimumFirstNonNegativeIndex(@NonNull int... indexes) {

		int resolvedIndex = Integer.MAX_VALUE;

		for (int index : indexes) {
			if (index > -1) {
				resolvedIndex = Math.min(resolvedIndex, index);
			}
		}

		return Math.max(resolvedIndex, 0);
	}

	private boolean isAlphabeticOnly(String value) {

		if (StringUtils.hasText(value)) {
			for (char c : value.trim().toCharArray()) {
				if (!Character.isAlphabetic(c)) {
					return false;
				}
			}

			return true;
		}

		return false;
	}

	private boolean isAliasUsed(@NonNull String query, String alias) {

		boolean hasWhereOrOrderByClauses =
			findWhereClauseIndexFrom(query) > -1 || findOrderByClauseIndexFrom(query) > -1;

		String aliasDot = String.format("%s.", alias);

		return !hasWhereOrOrderByClauses || query.contains(aliasDot);
	}

	private int nullSafeLength(@Nullable String value) {
		return value != null ? value.length() : 0;
	}

	private String resolveAlias(@NonNull String query, @NonNull Region<?, ?> region) {

		String regionPath = RegionUtils.toRegionPath(region);

		int regionPathIndex = String.valueOf(query).indexOf(regionPath);

		Assert.isTrue(regionPathIndex > -1,
			() -> String.format("Region [%1$s] must be present in the FROM clause of the query [%2$s]",
				regionPath, query));

		String[] queryTokens = query.substring(regionPathIndex).split(SINGLE_SPACE);

		return Optional.ofNullable(queryTokens)
			.filter(array -> array.length > 1)
			.map(array -> OqlKeyword.AS.getKeyword().equalsIgnoreCase(array[1]) ? array[2] : array[1])
			.filter(StringUtils::hasText)
			.filter(alias -> !OqlKeyword.isKeyword(alias))
			.filter(this::isAlphabeticOnly)
			.filter(alias -> isAliasUsed(query, alias))
			.orElseThrow(() ->
				newIllegalStateException(String.format("Query [%1$s] must contain an alias for Region [%2$s]",
					query, region.getName())));
	}

	private String substituteAlias(@NonNull String query, @NonNull String aliasToReplace, @NonNull String aliasToUse) {

		if (StringUtils.hasText(query)) {

			Assert.hasText(aliasToReplace, String.format("Alias [%s] to replace is required", aliasToReplace));

			//String aliasToReplacePlusDotRegex = String.format("%s\\.(?:entry\\.key)", aliasToReplace);
			//String aliasToReplacePlusDotRegex = String.format("%s\\.(^entry\\.key)", aliasToReplace);
			String aliasToReplacePlusDotRegex = String.format("%s\\.", aliasToReplace);

			return query.replaceAll(aliasToReplacePlusDotRegex, aliasToUse);
		}

		return query;
	}

	/**
	 * {@link BiFunction} implementation requiring a {@link Region} and {@link Iterable} of {@literal KEYS},
	 * returning a {@literal query for values} {@link String OQL query statement} in the 2-phased paged query execution.
	 *
	 * @return a {@link BiFunction} implementation returning a {@literal query for values}
	 * {@link String OQL query statement} in the 2-phased paged query execution.
	 * @throws IllegalArgumentException if {@link Region} is {@literal null}.
	 * @throws IllegalStateException if the {@literal query for keys} has not been computed.
	 * @see org.apache.geode.cache.Region
	 * @see java.util.function.BiFunction
	 * @see java.lang.Iterable
	 */
	private @NonNull BiFunction<Region<?, ?>, Iterable<?>, String> valuesQueryFunction() {

		return (region, keys) -> {

			Assert.notNull(region, "Region must not be null");

			Assert.state(keysQueryResolved.get(),
				"The KEYS based OQL query (i.e. query for keys) must be determined before the VALUES based OQL query (i.e. query for values)");

			String query = getQuery();

			String existingAlias = resolveAlias(query, region);

			String queryProjection = substituteAlias(parseProjectionFrom(query), existingAlias, ALIAS_VALUE_DOT);

			String queryOrderByClause = this.queryOrderByClause
				.map(orderByClause -> substituteAlias(orderByClause, existingAlias, ALIAS_VALUE_DOT))
				.orElse(EMPTY_STRING);

			String queryLimit = parseOptionalLimitClauseFrom(query).orElse(EMPTY_STRING);

			String valuesQueryTemplate = concatQueryClauses(this.queryHintsImportsTrace.orElse(EMPTY_STRING),
				SELECT_DISTINCT,
				queryProjection,
				fromRegionEntries(region),
				WHERE_KEYS_IN_PREDICATE_CLAUSE,
				imposeOrderByClause(queryOrderByClause, queryOrderByClause),
				queryLimit
			);

			Collection<?> resolvedKeys = resolveKeys(keys);

			String valuesQuery = bindInKeys(valuesQueryTemplate, resolvedKeys);

			return valuesQuery;
		};
	}

	private @NonNull Collection<?> resolveKeys(@Nullable Iterable<?> keys) {

		return StreamSupport.stream(CollectionUtils.nullSafeIterable(keys).spliterator(), false)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	private String bindInKeys(@NonNull String query, Collection<?> keys) {

		String prefix = isNumeric(keys) ? EMPTY_STRING : SINGLE_QUOTE;
		String suffix = prefix;

		return query.replaceFirst(IN_PATTERN, String.format("(%s)",
			StringUtils.collectionToDelimitedString(keys, COMMA_SPACE_DELIMITER, prefix, suffix)));
	}

	private boolean isNumeric(Collection<?> values) {

		return Optional.ofNullable(values)
			.map(Collection::iterator)
			.filter(Iterator::hasNext)
			.map(Iterator::next)
			.filter(this::isNumeric)
			.isPresent();
	}

	private boolean isNumeric(Object value) {

		try {
			Long.parseLong(String.valueOf(value).trim());
			return true;
		}
		catch (Throwable ignore) {
			return false;
		}
	}

	/**
	 * Returns an {@link Optional} reference to the {@link GemfireQueryMethod} modeling the
	 * {@link String OQL query statement} represented by this {@link PagedQueryString}
	 *
	 * @return an {@link Optional} reference to the {@link GemfireQueryMethod} modeling the
	 * {@link String OQL query statement} represented by this {@link PagedQueryString}.
	 * @see org.springframework.data.gemfire.repository.query.GemfireQueryMethod
	 * @see java.util.Optional
	 */
	@SuppressWarnings("unused")
	protected Optional<GemfireQueryMethod> getQueryMethod() {
		return getRepositoryQuery().map(GemfireRepositoryQuery::getGemfireQueryMethod);
	}

	/**
	 * Returns an {@link Optional} reference to the configured {@literal target} {@link Region} used as
	 * the {@literal subject} of this paged OQL query.
	 *
	 * @return an {@link Optional} reference to the configured {@literal target} {@link Region} used as
	 * the {@literal subject} of this paged OQL query.
	 * @see org.apache.geode.cache.Region
	 * @see java.util.Optional
	 */
	protected Optional<Region<?, ?>> getRegion() {
		return Optional.ofNullable(this.region);
	}

	/**
	 * Attempts to resolve the configured reference to the {@literal target} {@link Region} used as
	 * the {@literal subject} of this paged OQL query.
	 *
	 * @return the resolved, configured reference to the {@literal target} {@link Region} used as
	 * the {@literal subject} of this paged OQL query.
	 * @throws IllegalStateException if the {@link Region} reference was not configured.
	 * @see org.apache.geode.cache.Region
	 * @see #getRegion()
	 */
	protected @NonNull Region<?, ?> resolveRegion() {
		return getRegion().orElseThrow(() -> newIllegalStateException("Region was not configured"));
	}

	/**
	 * Returns an {@link Optional} reference to the configured {@link GemfireRepositoryQuery}
	 * (and underlying {@link GemfireQueryMethod}) from which this {@link PagedQueryString} was derived.
	 *
	 * @return an {@link Optional} reference to the configured {@link GemfireRepositoryQuery}
	 * (and underlying {@link GemfireQueryMethod}) from which this {@link PagedQueryString} was derived.
	 * @see org.springframework.data.gemfire.repository.query.GemfireRepositoryQuery
	 * @see java.util.Optional
	 */
	protected Optional<GemfireRepositoryQuery> getRepositoryQuery() {
		return Optional.ofNullable(this.repositoryQuery);
	}

	/**
	 * Attempts to resolve the configured {@link GemfireRepositoryQuery} (and underlying {@link GemfireQueryMethod})
	 * from which this {@link PagedQueryString} was derived.
	 *
	 * @return the resolved, configured {@link GemfireRepositoryQuery} (and underlying {@link GemfireQueryMethod})
	 * from which this {@link PagedQueryString} was derived.
	 * @throws IllegalStateException if the {@link GemfireRepositoryQuery} was not configured.
	 * @see org.springframework.data.gemfire.repository.query.GemfireRepositoryQuery
	 * @see #getRepositoryQuery()
	 */
	protected @NonNull GemfireRepositoryQuery resolveRepositoryQuery() {
		return getRepositoryQuery().orElseThrow(() -> newIllegalStateException("RepositoryQuery was not configured"));
	}

	/**
	 * Returns the computed {@literal query for keys} in the 2-phased paging query execution strategy/implementation.
	 *
	 * The subject/target {@link Region} is resolved from the configured {@link Region}.
	 *
	 * @return the computed {@literal query for keys} in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalStateException if the {@link Region} could not be resolved from configuration.
	 * @see org.apache.geode.cache.Region
	 * @see #resolveRegion()
	 */
	public @NonNull String getKeysQuery() {
		return getKeysQuery(resolveRegion());
	}

	/**
	 * Returns a computed {@literal query for keys} with the given {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 *
	 * @param region {@link Region} used as the subject/target of the {@literal query for keys};
	 * must not be {@literal null}.
	 * @return the computed {@literal query for keys} with the {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalStateException if {@link Region} is {@literal null}.
	 * @see org.apache.geode.cache.Region
	 */
	public @NonNull String getKeysQuery(@NonNull Region<?, ?> region) {
		return this.keysQuery.apply(region);
	}

	/**
	 * Returns the computed {@literal query for values} in the 2-phased paging query execution strategy/implementation.
	 *
	 * The subject/target {@link Region} is resolved from the configured {@link Region}.
	 *
	 * @param keys array of {@link Object keys} used to target specific values in the values query;
	 * must not be {@literal null}.
	 * @return the computed {@literal query for value} in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalStateException if the {@link Region} could not be resolved from configuration.
	 * @see org.apache.geode.cache.Region
	 * @see #resolveRegion()
	 */
	public @NonNull String getValuesQuery(@NonNull Object... keys) {
		return getValuesQuery(resolveRegion(), keys);
	}

	/**
	 * Returns the computed {@literal query for values} in the 2-phased paging query execution strategy/implementation.
	 *
	 * The subject/target {@link Region} is resolved from the configured {@link Region}.
	 *
	 * @param keys {@link Iterable} collection of {@literal keys} used to target specific values in the values query;
	 * must not be {@literal null}.
	 * @return the computed {@literal query for value} in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalStateException if the {@link Region} could not be resolved from configuration.
	 * @see org.apache.geode.cache.Region
	 * @see #resolveRegion()
	 */
	public @NonNull String getValuesQuery(@NonNull Iterable<?> keys) {
		return getValuesQuery(resolveRegion(), keys);
	}

	/**
	 * Returns a computed {@literal query for values} with the given {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 *
	 * @param region {@link Region} used as the subject/target of the {@literal query for values};
	 * must not be {@literal null}.
	 * @param keys array of {@link Object keys} used to target specific values in the values query;
	 * must not be {@literal null}.
	 * @return the computed {@literal query for values} with the {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalArgumentException if {@link Region} is {@literal null}.
	 * @see org.apache.geode.cache.Region
	 */
	public @NonNull String getValuesQuery(@NonNull Region<?, ?> region, @NonNull Object... keys) {
		return getValuesQuery(region, Arrays.asList(ArrayUtils.nullSafeArray(keys, Object.class)));
	}

	/**
	 * Returns a computed {@literal query for values} with the given {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 *
	 * @param region {@link Region} used as the subject/target of the {@literal query for values};
	 * must not be {@literal null}.
	 * @param keys {@link Iterable} collection of {@literal keys} used to target specific values in the values query;
	 * must not be {@literal null}.
	 * @return the computed {@literal query for values} with the {@link Region} as the subject/target of the query
	 * in the 2-phased paging query execution strategy/implementation.
	 * @throws IllegalArgumentException if {@link Region} is {@literal null}.
	 * @see org.apache.geode.cache.Region
	 */
	public @NonNull String getValuesQuery(@NonNull Region<?, ?> region, @NonNull Iterable<?> keys) {
		return this.valuesQuery.apply(region, keys);

	}

	/**
	 * Builder method used to configure the {@literal target} {@link Region} used as the {@literal subject} of
	 * this paged OQL query.
	 *
	 * @param region target {@link Region} used as the {@literal subject} of the paged OQL query.
	 * @return this {@link PagedQueryString}.
	 * @see org.apache.geode.cache.Region
	 */
	public @NonNull PagedQueryString withRegion(@Nullable Region<?, ?> region) {
		this.region = region;
		return this;
	}

	/**
	 * Builder method used to configure the {@link GemfireRepositoryQuery} (and underlying {@link GemfireQueryMethod}
	 * from which this paged OQL query was derived.
	 *
	 * @param repositoryQuery {@link GemfireRepositoryQuery} (and underlying {@link GemfireQueryMethod}
	 * from which this paged OQL query was derived.
	 * @return this {@link PagedQueryString}.
	 * @see org.springframework.data.gemfire.repository.query.GemfireRepositoryQuery
	 */
	public @NonNull PagedQueryString withRepositoryQuery(@Nullable GemfireRepositoryQuery repositoryQuery) {
		this.repositoryQuery = repositoryQuery;
		return this;
	}
}
